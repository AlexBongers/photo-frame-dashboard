package nl.fotolijst.panel;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Searches the Spotify catalogue with the "client credentials" flow: an app id and secret from
 * developer.spotify.com give a token that can search tracks, albums and playlists but sees nobody's
 * library. Playing is done through Home Assistant and Sonos, not here.
 */
final class SpotifyClient {
  private static final String TAG = "Panel";

  static final class Item {
    String type;
    String name;
    String sub;
    String uri;
  }

  interface Callback {
    void done(List<Item> items, String error);
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private String id = "";
  private String secret = "";
  private String apiBase = "https://api.spotify.com";
  private String tokenUrl = "https://accounts.spotify.com/api/token";
  private String token;
  private long tokenExpires;

  void configure(String clientId, String clientSecret, String api, String tokenEndpoint) {
    id = clientId == null ? "" : clientId.trim();
    secret = clientSecret == null ? "" : clientSecret.trim();
    apiBase = api == null || api.isEmpty() ? "https://api.spotify.com" : api.replaceAll("/+$", "");
    tokenUrl = tokenEndpoint == null || tokenEndpoint.isEmpty() ? "https://accounts.spotify.com/api/token" : tokenEndpoint;
    token = null;
  }

  boolean configured() {
    return !id.isEmpty() && !secret.isEmpty();
  }

  /** One request to the API host without credentials, to see whether the TLS connection works here. */
  void probe() {
    io.execute(() -> {
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(apiBase + "/v1/search?q=a&type=track").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        Log.i(TAG, "spotify probe: HTTP " + c.getResponseCode() + " from " + c.getURL().getHost());
        c.disconnect();
      } catch (Exception e) {
        Log.i(TAG, "spotify probe failed: " + e);
      }
    });
  }

  void search(String query, Callback callback) {
    io.execute(() -> {
      List<Item> items = new ArrayList<>();
      String error = null;
      try {
        ensureToken();
        String url = apiBase + "/v1/search?type=track,album,playlist&limit=6&market=NL&q="
            + URLEncoder.encode(query, "UTF-8");
        String body = get(url, "Bearer " + token);
        JSONObject root = new JSONObject(body);
        collect(root.optJSONObject("tracks"), "Nummer", items);
        collect(root.optJSONObject("albums"), "Album", items);
        collect(root.optJSONObject("playlists"), "Playlist", items);
      } catch (Exception e) {
        Log.w(TAG, "spotify search failed: " + e);
        error = String.valueOf(e.getMessage() == null ? e : e.getMessage());
      }
      final String err = error;
      main.post(() -> callback.done(items, err));
    });
  }

  private static void collect(JSONObject group, String label, List<Item> out) {
    JSONArray arr = group == null ? null : group.optJSONArray("items");
    for (int i = 0; arr != null && i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue; // playlists can come back as null entries
      Item it = new Item();
      it.type = label;
      it.name = o.optString("name");
      it.uri = o.optString("uri");
      String by = "";
      JSONArray artists = o.optJSONArray("artists");
      if (artists != null && artists.length() > 0) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < artists.length() && k < 2; k++) {
          if (k > 0) sb.append(", ");
          sb.append(artists.optJSONObject(k).optString("name"));
        }
        by = sb.toString();
      } else if (o.optJSONObject("owner") != null) {
        by = o.optJSONObject("owner").optString("display_name");
      }
      JSONObject album = o.optJSONObject("album");
      it.sub = label + (by.isEmpty() ? "" : "  ·  " + by)
          + (album != null && "Nummer".equals(label) ? "  ·  " + album.optString("name") : "");
      if (!it.uri.isEmpty() && !it.name.isEmpty()) out.add(it);
    }
  }

  private void ensureToken() throws Exception {
    if (token != null && System.currentTimeMillis() < tokenExpires - 60000) return;
    if (!configured()) throw new Exception("Spotify is niet ingesteld");
    HttpURLConnection c = (HttpURLConnection) new URL(tokenUrl).openConnection();
    c.setConnectTimeout(8000);
    c.setReadTimeout(10000);
    c.setRequestMethod("POST");
    c.setRequestProperty("Authorization", "Basic "
        + Base64.encodeToString((id + ":" + secret).getBytes("UTF-8"), Base64.NO_WRAP));
    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    c.setDoOutput(true);
    OutputStream os = c.getOutputStream();
    os.write("grant_type=client_credentials".getBytes("UTF-8"));
    os.close();
    String body = read(c);
    if (c.getResponseCode() >= 400) throw new Exception("Spotify weigerde de sleutels (" + c.getResponseCode() + ")");
    JSONObject j = new JSONObject(body);
    token = j.getString("access_token");
    tokenExpires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
  }

  private static String get(String url, String auth) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(8000);
    c.setReadTimeout(10000);
    c.setRequestProperty("Authorization", auth);
    String body = read(c);
    if (c.getResponseCode() >= 400) throw new Exception("Spotify gaf fout " + c.getResponseCode());
    return body;
  }

  private static String read(HttpURLConnection c) throws Exception {
    InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
    StringBuilder sb = new StringBuilder();
    if (is != null) {
      BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
      String line;
      while ((line = r.readLine()) != null) sb.append(line);
      r.close();
    }
    return sb.toString();
  }
}
