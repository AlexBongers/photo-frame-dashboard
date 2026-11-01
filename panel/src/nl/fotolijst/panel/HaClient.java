package nl.fotolijst.panel;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Talks to Home Assistant with a long-lived access token: a WebSocket for the live states
 * (get_states, then state_changed events) and the REST API for calendars, forecasts and services.
 * Callbacks run on the main thread.
 */
final class HaClient {
  private static final String TAG = "Panel";

  static final class State {
    final String state;
    final JSONObject attrs;

    State(String state, JSONObject attrs) {
      this.state = state;
      this.attrs = attrs;
    }

    boolean available() {
      return !"unavailable".equals(state) && !"unknown".equals(state);
    }
  }

  interface Listener {
    /** "verbonden", "verbinden…", or why it is not connected. */
    void onConnection(boolean connected, String text);

    void onState(String entityId);
  }

  interface Callback {
    void done(JSONObject json, JSONArray array);
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<String, State> states = new ConcurrentHashMap<>();
  private final ExecutorService io = Executors.newFixedThreadPool(2);
  private final Listener listener;
  private volatile boolean running;
  private volatile WebSocketClient ws;
  private String baseUrl;
  private String token;

  HaClient(Listener listener) {
    this.listener = listener;
  }

  State get(String entityId) {
    return states.get(entityId);
  }

  void start(String url, String accessToken) {
    stop();
    baseUrl = url.replaceAll("/+$", "");
    token = accessToken;
    running = true;
    Thread t = new Thread(this::connectLoop, "ha-ws");
    t.setDaemon(true);
    t.start();
  }

  void stop() {
    running = false;
    WebSocketClient current = ws;
    if (current != null) current.close();
  }

  private void status(boolean connected, String text) {
    main.post(() -> listener.onConnection(connected, text));
  }

  private void connectLoop() {
    int failures = 0;
    while (running) {
      status(false, failures == 0 ? "verbinden…" : "geen verbinding, opnieuw proberen…");
      final boolean[] gotStates = {false};
      final boolean[] rejected = {false};
      final WebSocketClient[] self = new WebSocketClient[1];
      final Thread[] pinger = new Thread[1];
      self[0] = new WebSocketClient(new WebSocketClient.Listener() {
        @Override
        public void onOpen() {
          pinger[0] = new Thread(() -> {
            int id = 100;
            try {
              while (running && ws == self[0]) {
                Thread.sleep(25000);
                self[0].sendText("{\"id\":" + (id++) + ",\"type\":\"ping\"}");
              }
            } catch (Exception ignored) {
              // connection ended
            }
          }, "ha-ping");
          pinger[0].setDaemon(true);
          pinger[0].start();
        }

        @Override
        public void onText(String text) {
          try {
            long parseStart = System.currentTimeMillis();
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            if (text.length() > 100000) Log.i(TAG, "parsed " + text.length() / 1024 + " KB JSON in "
                + (System.currentTimeMillis() - parseStart) + " ms");
            if ("auth_required".equals(type)) {
              self[0].sendText(new JSONObject().put("type", "auth").put("access_token", token).toString());
            } else if ("auth_ok".equals(type)) {
              self[0].sendText("{\"id\":1,\"type\":\"get_states\"}");
              self[0].sendText("{\"id\":2,\"type\":\"subscribe_events\",\"event_type\":\"state_changed\"}");
            } else if ("auth_invalid".equals(type)) {
              rejected[0] = true;
              status(false, "token geweigerd");
              self[0].close();
            } else if ("result".equals(type) && m.optInt("id") == 1 && m.optBoolean("success")) {
              long t0 = System.currentTimeMillis();
              JSONArray all = m.getJSONArray("result");
              for (int i = 0; i < all.length(); i++) {
                JSONObject s = all.getJSONObject(i);
                put(s.getString("entity_id"), s);
              }
              Log.i(TAG, "get_states: " + all.length() + " entities stored in "
                  + (System.currentTimeMillis() - t0) + " ms (message " + text.length() / 1024 + " KB)");
              gotStates[0] = true;
              status(true, "verbonden");
              main.post(() -> listener.onState(null));
            } else if ("event".equals(type)) {
              JSONObject data = m.getJSONObject("event").getJSONObject("data");
              String id = data.getString("entity_id");
              JSONObject next = data.optJSONObject("new_state");
              if (next == null) {
                states.remove(id);
              } else {
                put(id, next);
              }
              main.post(() -> listener.onState(id));
            }
          } catch (Exception e) {
            Log.w(TAG, "bad message: " + e);
          }
        }
      });
      ws = self[0];
      String why = self[0].run(baseUrl.replaceFirst("^http", "ws") + "/api/websocket");
      Log.i(TAG, "websocket ended: " + why);
      if (!running) return;
      failures = gotStates[0] ? 0 : failures + 1;
      try {
        Thread.sleep(rejected[0] ? 30000 : Math.min(15000, 2000 + 2000L * failures));
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private void put(String id, JSONObject s) {
    JSONObject attrs = s.optJSONObject("attributes");
    states.put(id, new State(s.optString("state", "unknown"), attrs == null ? new JSONObject() : attrs));
  }

  /** GET or POST a JSON endpoint; the callback gets the parsed body (object or array) or nulls. */
  void request(String method, String path, String body, Callback callback) {
    io.execute(() -> {
      JSONObject json = null;
      JSONArray array = null;
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
          c.setRequestProperty("Content-Type", "application/json");
          c.setDoOutput(true);
          OutputStream os = c.getOutputStream();
          os.write(body.getBytes("UTF-8"));
          os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
          BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
          String line;
          while ((line = r.readLine()) != null) sb.append(line);
          r.close();
        }
        c.disconnect();
        if (code < 400) {
          String text = sb.toString().trim();
          if (text.startsWith("[")) array = new JSONArray(text);
          else if (text.startsWith("{")) json = new JSONObject(text);
          else json = new JSONObject();
        } else {
          Log.w(TAG, method + " " + path + " -> " + code);
        }
      } catch (Exception e) {
        Log.w(TAG, method + " " + path + " failed: " + e);
      }
      final JSONObject j = json;
      final JSONArray a = array;
      if (callback != null) main.post(() -> callback.done(j, a));
    });
  }

  interface BitmapCallback {
    void done(Bitmap bitmap);
  }

  /** Downloads an image from the Home Assistant server (album art), scaled down to at most maxSide pixels. */
  void fetchBitmap(String path, int maxSide, BitmapCallback callback) {
    io.execute(() -> {
      Bitmap result = null;
      try {
        String url = path.startsWith("http") ? path : baseUrl + path;
        byte[] data;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        InputStream is = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        c.disconnect();
        data = out.toByteArray();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        result = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
      } catch (Exception e) {
        Log.w(TAG, "image " + path + " failed: " + e);
      }
      final Bitmap b = result;
      main.post(() -> callback.done(b));
    });
  }

  void callService(String domain, String service, JSONObject data) {
    request("POST", "/api/services/" + domain + "/" + service, data.toString(), null);
  }
}
