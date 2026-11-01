package nl.fotolijst.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fotolijst Panel: a native Home Assistant wall panel. No WebView; it reads states over the
 * WebSocket API with a long-lived access token and draws them with plain Android views.
 *
 * Setup over USB (once; the values are saved on the frame):
 *   adb shell am start -n nl.fotolijst.panel/.MainActivity --es ha_url http://192.168.1.10:8123 --es ha_token TOKEN
 * Make it the Home app:
 *   adb shell am start -n nl.fotolijst.panel/.MainActivity --ez home true
 * The layout comes from assets/panel.json, or from /data/data/nl.fotolijst.panel/files/panel.json if present.
 */
public class MainActivity extends Activity implements HaClient.Listener {
  private static final String TAG = "Panel";
  private static final String PREFS = "panel";

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, List<Runnable>> bindings = new HashMap<>();
  private final Set<String> dirty = new HashSet<>();
  private final List<View> pages = new ArrayList<>();
  private final List<TextView> tabViews = new ArrayList<>();
  private boolean flushScheduled;

  private HaClient ha;
  private JSONObject config;
  private boolean dark;
  private int cBg, cCard, cBorder, cText, cSub, cAccent, cOnBg, cOnText, cPress;
  private float density;
  private TextView clock;
  private TextView statusDot;
  private TextView statusText;
  private String statusMessage = "verbinden…";

  // Today page
  private LinearLayout agendaList;
  private TextView agendaTitle;
  private int agendaOffset;
  private TextView agendaNote;
  private WeatherIcon nowIcon;
  private TextView nowTemp;
  private TextView nowCondition;
  private final TextView[] fcDay = new TextView[5];
  private final TextView[] fcHigh = new TextView[5];
  private final TextView[] fcLow = new TextView[5];
  private final WeatherIcon[] fcIcon = new WeatherIcon[5];
  private String weatherEntity;
  private JSONArray calendars;
  private AmbientLight ambient;
  private NightMode night;
  private long lastTouch = System.currentTimeMillis();
  private boolean autoTheme;
  private View dimOverlay;
  private float dimAlpha;
  private int currentPage;
  private boolean statusConnected;
  private final List<String> pageTypes = new ArrayList<>();

  // Muziek page
  private final List<String> musicPlayers = new ArrayList<>();
  private final List<TextView> musicTileName = new ArrayList<>();
  private final List<TextView> musicTileState = new ArrayList<>();
  private final List<View> musicTiles = new ArrayList<>();
  private final List<TextView> presetViews = new ArrayList<>();
  private final List<String> presetNames = new ArrayList<>();
  private final LruCache<String, Bitmap> artCache = new LruCache<>(6);
  private String musicSelected;
  private String artPath = "";
  private String presetKey = "";
  private ImageView musicArt;
  private View musicArtNote;
  private TextView musicTitle;
  private TextView musicArtist;
  private TextView musicAlbum;
  private TextView musicTime;
  private TextView musicDuration;
  private LinearLayout musicCenter;
  private int musicTint;

  // Boodschappen page (Home Assistant todo list, for example the Bring! integration)
  private String shopEntity;
  private LinearLayout shopToBuy;
  private LinearLayout shopRecent;
  private TextView shopCount;
  private EditText shopInput;
  private int shopGeneration;
  private final SpotifyClient spotify = new SpotifyClient();
  private boolean searchMode;
  private EditText searchInput;
  private TextView searchClear;
  private JSONArray musicPlaylists;
  private final Map<String, String> lastPlaylist = new HashMap<>();
  private final Map<String, Integer> artTints = new HashMap<>();
  private TextView musicVolText;
  private LinearLayout presetList;
  private View progressFill;
  private View progressRest;
  private MediaIcon musicPlay;
  private MediaIcon musicShuffle;
  private MediaIcon musicRepeat;
  private MediaIcon musicMute;
  private BrightnessBar musicVolume;

  // ------------------------------------------------------------------ lifecycle

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    density = getResources().getDisplayMetrics().density;
    applyIntent(getIntent());
    loadConfig();
    buildUi();
    ha = new HaClient(this);
    startClient();
    handler.post(tick);
    setupAmbient();
    setupNight();
  }

  private void setupNight() {
    JSONObject cfg = config.optJSONObject("night");
    boolean on = (cfg == null || cfg.optBoolean("enabled", true)) && prefs().getBoolean("night", true);
    night = on ? new NightMode(this, cfg) : null;
  }

  /** Brightness, dim layer and theme follow the light sensor unless "ambient" is off in panel.json or via the ambient extra. */
  private void setupAmbient() {
    JSONObject cfg = config.optJSONObject("ambient");
    boolean enabled = (cfg == null || cfg.optBoolean("enabled", true)) && prefs().getBoolean("ambient", true);
    if (!enabled) return;
    ambient = new AmbientLight(this, cfg, autoTheme, dark, new AmbientLight.Listener() {
      @Override
      public void onLevels(float brightness, float dim) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = brightness;
        getWindow().setAttributes(lp);
        dimAlpha = dim;
        if (dimOverlay != null) dimOverlay.setAlpha(dim);
      }

      @Override
      public void onTheme(boolean toDark) {
        setTheme(toDark);
      }
    });
    if (!ambient.available()) Log.w(TAG, "no light sensor on this device");
  }

  @Override
  protected void onResume() {
    super.onResume();
    NightMode.endNight(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    lastTouch = System.currentTimeMillis();
    handler.removeCallbacks(tick);
    handler.post(tick);
    if (ambient != null) ambient.start();
  }

  @Override
  protected void onPause() {
    if (ambient != null) {
      ambient.stop();
      WindowManager.LayoutParams lp = getWindow().getAttributes();
      lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
      getWindow().setAttributes(lp);
    }
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (applyIntent(intent)) startClient();
    if (intent.hasExtra("page")) showPage(intent.getIntExtra("page", 0));
    // For testing the light theme without waiting for daylight: --es theme light|dark
    if (intent.hasExtra("theme")) {
      boolean toDark = !"light".equals(intent.getStringExtra("theme"));
      if (ambient != null) ambient.setDark(toDark);
      setTheme(toDark);
    }
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (ha != null) ha.stop();
    super.onDestroy();
  }

  @Override
  public boolean dispatchTouchEvent(android.view.MotionEvent e) {
    lastTouch = System.currentTimeMillis();
    return super.dispatchTouchEvent(e);
  }

  private void configureSpotify() {
    SharedPreferences p = prefs();
    spotify.configure(p.getString("spotify_id", ""), p.getString("spotify_secret", ""),
        p.getString("spotify_api", ""), p.getString("spotify_token_url", ""));
  }

  private SharedPreferences prefs() {
    return getSharedPreferences(PREFS, MODE_PRIVATE);
  }

  /** Saves ha_url / ha_token and the home flag. Returns true when the connection settings changed. */
  private boolean applyIntent(Intent intent) {
    boolean changed = false;
    if (intent == null) return false;
    SharedPreferences.Editor e = prefs().edit();
    if (intent.hasExtra("ha_url")) {
      e.putString("ha_url", intent.getStringExtra("ha_url"));
      changed = true;
    }
    if (intent.hasExtra("ha_token")) {
      e.putString("ha_token", intent.getStringExtra("ha_token"));
      changed = true;
    }
    // Spotify search: app id and secret from developer.spotify.com, kept in the app's private preferences.
    for (String k : new String[] {"spotify_id", "spotify_secret", "spotify_api", "spotify_token_url"}) {
      if (intent.getBooleanExtra("spotify_reset", false)) e.remove(k);
      else if (intent.hasExtra(k)) e.putString(k, intent.getStringExtra(k));
    }
    e.apply();
    configureSpotify();
    if (intent.getBooleanExtra("spotify_probe", false)) spotify.probe();
    if (intent.hasExtra("night_test") && night != null) {
      // Wait until onResume has run, which ends any earlier night state.
      final int minutes = intent.getIntExtra("night_test", 1);
      handler.postDelayed(() -> Log.i(TAG, "night test: "
          + (night.testSleep(this, minutes) ? "sleeping for " + minutes + " min" : "no permission")), 1500);
    }
    if (intent.hasExtra("night")) {
      prefs().edit().putBoolean("night", intent.getBooleanExtra("night", true)).apply();
      if (night != null) NightMode.endNight(this);
      setupNight();
    }
    if (intent.hasExtra("auto_theme")) {
      prefs().edit().putBoolean("auto_theme", intent.getBooleanExtra("auto_theme", true)).apply();
      autoTheme = intent.getBooleanExtra("auto_theme", true);
      if (ambient != null) ambient.stop();
      ambient = null;
      setupAmbient();
      if (ambient != null) ambient.start();
    }
    if (intent.hasExtra("ambient")) {
      prefs().edit().putBoolean("ambient", intent.getBooleanExtra("ambient", true)).apply();
      if (ambient != null) ambient.stop();
      ambient = null;
      setupAmbient();
      if (ambient != null) ambient.start();
    }
    if (intent.hasExtra("home")) {
      boolean home = intent.getBooleanExtra("home", false);
      getPackageManager().setComponentEnabledSetting(
          new ComponentName(this, getPackageName() + ".Home"),
          home ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
          PackageManager.DONT_KILL_APP);
      Log.i(TAG, "Home alias " + (home ? "on" : "off"));
    }
    return changed;
  }

  private void startClient() {
    String url = prefs().getString("ha_url", "");
    String token = prefs().getString("ha_token", "");
    if (url.isEmpty() || token.isEmpty()) {
      onConnection(false, "nog niet ingesteld (zie adb-commando in de documentatie)");
      return;
    }
    ha.start(url, token);
  }

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      updateClock();
      if (night != null && night.tick(MainActivity.this, ambient == null ? -1 : ambient.smoothedLux(),
          lastTouch, System.currentTimeMillis())) return;
      handler.postDelayed(this, 10000);
    }
  };

  private final Runnable refreshAgendaTask = new Runnable() {
    @Override
    public void run() {
      refreshAgenda();
      refreshForecast();
      handler.postDelayed(this, 10 * 60 * 1000L);
    }
  };

  // ------------------------------------------------------------------ config and theme

  private void loadConfig() {
    try {
      InputStream in;
      File override = new File(getFilesDir(), "panel.json");
      in = override.exists() ? new FileInputStream(override) : getAssets().open("panel.json");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      in.close();
      config = new JSONObject(out.toString("UTF-8"));
    } catch (Exception e) {
      Log.e(TAG, "config: " + e);
      config = new JSONObject();
    }
    JSONObject amb = config.optJSONObject("ambient");
    boolean autoThemeConfig = amb == null || (amb.optJSONObject("auto_theme") == null
        ? true : amb.optJSONObject("auto_theme").optBoolean("enabled", true));
    autoTheme = autoThemeConfig && prefs().getBoolean("auto_theme", true);
    boolean configDark = !"light".equals(config.optString("theme", "dark"));
    dark = autoTheme ? prefs().getBoolean("theme_dark", configDark) : configDark;
    applyPalette();
    weatherEntity = config.optString("weather", "");
    calendars = config.optJSONArray("calendars");
  }

  private void applyPalette() {
    if (dark) {
      cBg = 0xFF0F1115; cCard = 0xFF1B1E24; cBorder = 0xFF2A2E36; cText = 0xFFECEFF4; cSub = 0xFF9AA3B2;
      cAccent = 0xFF4FC3F7; cOnBg = 0xFF4A3B12; cOnText = 0xFFFFD54F; cPress = 0xFF2A2E36;
    } else {
      cBg = 0xFFF5F6F8; cCard = 0xFFFFFFFF; cBorder = 0xFFDDE1E6; cText = 0xFF111418; cSub = 0xFF5F6B7A;
      cAccent = 0xFF0288D1; cOnBg = 0xFFFFE9B0; cOnText = 0xFF5A4200; cPress = 0xFFE3E7EC;
    }
  }

  /** Switches the palette and rebuilds every page; the bindings fill them again from the cached states. */
  private void setTheme(boolean toDark) {
    if (dark == toDark) return;
    dark = toDark;
    prefs().edit().putBoolean("theme_dark", dark).apply();
    applyPalette();
    int keep = currentPage;
    bindings.clear();
    dirty.clear();
    pages.clear();
    pageTypes.clear();
    tabViews.clear();
    buildUi();
    showPage(keep);
    onConnection(statusConnected, statusMessage);
    onState(null);
  }

  // ------------------------------------------------------------------ small view helpers

  private int dp(float v) {
    return Math.round(v * density);
  }

  private TextView tv(String s, float sp, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    return t;
  }

  private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radiusDp));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private StateListDrawable pressable(int fill, int stroke) {
    StateListDrawable s = new StateListDrawable();
    s.addState(new int[] {android.R.attr.state_pressed}, rounded(cPress, stroke, 12));
    s.addState(new int[] {}, rounded(fill, stroke, 12));
    return s;
  }

  private LinearLayout vbox() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout hbox() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    return l;
  }

  private LinearLayout card() {
    LinearLayout c = vbox();
    c.setBackground(rounded(cCard, cBorder, 14));
    c.setPadding(dp(16), dp(14), dp(16), dp(14));
    return c;
  }

  private LinearLayout.LayoutParams lp(int w, int h, float weight) {
    return new LinearLayout.LayoutParams(w, h, weight);
  }

  private LinearLayout.LayoutParams weighted(int margin) {
    LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    p.setMargins(dp(margin), 0, dp(margin), 0);
    return p;
  }

  private LinearLayout.LayoutParams wrapWeighted(int margin) {
    LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    p.setMargins(dp(margin), 0, dp(margin), 0);
    return p;
  }

  // ------------------------------------------------------------------ bindings

  private void bind(String entity, Runnable update) {
    List<Runnable> l = bindings.get(entity);
    if (l == null) {
      l = new ArrayList<>();
      bindings.put(entity, l);
    }
    l.add(update);
    update.run();
  }

  private final Runnable flush = new Runnable() {
    @Override
    public void run() {
      flushScheduled = false;
      Set<String> ids = new HashSet<>(dirty);
      dirty.clear();
      for (String id : ids) {
        List<Runnable> l = bindings.get(id);
        if (l != null) for (Runnable r : l) r.run();
      }
    }
  };

  @Override
  public void onState(String entityId) {
    if (entityId == null) {
      for (List<Runnable> l : bindings.values()) for (Runnable r : l) r.run();
      handler.removeCallbacks(refreshAgendaTask);
      handler.post(refreshAgendaTask);
      return;
    }
    if (!bindings.containsKey(entityId)) return;
    dirty.add(entityId);
    if (!flushScheduled) {
      flushScheduled = true;
      handler.postDelayed(flush, 150);
    }
  }

  @Override
  public void onConnection(boolean connected, String text) {
    statusMessage = text;
    statusConnected = connected;
    if (statusDot != null) statusDot.setTextColor(connected ? 0xFF66BB6A : 0xFFEF5350);
    if (statusText != null) statusText.setText(connected ? "" : text);
  }

  private String value(String entity, int digits, String unit) {
    HaClient.State s = ha == null ? null : ha.get(entity);
    if (s == null || !s.available()) return "–";
    try {
      double v = Double.parseDouble(s.state);
      return Fmt.num(v, digits) + (unit == null || unit.isEmpty() ? "" : " " + unit);
    } catch (NumberFormatException e) {
      return s.state;
    }
  }

  // ------------------------------------------------------------------ UI skeleton

  private void buildUi() {
    LinearLayout root = vbox();
    root.setBackgroundColor(cBg);

    LinearLayout bar = hbox();
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setBackgroundColor(cCard);
    bar.setPadding(dp(8), 0, dp(16), 0);
    LinearLayout tabs = hbox();
    bar.addView(tabs, lp(0, dp(60), 1));

    clock = tv("", 28, cText, true);
    bar.addView(clock);
    statusText = tv("", 16, cSub, false);
    statusText.setPadding(dp(16), 0, dp(6), 0);
    bar.addView(statusText);
    statusDot = tv("●", 22, 0xFFEF5350, false);
    statusDot.setPadding(dp(14), dp(10), dp(6), dp(10));
    statusDot.setOnClickListener(v -> showMenu());
    bar.addView(statusDot);
    root.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), 0));

    FrameLayout content = new FrameLayout(this);
    root.addView(content, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

    JSONArray list = config.optJSONArray("pages");
    for (int i = 0; list != null && i < list.length(); i++) {
      JSONObject page = list.optJSONObject(i);
      if (page == null) continue;
      final int index = i;
      TextView tab = tv(page.optString("title", "Pagina"), 22, cSub, true);
      tab.setGravity(Gravity.CENTER);
      tab.setPadding(dp(22), 0, dp(22), 0);
      tab.setOnClickListener(v -> showPage(index));
      tabs.addView(tab, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 0));
      tabViews.add(tab);
      View view;
      switch (page.optString("type")) {
        case "today": view = buildToday(page); break;
        case "climate": view = buildClimate(page); break;
        case "lights": view = buildLights(page); break;
        case "cards": view = buildCards(page); break;
        case "music": view = buildMusic(page); break;
        case "shopping": view = buildShopping(page); break;
        default: view = tv("Onbekend paginatype", 22, cText, false); break;
      }
      content.addView(view, new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      pages.add(view);
      pageTypes.add(page.optString("type"));
    }
    FrameLayout frame = new FrameLayout(this);
    frame.addView(root, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    // A black layer over everything goes darker than the backlight can. It lets touches through.
    dimOverlay = new View(this);
    dimOverlay.setBackgroundColor(Color.BLACK);
    dimOverlay.setAlpha(dimAlpha);
    dimOverlay.setClickable(false);
    frame.addView(dimOverlay, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(frame);
    showPage(0);
    updateClock();
  }

  private void showPage(int index) {
    if (index < 0 || index >= pages.size()) return;
    currentPage = index;
    handler.removeCallbacks(musicTick);
    if ("music".equals(pageTypes.get(index))) handler.post(musicTick);
    if ("shopping".equals(pageTypes.get(index))) refreshShopping();
    if ("cards".equals(pageTypes.get(index))) refreshGraph();
    for (int i = 0; i < pages.size(); i++) {
      pages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
      tabViews.get(i).setTextColor(i == index ? cAccent : cSub);
      tabViews.get(i).setBackground(i == index ? underline(cAccent) : null);
    }
  }

  private android.graphics.drawable.Drawable underline(int color) {
    android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(
        new android.graphics.drawable.Drawable[] {new android.graphics.drawable.ColorDrawable(color)});
    d.setLayerInset(0, 0, dp(56), 0, 0);
    return d;
  }

  private void updateClock() {
    Calendar c = Calendar.getInstance();
    clock.setText(Fmt.two(c.get(Calendar.HOUR_OF_DAY)) + ":" + Fmt.two(c.get(Calendar.MINUTE))
        + "  ·  " + Fmt.dayLong(c));
  }

  private void showMenu() {
    String light = ambient == null ? "uit" : ambient.lux() < 0 ? "geen meting"
        : Math.round(ambient.lux()) + " lux, helderheid " + Math.round(ambient.brightness() * 100) + "%";
    final String[] items = {"Wi-Fi-instellingen", "Android-instellingen", "Opnieuw verbinden",
        "Status: " + statusMessage, "Lichtsensor: " + light,
        "Nachtstand: " + (night == null ? "uit" : night.describe())};
    new AlertDialog.Builder(this).setItems(items, (d, which) -> {
      try {
        if (which == 0) startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else if (which == 1) startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else if (which == 2) startClient();
        else if (which == 5 && night != null && !night.canWrite()) {
          startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
              android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
      } catch (Exception e) {
        Log.w(TAG, "menu: " + e);
      }
    }).show();
  }

  // ------------------------------------------------------------------ page: Vandaag

  private View buildToday(JSONObject page) {
    LinearLayout root = vbox();
    root.setPadding(dp(16), dp(12), dp(16), dp(12));

    LinearLayout agendaCard = card();
    LinearLayout nav = hbox();
    nav.setGravity(Gravity.CENTER_VERTICAL);
    TextView prev = navButton("‹");
    TextView next = navButton("›");
    TextView todayButton = navButton("Vandaag");
    // Empty in the normal view; shows "Week 45 · 2 nov – 8 nov" while browsing other weeks.
    agendaTitle = tv("", 22, cText, true);
    prev.setOnClickListener(v -> moveAgenda(agendaOffset - 1));
    next.setOnClickListener(v -> moveAgenda(agendaOffset + 1));
    todayButton.setOnClickListener(v -> moveAgenda(0));
    nav.addView(agendaTitle, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    LinearLayout.LayoutParams todayParams = lp(dp(120), dp(44), 0);
    todayParams.rightMargin = dp(8);
    nav.addView(todayButton, todayParams);
    LinearLayout.LayoutParams prevParams = lp(dp(60), dp(44), 0);
    prevParams.rightMargin = dp(8);
    nav.addView(prev, prevParams);
    nav.addView(next, lp(dp(60), dp(44), 0));
    LinearLayout.LayoutParams navParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    navParams.bottomMargin = dp(8);
    agendaCard.addView(nav, navParams);
    agendaNote = tv("Agenda wordt geladen…", 20, cSub, false);
    agendaList = vbox();
    ScrollView scroll = new ScrollView(this);
    scroll.addView(agendaList, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    agendaCard.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    agendaList.addView(agendaNote);
    root.addView(agendaCard, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout row = hbox();
    LinearLayout.LayoutParams rowParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    rowParams.topMargin = dp(12);
    root.addView(row, rowParams);

    // Weather
    LinearLayout weather = card();
    LinearLayout now = hbox();
    now.setGravity(Gravity.CENTER_VERTICAL);
    nowIcon = new WeatherIcon(this, dark);
    now.addView(nowIcon, lp(dp(64), dp(64), 0));
    nowTemp = tv("–", 44, cText, true);
    nowTemp.setPadding(dp(12), 0, dp(14), 0);
    now.addView(nowTemp);
    nowCondition = tv("", 22, cSub, false);
    now.addView(nowCondition);
    weather.addView(now);
    LinearLayout days = hbox();
    LinearLayout.LayoutParams daysParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    daysParams.topMargin = dp(8);
    for (int i = 0; i < 5; i++) {
      LinearLayout col = vbox();
      col.setGravity(Gravity.CENTER_HORIZONTAL);
      fcDay[i] = tv("", 18, cSub, false);
      fcIcon[i] = new WeatherIcon(this, dark);
      fcHigh[i] = tv("", 22, cText, true);
      fcLow[i] = tv("", 18, cSub, false);
      fcDay[i].setGravity(Gravity.CENTER_HORIZONTAL);
      fcHigh[i].setGravity(Gravity.CENTER_HORIZONTAL);
      fcLow[i].setGravity(Gravity.CENTER_HORIZONTAL);
      col.addView(fcDay[i]);
      col.addView(fcIcon[i], lp(dp(44), dp(44), 0));
      col.addView(fcHigh[i]);
      col.addView(fcLow[i]);
      days.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
    weather.addView(days, daysParams);
    row.addView(weather, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    if (!weatherEntity.isEmpty()) {
      bind(weatherEntity, () -> {
        HaClient.State s = ha == null ? null : ha.get(weatherEntity);
        if (s == null) return;
        nowIcon.setCondition(s.state);
        nowCondition.setText(Fmt.condition(s.state));
        double t = s.attrs.optDouble("temperature", Double.NaN);
        nowTemp.setText(Double.isNaN(t) ? "–" : Fmt.num(t, 1) + "°");
      });
    }

    // Temperatures
    LinearLayout temps = card();
    JSONArray items = page.optJSONArray("temps");
    LinearLayout current = null;
    for (int i = 0; items != null && i < items.length(); i++) {
      if (i % 4 == 0) {
        current = hbox();
        current.setGravity(Gravity.CENTER_VERTICAL);
        temps.addView(current, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
      }
      JSONObject it = items.optJSONObject(i);
      final String entity = it.optString("entity");
      LinearLayout cell = vbox();
      cell.setGravity(Gravity.CENTER_HORIZONTAL);
      TextView cellName = tv(it.optString("name"), 18, cSub, false);
      cellName.setGravity(Gravity.CENTER_HORIZONTAL);
      cell.addView(cellName);
      final TextView v = tv("–", 30, cText, true);
      v.setGravity(Gravity.CENTER_HORIZONTAL);
      cell.addView(v);
      current.addView(cell, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
      bind(entity, () -> v.setText(value(entity, 1, "°C")));
    }
    LinearLayout.LayoutParams tp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    tp.leftMargin = dp(12);
    row.addView(temps, tp);
    return root;
  }

  private TextView navButton(String label) {
    TextView b = tv(label, label.length() == 1 ? 30 : 20, cText, true);
    b.setGravity(Gravity.CENTER);
    b.setBackground(pressable(cBorder, 0));
    return b;
  }

  private final Runnable agendaReset = new Runnable() {
    @Override
    public void run() {
      moveAgenda(0);
    }
  };

  /** 0 is today and the six days after; other values are whole Monday to Sunday weeks. */
  private void moveAgenda(int offset) {
    agendaOffset = offset;
    handler.removeCallbacks(agendaReset);
    if (offset != 0) handler.postDelayed(agendaReset, 3 * 60 * 1000L);
    refreshAgenda();
  }

  /** Today view looks ahead agenda_days days (default 10); week views are always 7. */
  private int agendaDays() {
    return agendaOffset == 0 ? Math.max(7, Math.min(21, config.optInt("agenda_days", 10))) : 7;
  }

  private long agendaStart() {
    long today = Fmt.startOfDay(System.currentTimeMillis());
    if (agendaOffset == 0) return today;
    Calendar c = Calendar.getInstance();
    c.setFirstDayOfWeek(Calendar.MONDAY);
    c.setTimeInMillis(today);
    int back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // days since Monday
    return Fmt.addDays(today, -back + 7 * agendaOffset);
  }

  private String agendaLabel(long start) {
    Calendar a = Calendar.getInstance();
    a.setTimeInMillis(start);
    Calendar b = Calendar.getInstance();
    b.setTimeInMillis(Fmt.addDays(start, agendaDays() - 1));
    String range = a.get(Calendar.DAY_OF_MONTH) + " " + Fmt.MONTHS[a.get(Calendar.MONTH)].substring(0, 3)
        + " – " + b.get(Calendar.DAY_OF_MONTH) + " " + Fmt.MONTHS[b.get(Calendar.MONTH)].substring(0, 3);
    if (agendaOffset == 0) return "Komende " + agendaDays() + " dagen  ·  " + range;
    Calendar w = Calendar.getInstance();
    w.setFirstDayOfWeek(Calendar.MONDAY);
    w.setMinimalDaysInFirstWeek(4);
    w.setTimeInMillis(start);
    return "Week " + w.get(Calendar.WEEK_OF_YEAR) + "  ·  " + range;
  }

  private static final class Event {
    long start;
    long end;
    boolean allDay;
    String title;
  }

  private int agendaGeneration;

  private void refreshAgenda() {
    if (agendaList == null || calendars == null || calendars.length() == 0) return;
    final int generation = ++agendaGeneration;
    final long windowStart = agendaStart();
    final int days = agendaDays();
    final long windowEnd = Fmt.addDays(windowStart, days);
    agendaTitle.setText(agendaOffset == 0 ? "" : agendaLabel(windowStart));
    final List<Event> all = Collections.synchronizedList(new ArrayList<Event>());
    final int[] left = {calendars.length()};
    final boolean[] anyOk = {false};
    for (int i = 0; i < calendars.length(); i++) {
      String id = calendars.optString(i);
      String path = "/api/calendars/" + id + "?start=" + Fmt.utcIso(windowStart) + "&end=" + Fmt.utcIso(windowEnd);
      ha.request("GET", path, null, (json, array) -> {
        if (array != null) {
          anyOk[0] = true;
          for (int k = 0; k < array.length(); k++) {
            JSONObject o = array.optJSONObject(k);
            if (o == null) continue;
            Event e = new Event();
            e.title = o.optString("summary", "(zonder titel)");
            JSONObject s = o.optJSONObject("start");
            JSONObject en = o.optJSONObject("end");
            if (s == null) continue;
            if (s.has("date")) {
              e.allDay = true;
              e.start = Fmt.parseDate(s.optString("date"));
              e.end = en != null && en.has("date") ? Fmt.parseDate(en.optString("date")) : Fmt.addDays(e.start, 1);
            } else {
              e.start = Fmt.parseDateTime(s.optString("dateTime"));
              e.end = en != null ? Fmt.parseDateTime(en.optString("dateTime")) : e.start;
            }
            all.add(e);
          }
        }
        if (--left[0] == 0 && generation == agendaGeneration) renderAgenda(all, windowStart, days, anyOk[0]);
      });
    }
  }

  private void renderAgenda(List<Event> events, long windowStart, int days, boolean ok) {
    agendaList.removeAllViews();
    if (!ok) {
      agendaList.addView(tv("Agenda niet beschikbaar", 22, cSub, false));
      return;
    }
    // One block per day with events, then two columns side by side: fill the left one until about half
    // of all lines are used, so a week can be read at a glance. Days are never split across columns.
    final List<View> blocks = new ArrayList<>();
    final List<Integer> lines = new ArrayList<>();
    int totalLines = 0;
    long todayStart = Fmt.startOfDay(System.currentTimeMillis());
    for (int d = 0; d < days; d++) {
      long dayStart = Fmt.addDays(windowStart, d);
      long dayEnd = Fmt.addDays(dayStart, 1);
      List<Event> today = new ArrayList<>();
      for (Event e : events) {
        long end = e.end > e.start ? e.end : e.start + 1;
        if (e.start < dayEnd && end > dayStart) today.add(e);
      }
      if (today.isEmpty()) continue;
      Collections.sort(today, (a1, b1) -> {
        if (a1.allDay != b1.allDay) return a1.allDay ? -1 : 1;
        return Long.compare(a1.start, b1.start);
      });
      Calendar c = Calendar.getInstance();
      c.setTimeInMillis(dayStart);
      String tag = dayStart == todayStart ? "  ·  vandaag" : dayStart == Fmt.addDays(todayStart, 1) ? "  ·  morgen" : "";
      LinearLayout block = vbox();
      block.addView(tv(Fmt.dayLong(c) + tag, 24, cAccent, true));
      for (Event e : today) {
        LinearLayout line = hbox();
        String when = e.allDay ? "hele dag" : Fmt.time(e.start) + " – " + Fmt.time(e.end);
        line.addView(tv(when, 20, cSub, false), lp(dp(142), ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        TextView title = tv(e.title, 24, cText, false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(title, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        p.topMargin = dp(1);
        block.addView(line, p);
      }
      blocks.add(block);
      lines.add(1 + today.size());
      totalLines += 1 + today.size();
    }
    if (blocks.isEmpty()) {
      agendaList.addView(tv("Geen afspraken in deze periode", 24, cSub, false));
      return;
    }
    LinearLayout cols = hbox();
    LinearLayout left = vbox();
    LinearLayout right = vbox();
    int used = 0;
    for (int i = 0; i < blocks.size(); i++) {
      boolean toLeft = used < totalLines / 2.0 || i == 0;
      LinearLayout target = toLeft ? left : right;
      LinearLayout.LayoutParams bp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
      bp.topMargin = target.getChildCount() == 0 ? 0 : dp(8);
      target.addView(blocks.get(i), bp);
      if (toLeft) used += lines.get(i);
    }
    LinearLayout.LayoutParams lpLeft = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    lpLeft.rightMargin = dp(12);
    LinearLayout.LayoutParams lpRight = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    lpRight.leftMargin = dp(12);
    cols.addView(left, lpLeft);
    cols.addView(right, lpRight);
    agendaList.addView(cols, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
  }

  private void refreshForecast() {
    if (weatherEntity == null || weatherEntity.isEmpty()) return;
    String body;
    try {
      body = new JSONObject().put("entity_id", weatherEntity).put("type", "daily").toString();
    } catch (Exception e) {
      return;
    }
    ha.request("POST", "/api/services/weather/get_forecasts?return_response", body, (json, array) -> {
      if (json == null) return;
      JSONObject resp = json.optJSONObject("service_response");
      JSONObject entity = resp == null ? null : resp.optJSONObject(weatherEntity);
      JSONArray fc = entity == null ? null : entity.optJSONArray("forecast");
      if (fc == null) return;
      // The first entries can be today or tomorrow depending on the time; show five from today on.
      long today = Fmt.startOfDay(System.currentTimeMillis());
      int shown = 0;
      for (int i = 0; i < fc.length() && shown < 5; i++) {
        JSONObject f = fc.optJSONObject(i);
        if (f == null) continue;
        long when = Fmt.parseDateTime(f.optString("datetime"));
        if (Fmt.startOfDay(when) < today) continue;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        fcDay[shown].setText(shown == 0 && Fmt.startOfDay(when) == today ? "nu" : Fmt.DAYS_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]);
        fcIcon[shown].setCondition(f.optString("condition"));
        fcHigh[shown].setText(Fmt.num(f.optDouble("temperature", 0), 0) + "°");
        fcLow[shown].setText(f.has("templow") ? Fmt.num(f.optDouble("templow", 0), 0) + "°" : "");
        shown++;
      }
    });
  }

  // ------------------------------------------------------------------ page: Klimaat

  private View buildClimate(JSONObject page) {
    LinearLayout root = hbox();
    root.setPadding(dp(8), dp(12), dp(8), dp(12));
    JSONArray items = page.optJSONArray("items");
    int count = items == null ? 0 : items.length();
    int columns = count <= 1 ? 1 : 2;
    int rows = Math.max(1, (count + columns - 1) / columns);
    LinearLayout grid = vbox();
    for (int r = 0; r < rows; r++) {
      LinearLayout row = hbox();
      for (int c = 0; c < columns; c++) {
        int i = r * columns + c;
        LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(dp(6), dp(6), dp(6), dp(6));
        if (i < count) {
          JSONObject it = items.optJSONObject(i);
          row.addView(climateCard(it.optString("entity"), it.optString("name")), p);
        } else {
          row.addView(new View(this), p);
        }
      }
      grid.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }
    root.addView(grid, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    return root;
  }

  private static String modeLabel(String m) {
    switch (m) {
      case "off": return "Uit";
      case "cool": return "Koelen";
      case "heat": return "Verwarmen";
      case "auto": return "Auto";
      case "heat_cool": return "Auto";
      case "dry": return "Drogen";
      case "fan_only": return "Ventileren";
      default: return m;
    }
  }

  private static String fanLabel(String f) {
    switch (f) {
      case "silent": return "Stil";
      case "low": return "Laag";
      case "medium": return "Middel";
      case "high": return "Hoog";
      case "max": return "Max";
      case "auto": return "Auto";
      default: return f.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + f.substring(1);
    }
  }

  private static String shortMode(String m) {
    switch (m) {
      case "cool": return "Koel";
      case "heat": return "Warm";
      default: return modeLabel(m);
    }
  }

  private LinearLayout climateCard(final String entity, String name) {
    LinearLayout card = card();
    card.setPadding(dp(22), dp(18), dp(22), dp(18));
    LinearLayout head = hbox();
    head.setGravity(Gravity.CENTER_VERTICAL);
    final GradientDrawable dotShape = new GradientDrawable();
    dotShape.setShape(GradientDrawable.OVAL);
    dotShape.setColor(cSub);
    View dot = new View(this);
    dot.setBackground(dotShape);
    head.addView(dot, lp(dp(12), dp(12), 0));
    TextView title = tv(name.toUpperCase(java.util.Locale.ROOT), 16, cSub, true);
    title.setLetterSpacing(0.14f);
    title.setPadding(dp(10), 0, 0, 0);
    head.addView(title);
    card.addView(head);
    final TextView status = tv("", 22, cSub, false);
    status.setPadding(0, dp(6), 0, 0);
    card.addView(status);

    final String[] modes = {"off", "cool", "heat", "auto"};
    final TextView[] modeButtons = new TextView[modes.length];
    LinearLayout modeRow = hbox();
    LinearLayout.LayoutParams mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(70), 0);
    mp.topMargin = dp(14);
    for (int i = 0; i < modes.length; i++) {
      final String mode = modes[i];
      TextView b = tv(shortMode(mode), 23, cText, true);
      b.setGravity(Gravity.CENTER);
      b.setBackground(pressable(cBorder, 0));
      b.setOnClickListener(v -> {
        try {
          ha.callService("climate", "set_hvac_mode", new JSONObject().put("entity_id", entity).put("hvac_mode", mode));
        } catch (Exception ignored) {
          // cannot happen for plain strings
        }
      });
      LinearLayout.LayoutParams bp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
      bp.setMargins(dp(3), 0, dp(3), 0);
      modeRow.addView(b, bp);
      modeButtons[i] = b;
    }
    card.addView(modeRow, mp);

    // Fan speed presets, built from the fan_modes the device reports
    final LinearLayout fanRow = hbox();
    fanRow.setVisibility(View.GONE);
    final String[] fanKey = {""};
    LinearLayout.LayoutParams fp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 0);
    fp.topMargin = dp(8);
    card.addView(fanRow, fp);

    final double[] target = {Double.NaN};
    final double[] step = {1};
    final double[] range = {7, 35};
    LinearLayout tempRow = hbox();
    tempRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams tp = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    tp.topMargin = dp(14);
    final TextView minus = tv("−", 46, cText, true);
    final TextView plus = tv("+", 46, cText, true);
    final TextView temp = tv("–", 60, cText, true);
    for (TextView b : new TextView[] {minus, plus}) {
      b.setGravity(Gravity.CENTER);
      b.setBackground(pressable(cBorder, 0));
    }
    temp.setGravity(Gravity.CENTER);
    View.OnClickListener change = v -> {
      if (Double.isNaN(target[0])) return;
      double next = target[0] + (v == plus ? step[0] : -step[0]);
      next = Math.max(range[0], Math.min(range[1], next));
      target[0] = next;
      temp.setText(Fmt.num(next, step[0] < 1 ? 1 : 0) + " °C");
      try {
        ha.callService("climate", "set_temperature", new JSONObject().put("entity_id", entity).put("temperature", next));
      } catch (Exception ignored) {
        // cannot happen for plain numbers
      }
    };
    minus.setOnClickListener(change);
    plus.setOnClickListener(change);
    tempRow.addView(minus, lp(dp(110), ViewGroup.LayoutParams.MATCH_PARENT, 0));
    tempRow.addView(temp, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    tempRow.addView(plus, lp(dp(110), ViewGroup.LayoutParams.MATCH_PARENT, 0));
    card.addView(tempRow, tp);

    bind(entity, () -> {
      HaClient.State s = ha == null ? null : ha.get(entity);
      boolean ok = s != null && s.available();
      if (!ok) {
        status.setText(s == null ? "–" : "Niet beschikbaar");
        for (TextView b : modeButtons) b.setAlpha(0.35f);
        temp.setText("–");
        minus.setAlpha(0.35f);
        plus.setAlpha(0.35f);
        target[0] = Double.NaN;
        return;
      }
      double cur = s.attrs.optDouble("current_temperature", Double.NaN);
      dotShape.setColor("heat".equals(s.state) ? 0xFFFF8A65 : "cool".equals(s.state) ? cAccent
          : "off".equals(s.state) ? cSub : 0xFF81C784);
      status.setText(modeLabel(s.state) + (Double.isNaN(cur) ? "" : "  ·  nu " + Fmt.num(cur, 1) + " °C"));
      JSONArray avail = s.attrs.optJSONArray("hvac_modes");
      for (int i = 0; i < modes.length; i++) {
        boolean has = avail == null;
        for (int k = 0; avail != null && k < avail.length(); k++) if (modes[i].equals(avail.optString(k))) has = true;
        modeButtons[i].setVisibility(has ? View.VISIBLE : View.GONE);
        modeButtons[i].setAlpha(1f);
        boolean active = modes[i].equals(s.state);
        modeButtons[i].setBackground(pressable(active ? cAccent : cBorder, 0));
        modeButtons[i].setTextColor(active ? (dark ? 0xFF0F1115 : 0xFFFFFFFF) : cText);
      }
      JSONArray fans = s.attrs.optJSONArray("fan_modes");
      String fanNow = s.attrs.optString("fan_mode", "");
      String key = fans == null ? "" : fans.toString();
      if (!key.equals(fanKey[0])) {
        fanKey[0] = key;
        fanRow.removeAllViews();
        for (int k = 0; fans != null && k < fans.length(); k++) {
          final String fan = fans.optString(k);
          if ("custom".equals(fan)) continue;
          TextView fb = tv(fanLabel(fan), 19, cText, true);
          fb.setGravity(Gravity.CENTER);
          fb.setTag(fan);
          fb.setOnClickListener(v -> {
            try {
              ha.callService("climate", "set_fan_mode", new JSONObject().put("entity_id", entity).put("fan_mode", fan));
            } catch (Exception ignored) {
              // cannot happen for plain strings
            }
          });
          LinearLayout.LayoutParams bp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
          bp.setMargins(dp(3), 0, dp(3), 0);
          fanRow.addView(fb, bp);
        }
      }
      fanRow.setVisibility(fanRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
      for (int k = 0; k < fanRow.getChildCount(); k++) {
        TextView fb = (TextView) fanRow.getChildAt(k);
        boolean active = fb.getTag().equals(fanNow);
        fb.setBackground(pressable(active ? cAccent : cBorder, 0));
        fb.setTextColor(active ? (dark ? 0xFF0F1115 : 0xFFFFFFFF) : cText);
      }
      step[0] = s.attrs.optDouble("target_temp_step", 1);
      if (step[0] <= 0) step[0] = 1;
      range[0] = s.attrs.optDouble("min_temp", 7);
      range[1] = s.attrs.optDouble("max_temp", 35);
      target[0] = s.attrs.optDouble("temperature", Double.NaN);
      boolean off = "off".equals(s.state);
      temp.setText(Double.isNaN(target[0]) ? "–" : Fmt.num(target[0], step[0] < 1 ? 1 : 0) + " °C");
      temp.setTextColor(off ? cSub : cText);
      minus.setAlpha(1f);
      plus.setAlpha(1f);
    });
    return card;
  }

  // ------------------------------------------------------------------ page: Licht

  private View buildLights(JSONObject page) {
    LinearLayout root = hbox();
    root.setPadding(dp(8), dp(12), dp(8), dp(12));
    JSONArray groups = page.optJSONArray("groups");
    for (int g = 0; groups != null && g < groups.length(); g++) {
      JSONObject group = groups.optJSONObject(g);
      LinearLayout col = vbox();
      TextView title = tv(group.optString("title"), 24, cSub, true);
      title.setPadding(dp(6), 0, 0, dp(10));
      col.addView(title);
      JSONArray items = group.optJSONArray("items");
      LinearLayout row = null;
      for (int i = 0; items != null && i < items.length(); i++) {
        if (i % 2 == 0) {
          row = hbox();
          col.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        JSONObject it = items.optJSONObject(i);
        LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(lightTile(it.optString("entity"), it.optString("name")), p);
      }
      if (items != null && items.length() % 2 == 1) {
        row.addView(new View(this), weighted(4));
      }
      root.addView(col, weighted(8));
    }
    return root;
  }

  /** A horizontal brightness bar: drag to set the level, released value is sent once. */
  private interface OnSet {
    void set(int percent);
  }

  private final class BrightnessBar extends View {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private final OnSet onSet;
    private float fraction;
    private boolean on;
    private boolean dragging;
    private int fillColor;

    void setFill(int color) {
      fillColor = color;
    }

    BrightnessBar(Context context, OnSet onSet) {
      super(context);
      this.onSet = onSet;
    }

    void setLevel(float f, boolean isOn) {
      if (dragging) return;
      fraction = Math.max(0f, Math.min(1f, f));
      on = isOn;
      invalidate();
    }

    @Override
    protected void onDraw(android.graphics.Canvas c) {
      float w = getWidth();
      float h = getHeight();
      float r = dp(10);
      paint.setColor(dark ? 0xFF2F343D : 0xFFE3E7EC);
      rect.set(0, 0, w, h);
      c.drawRoundRect(rect, r, r, paint);
      if (fraction > 0f) {
        paint.setColor(on || dragging ? (fillColor != 0 ? fillColor : (dark ? 0xFFFFC107 : 0xFFF9A825)) : cSub);
        c.save();
        c.clipRect(0, 0, w * fraction, h);
        c.drawRoundRect(rect, r, r, paint);
        c.restore();
        // handle
        paint.setColor(0xFFFFFFFF);
        float x = Math.max(dp(8), Math.min(w - dp(8), w * fraction));
        rect.set(x - dp(2), h * 0.25f, x + dp(2), h * 0.75f);
        c.drawRoundRect(rect, dp(2), dp(2), paint);
        rect.set(0, 0, w, h);
      }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
      switch (e.getActionMasked()) {
        case android.view.MotionEvent.ACTION_DOWN:
          getParent().requestDisallowInterceptTouchEvent(true);
          dragging = true;
          move(e);
          return true;
        case android.view.MotionEvent.ACTION_MOVE:
          move(e);
          return true;
        case android.view.MotionEvent.ACTION_UP:
          move(e);
          dragging = false;
          on = true;
          onSet.set(Math.max(1, Math.round(fraction * 100)));
          return true;
        case android.view.MotionEvent.ACTION_CANCEL:
          dragging = false;
          return true;
        default:
          return true;
      }
    }

    private void move(android.view.MotionEvent e) {
      fraction = Math.max(0.01f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
      invalidate();
    }
  }

  private View lightTile(final String entity, String name) {
    final LinearLayout tile = vbox();
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setPadding(dp(14), dp(10), dp(14), dp(14));
    final TextView label = tv(name, 26, cText, true);
    label.setMaxLines(2);
    final TextView state = tv("", 21, cSub, false);
    tile.addView(label);
    tile.addView(state);
    final BrightnessBar bar = new BrightnessBar(this, percent -> {
      try {
        ha.callService("light", "turn_on", new JSONObject().put("entity_id", entity).put("brightness_pct", percent));
      } catch (Exception ignored) {
        // cannot happen for plain values
      }
    });
    LinearLayout.LayoutParams barParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 0);
    barParams.topMargin = dp(10);
    tile.addView(bar, barParams);
    tile.setBackground(pressable(cCard, cBorder));
    tile.setOnClickListener(v -> {
      try {
        ha.callService("light", "toggle", new JSONObject().put("entity_id", entity));
      } catch (Exception ignored) {
        // cannot happen for plain strings
      }
    });
    bind(entity, () -> {
      HaClient.State s = ha == null ? null : ha.get(entity);
      boolean on = s != null && "on".equals(s.state);
      boolean ok = s != null && s.available();
      boolean dimmable = false;
      JSONArray modes = s == null ? null : s.attrs.optJSONArray("supported_color_modes");
      for (int i = 0; modes != null && i < modes.length(); i++) {
        String m = modes.optString(i);
        if (!"onoff".equals(m) && !"unknown".equals(m)) dimmable = true;
      }
      double b = s == null ? 0 : s.attrs.optDouble("brightness", 0);
      int pct = (int) Math.round(b / 255.0 * 100);
      state.setText(!ok ? "niet beschikbaar" : on ? (dimmable && pct > 0 ? pct + "%" : "Aan") : "Uit");
      tile.setBackground(pressable(on ? cOnBg : cCard, cBorder));
      label.setTextColor(on ? cOnText : cText);
      state.setTextColor(on ? cOnText : cSub);
      tile.setAlpha(ok ? 1f : 0.45f);
      bar.setVisibility(dimmable && ok ? View.VISIBLE : View.GONE);
      bar.setLevel(on ? (float) (b / 255.0) : 0f, on);
    });
    return tile;
  }

  // ------------------------------------------------------------------ page: Muziek (Sonos)

  private final Runnable musicTick = new Runnable() {
    @Override
    public void run() {
      updateProgress();
      if (currentPage < pageTypes.size() && "music".equals(pageTypes.get(currentPage))) handler.postDelayed(this, 1000);
    }
  };

  /** The album's own colour when there is art, otherwise the theme accent. */
  private int accent() {
    return musicTint != 0 ? musicTint : cAccent;
  }

  private int onAccent() {
    return dark ? 0xFF0F1115 : 0xFFFFFFFF;
  }

  private static int mix(int a, int b, float t) {
    int r = Math.round(Color.red(a) * t + Color.red(b) * (1 - t));
    int g = Math.round(Color.green(a) * t + Color.green(b) * (1 - t));
    int bl = Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t));
    return Color.rgb(r, g, bl);
  }

  /** The average colour of the art, made fuller and lighter (dark theme) or deeper (light theme) to use as an accent. */
  private int tintOf(Bitmap bitmap) {
    Bitmap one = Bitmap.createScaledBitmap(bitmap, 1, 1, true);
    int c = one.getPixel(0, 0);
    if (one != bitmap) one.recycle();
    float[] hsv = new float[3];
    Color.colorToHSV(c, hsv);
    hsv[1] = Math.max(0.35f, Math.min(0.8f, hsv[1] * 1.15f));
    hsv[2] = dark ? 0.88f : 0.62f;
    return Color.HSVToColor(hsv);
  }

  private StateListDrawable pill(int fill, int stroke, float radiusDp) {
    StateListDrawable st = new StateListDrawable();
    st.addState(new int[] {android.R.attr.state_pressed}, rounded(cPress, stroke, radiusDp));
    st.addState(new int[] {}, rounded(fill, stroke, radiusDp));
    return st;
  }

  private View buildMusic(JSONObject page) {
    musicPlayers.clear();
    musicTileName.clear();
    musicTileState.clear();
    musicTiles.clear();
    presetViews.clear();
    presetNames.clear();
    presetKey = "";
    artPath = "";
    JSONArray players = page.optJSONArray("players");
    musicPlaylists = page.optJSONArray("playlists");
    LinearLayout root = hbox();
    root.setPadding(dp(12), dp(12), dp(12), dp(12));

    // Speakers
    LinearLayout left = vbox();
    TextView speakersTitle = tv("SPELERS", 16, cSub, true);
    speakersTitle.setLetterSpacing(0.14f);
    speakersTitle.setPadding(dp(8), dp(4), 0, dp(10));
    left.addView(speakersTitle);
    for (int i = 0; players != null && i < players.length(); i++) {
      JSONObject def = players.optJSONObject(i);
      final String id = def.optString("entity");
      musicPlayers.add(id);
      LinearLayout tile = vbox();
      tile.setGravity(Gravity.CENTER_VERTICAL);
      tile.setPadding(dp(16), dp(8), dp(12), dp(8));
      TextView name = tv(def.optString("name"), 24, cText, true);
      TextView state = tv("", 18, cSub, false);
      tile.addView(name);
      tile.addView(state);
      tile.setOnClickListener(v -> selectPlayer(id));
      musicTileName.add(name);
      musicTileState.add(state);
      musicTiles.add(tile);
      LinearLayout.LayoutParams tp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(88), 0);
      tp.bottomMargin = dp(10);
      left.addView(tile, tp);
    }
    LinearLayout.LayoutParams leftParams = lp(dp(230), ViewGroup.LayoutParams.MATCH_PARENT, 0);
    leftParams.setMargins(dp(4), 0, dp(10), 0);
    root.addView(left, leftParams);

    if (musicSelected == null || !musicPlayers.contains(musicSelected)) {
      musicSelected = prefs().getString("music_player", musicPlayers.isEmpty() ? null : musicPlayers.get(0));
      if (musicSelected == null || !musicPlayers.contains(musicSelected)) {
        musicSelected = musicPlayers.isEmpty() ? null : musicPlayers.get(0);
      }
    }

    // Now playing
    musicCenter = vbox();
    musicCenter.setPadding(dp(24), dp(22), dp(24), dp(18));
    LinearLayout top = hbox();
    FrameLayout artBox = new FrameLayout(this);
    artBox.setBackground(rounded(dark ? 0xFF262A31 : 0xFFE3E7EC, 0, 18));
    artBox.setElevation(dp(10));
    artBox.setOutlineProvider(new ViewOutlineProvider() {
      @Override
      public void getOutline(View view, android.graphics.Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
      }
    });
    artBox.setClipToOutline(true);
    musicArtNote = new MediaIcon(this, MediaIcon.NOTE, cSub);
    artBox.addView(musicArtNote, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
    musicArt = new ImageView(this);
    musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
    artBox.addView(musicArt, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    LinearLayout.LayoutParams artParams = lp(dp(310), dp(310), 0);
    artParams.setMargins(dp(4), dp(4), dp(8), dp(10));
    top.addView(artBox, artParams);

    LinearLayout info = vbox();
    info.setPadding(dp(20), dp(4), 0, dp(10));
    musicTitle = tv("", 34, cText, true);
    musicTitle.setMaxLines(3);
    musicTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    musicArtist = tv("", 26, cText, false);
    musicArtist.setMaxLines(1);
    musicArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
    musicAlbum = tv("", 21, cSub, false);
    musicAlbum.setMaxLines(1);
    musicAlbum.setEllipsize(android.text.TextUtils.TruncateAt.END);
    info.addView(musicTitle);
    LinearLayout.LayoutParams artistParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    artistParams.topMargin = dp(6);
    info.addView(musicArtist, artistParams);
    info.addView(musicAlbum);
    info.addView(new View(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout track = hbox();
    track.setBackground(rounded(dark ? 0x33FFFFFF : 0x22000000, 0, 3));
    progressFill = new View(this);
    progressRest = new View(this);
    track.addView(progressFill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
    track.addView(progressRest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    info.addView(track, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(6), 0));
    LinearLayout times = hbox();
    musicTime = tv("", 17, cSub, false);
    musicDuration = tv("", 17, cSub, false);
    musicDuration.setGravity(Gravity.RIGHT);
    times.addView(musicTime, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    times.addView(musicDuration, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    LinearLayout.LayoutParams timesParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    timesParams.topMargin = dp(8);
    info.addView(times, timesParams);
    top.addView(info, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    musicCenter.addView(top);

    // Transport
    LinearLayout transport = hbox();
    transport.setGravity(Gravity.CENTER);
    musicShuffle = new MediaIcon(this, MediaIcon.SHUFFLE, cSub);
    MediaIcon prev = new MediaIcon(this, MediaIcon.PREV, cText);
    musicPlay = new MediaIcon(this, MediaIcon.PLAY, onAccent());
    MediaIcon next = new MediaIcon(this, MediaIcon.NEXT, cText);
    musicRepeat = new MediaIcon(this, MediaIcon.REPEAT, cSub);
    musicShuffle.setOnClickListener(v -> {
      HaClient.State st = ha.get(musicSelected);
      boolean on = st != null && st.attrs.optBoolean("shuffle", false);
      musicCall("shuffle_set", "shuffle", !on);
    });
    musicRepeat.setOnClickListener(v -> {
      HaClient.State st = ha.get(musicSelected);
      String cur = st == null ? "off" : st.attrs.optString("repeat", "off");
      musicCall("repeat_set", "repeat", "off".equals(cur) ? "all" : "all".equals(cur) ? "one" : "off");
    });
    prev.setOnClickListener(v -> musicCall("media_previous_track", null, null));
    next.setOnClickListener(v -> musicCall("media_next_track", null, null));
    musicPlay.setOnClickListener(v -> musicCall("media_play_pause", null, null));
    for (View v : new View[] {prev, next}) {
      v.setPadding(dp(9), dp(9), dp(9), dp(9));
      v.setBackground(circle(dark ? 0xFF2A2F37 : 0xFFE9EDF1, 0));
    }
    for (View v : new View[] {musicShuffle, musicRepeat}) v.setPadding(dp(6), dp(6), dp(6), dp(6));
    musicPlay.setPadding(dp(20), dp(20), dp(20), dp(20));
    transport.addView(musicShuffle, lp(dp(50), dp(50), 0));
    transport.addView(prev, lpm(dp(66), dp(66), 22));
    transport.addView(musicPlay, lpm(dp(98), dp(98), 8));
    transport.addView(next, lpm(dp(66), dp(66), 22));
    transport.addView(musicRepeat, lp(dp(50), dp(50), 0));
    musicCenter.addView(new View(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    musicCenter.addView(transport, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

    // Volume
    LinearLayout volume = hbox();
    volume.setGravity(Gravity.CENTER_VERTICAL);
    musicMute = new MediaIcon(this, MediaIcon.VOLUME, cSub);
    musicMute.setOnClickListener(v -> {
      HaClient.State st = ha.get(musicSelected);
      boolean muted = st != null && st.attrs.optBoolean("is_volume_muted", false);
      musicCall("volume_mute", "is_volume_muted", !muted);
    });
    musicVolume = new BrightnessBar(this, percent -> musicCall("volume_set", "volume_level", percent / 100.0));
    musicVolText = tv("", 20, cSub, true);
    musicVolText.setGravity(Gravity.CENTER);
    volume.addView(musicMute, lp(dp(56), dp(56), 0));
    LinearLayout.LayoutParams barParams = lp(0, dp(40), 1);
    barParams.setMargins(dp(10), 0, dp(10), 0);
    volume.addView(musicVolume, barParams);
    volume.addView(musicVolText, lp(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT, 0));
    musicCenter.addView(new View(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    musicCenter.addView(volume, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
    LinearLayout.LayoutParams centerParams = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    centerParams.setMargins(0, 0, dp(10), 0);
    root.addView(musicCenter, centerParams);

    // Presets (the Sonos favourites show up as the source list)
    LinearLayout presets = vbox();
    presets.setBackground(rounded(cCard, cBorder, 16));
    presets.setPadding(dp(16), dp(16), dp(16), dp(10));
    TextView presetsTitle = tv(page.optString("list_title", musicPlaylists != null ? "PLAYLISTS" : "FAVORIETEN"), 16, cSub, true);
    presetsTitle.setLetterSpacing(0.14f);
    presetsTitle.setPadding(dp(6), 0, 0, dp(10));
    presets.addView(presetsTitle);

    // Search field: type, press Search on the keyboard, and the results replace the playlists.
    LinearLayout searchRow = hbox();
    searchRow.setGravity(Gravity.CENTER_VERTICAL);
    searchInput = new EditText(this);
    searchInput.setHint("Zoek in Spotify…");
    searchInput.setHintTextColor(cSub);
    searchInput.setTextColor(cText);
    searchInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 23);
    searchInput.setSingleLine(true);
    searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    searchInput.setBackground(rounded(dark ? 0xFF262A31 : 0xFFEEF1F4, 0, 26));
    searchInput.setPadding(dp(20), 0, dp(16), 0);
    searchInput.setOnEditorActionListener((v, actionId, event) -> {
      boolean enterKey = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
          && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
      if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enterKey) {
        runSearch();
        return true;
      }
      return false;
    });
    searchRow.addView(searchInput, lp(0, dp(60), 1));
    searchClear = tv("✕", 22, cSub, true);
    searchClear.setGravity(Gravity.CENTER);
    searchClear.setBackground(pill(dark ? 0xFF262A31 : 0xFFEEF1F4, 0, 26));
    searchClear.setVisibility(View.GONE);
    searchClear.setOnClickListener(v -> leaveSearch());
    LinearLayout.LayoutParams clearParams = lp(dp(60), dp(60), 0);
    clearParams.leftMargin = dp(8);
    searchRow.addView(searchClear, clearParams);
    LinearLayout.LayoutParams searchParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    searchParams.bottomMargin = dp(10);
    presets.addView(searchRow, searchParams);
    searchMode = false;

    presetList = vbox();
    ScrollView presetScroll = new ScrollView(this);
    presetScroll.setVerticalScrollBarEnabled(false);
    presetScroll.addView(presetList, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    presets.addView(presetScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    root.addView(presets, lp(dp(400), ViewGroup.LayoutParams.MATCH_PARENT, 0));

    for (String id : musicPlayers) bind(id, this::refreshMusic);
    return root;
  }

  /** A round fill, or a round ring when only a stroke colour is given. */
  private GradientDrawable circle(int fill, int ring) {
    GradientDrawable g = new GradientDrawable();
    g.setShape(GradientDrawable.OVAL);
    g.setColor(fill);
    if (ring != 0) g.setStroke(dp(3), ring);
    return g;
  }

  private LinearLayout.LayoutParams lpm(int w, int h, int margin) {
    LinearLayout.LayoutParams p = lp(w, h, 0);
    p.setMargins(dp(margin), 0, dp(margin), 0);
    return p;
  }

  /**
   * Plays a Spotify playlist on the selected Sonos speaker. Home Assistant's Sonos integration takes a
   * Spotify share link (it must be linked to Spotify in the Sonos app); "spotify:playlist:ID" becomes
   * https://open.spotify.com/playlist/ID.
   */
  private void playPlaylist(String name, String uri) {
    playUri(name, uri, false);
  }

  private void playUri(String name, String uri, boolean addToQueue) {
    if (musicSelected == null) return;
    String link = uri.startsWith("spotify:") ? "https://open.spotify.com/" + uri.substring(8).replace(':', '/') : uri;
    if (!addToQueue && uri.contains(":playlist:")) lastPlaylist.put(musicSelected, name);
    try {
      JSONObject data = new JSONObject().put("entity_id", musicSelected)
          .put("media_content_type", "music").put("media_content_id", link);
      if (addToQueue) data.put("enqueue", "add");
      ha.callService("media_player", "play_media", data);
    } catch (Exception ignored) {
      // plain strings only
    }
    refreshMusic();
  }

  private void hideKeyboard() {
    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    View focus = getCurrentFocus();
    if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    if (focus != null) focus.clearFocus();
  }

  private TextView searchNote(String text) {
    TextView t = tv(text, 20, cSub, false);
    t.setPadding(dp(8), dp(10), dp(8), dp(10));
    return t;
  }

  private void runSearch() {
    final String q = searchInput.getText().toString().trim();
    hideKeyboard();
    if (q.length() < 2) return;
    searchMode = true;
    searchClear.setVisibility(View.VISIBLE);
    presetList.removeAllViews();
    presetViews.clear();
    presetNames.clear();
    presetKey = "";
    if (!spotify.configured()) {
      presetList.addView(searchNote("Zoeken in Spotify is nog niet ingesteld. De uitleg staat in panel/README.md (Spotify zoeken)."));
      return;
    }
    presetList.addView(searchNote("Zoeken naar “" + q + "”…"));
    spotify.search(q, (items, error) -> {
      if (!searchMode) return;
      presetList.removeAllViews();
      if (error != null) {
        presetList.addView(searchNote("Zoeken lukte niet: " + error));
        return;
      }
      if (items.isEmpty()) {
        presetList.addView(searchNote("Niets gevonden voor “" + q + "”."));
        return;
      }
      for (SpotifyClient.Item it : items) presetList.addView(resultRow(it));
    });
  }

  private View resultRow(final SpotifyClient.Item it) {
    LinearLayout row = hbox();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackground(pill(dark ? 0xFF262A31 : 0xFFEEF1F4, 0, 18));
    row.setPadding(dp(18), dp(6), dp(8), dp(6));
    LinearLayout text = vbox();
    TextView name = tv(it.name, 23, cText, true);
    name.setMaxLines(1);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    TextView sub = tv(it.sub, 17, cSub, false);
    sub.setMaxLines(1);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    text.addView(name);
    text.addView(sub);
    LinearLayout.LayoutParams textParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    textParams.rightMargin = dp(10);
    row.addView(text, textParams);
    TextView queue = tv("+", 30, accent(), true);
    queue.setGravity(Gravity.CENTER);
    queue.setBackground(pill(dark ? 0xFF1B1E24 : 0xFFFFFFFF, 0, 22));
    queue.setOnClickListener(v -> playUri(it.name, it.uri, true));
    row.addView(queue, lp(dp(48), dp(48), 0));
    row.setOnClickListener(v -> playUri(it.name, it.uri, false));
    LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(76), 0);
    rp.bottomMargin = dp(8);
    row.setLayoutParams(rp);
    return row;
  }

  private void leaveSearch() {
    searchMode = false;
    searchInput.setText("");
    hideKeyboard();
    searchClear.setVisibility(View.GONE);
    presetKey = "";
    refreshMusic();
  }

  private void selectPlayer(String id) {
    musicSelected = id;
    prefs().edit().putString("music_player", id).apply();
    artPath = "";
    presetKey = "";
    refreshMusic();
  }

  private void musicCall(String service, String key, Object value) {
    if (musicSelected == null) return;
    try {
      JSONObject data = new JSONObject().put("entity_id", musicSelected);
      if (key != null) data.put(key, value);
      ha.callService("media_player", service, data);
    } catch (Exception ignored) {
      // plain values only
    }
  }

  private static String playerState(HaClient.State st) {
    if (st == null) return "–";
    switch (st.state) {
      case "playing": return "Speelt";
      case "paused": return "Gepauzeerd";
      case "idle": return "Stil";
      case "off": return "Uit";
      case "standby": return "Standby";
      case "unavailable": return "Niet beschikbaar";
      default: return st.state;
    }
  }

  private void refreshMusic() {
    if (musicTitle == null || ha == null) return;
    int accent = accent();
    for (int i = 0; i < musicPlayers.size(); i++) {
      HaClient.State st = ha.get(musicPlayers.get(i));
      boolean selected = musicPlayers.get(i).equals(musicSelected);
      JSONArray group = st == null ? null : st.attrs.optJSONArray("group_members");
      boolean playing = st != null && "playing".equals(st.state);
      musicTileState.get(i).setText((playing ? "● " : "") + playerState(st) + (group != null && group.length() > 1 ? "  ·  groep" : ""));
      musicTileState.get(i).setTextColor(playing ? accent : cSub);
      musicTileName.get(i).setTextColor(selected ? cText : cSub);
      musicTiles.get(i).setBackground(pill(selected ? mix(accent, cCard, dark ? 0.22f : 0.18f) : cCard,
          selected ? mix(accent, cCard, 0.6f) : cBorder, 16));
      musicTiles.get(i).setAlpha(st != null && st.available() ? 1f : 0.5f);
    }
    // The now-playing panel takes a soft wash of the album colour.
    GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        new int[] {mix(accent, cCard, dark ? 0.26f : 0.16f), cCard});
    wash.setCornerRadius(dp(18));
    wash.setStroke(dp(1), cBorder);
    musicCenter.setBackground(wash);

    HaClient.State s = musicSelected == null ? null : ha.get(musicSelected);
    boolean ok = s != null && s.available() && !"off".equals(s.state);
    String title = ok ? s.attrs.optString("media_title", "") : "";
    musicTitle.setText(!title.isEmpty() ? title : ok ? "Niets aan het afspelen" : s == null ? "" : playerState(s));
    musicArtist.setText(ok ? s.attrs.optString("media_artist", "") : "");
    musicArtist.setTextColor(mix(accent, cText, 0.55f));
    musicAlbum.setText(ok ? s.attrs.optString("media_album_name", "") : "");
    boolean playing = ok && "playing".equals(s.state);
    android.graphics.drawable.LayerDrawable playBg = new android.graphics.drawable.LayerDrawable(
        new android.graphics.drawable.Drawable[] {circle(0, (accent & 0x00FFFFFF) | 0x66000000), circle(accent, 0)});
    playBg.setLayerInset(0, 0, 0, 0, 0);
    playBg.setLayerInset(1, dp(7), dp(7), dp(7), dp(7));
    musicPlay.setBackground(playBg);
    musicPlay.set(playing ? MediaIcon.PAUSE : MediaIcon.PLAY, onAccent());
    boolean shuffleOn = ok && s.attrs.optBoolean("shuffle", false);
    boolean repeatOn = ok && !"off".equals(s.attrs.optString("repeat", "off"));
    musicShuffle.set(MediaIcon.SHUFFLE, shuffleOn ? accent : cSub);
    musicRepeat.set(MediaIcon.REPEAT, repeatOn ? accent : cSub);
    musicShuffle.setBackground(circle(shuffleOn ? (accent & 0x00FFFFFF) | 0x33000000 : 0, 0));
    musicRepeat.setBackground(circle(repeatOn ? (accent & 0x00FFFFFF) | 0x33000000 : 0, 0));
    double vol = ok ? s.attrs.optDouble("volume_level", -1) : -1;
    musicVolume.setFill(accent);
    musicVolume.setLevel(vol < 0 ? 0f : (float) vol, true);
    musicVolText.setText(vol < 0 ? "–" : Math.round(vol * 100) + "%");
    boolean muted = ok && s.attrs.optBoolean("is_volume_muted", false);
    musicMute.set(muted ? MediaIcon.MUTE : MediaIcon.VOLUME, muted ? accent : cSub);
    progressFill.setBackground(rounded(accent, 0, 3));
    for (View v : new View[] {musicPlay, musicShuffle, musicRepeat, musicMute, musicVolume}) v.setAlpha(ok ? 1f : 0.35f);

    String path = ok ? s.attrs.optString("entity_picture", "") : "";
    if (!path.equals(artPath)) {
      artPath = path;
      loadArt(path);
    }

    if (searchMode) {
      updateProgress();
      return;
    }
    // Either the Spotify playlists from panel.json (played through Sonos) or the player's own source list.
    final boolean playlistMode = musicPlaylists != null && musicPlaylists.length() > 0;
    JSONArray sources = ok ? s.attrs.optJSONArray("source_list") : null;
    final List<String> names = new ArrayList<>();
    final List<String> ids = new ArrayList<>();
    if (playlistMode) {
      for (int i = 0; i < musicPlaylists.length(); i++) {
        JSONObject pl = musicPlaylists.optJSONObject(i);
        if (pl == null) continue;
        names.add(pl.optString("name"));
        ids.add(pl.optString("uri"));
      }
    } else {
      for (int i = 0; sources != null && i < sources.length(); i++) {
        names.add(sources.optString(i));
        ids.add(sources.optString(i));
      }
    }
    StringBuilder key = new StringBuilder(playlistMode ? "pl:" : "src:");
    for (String n : names) key.append(n).append('\n');
    if (!key.toString().equals(presetKey)) {
      presetKey = key.toString();
      presetList.removeAllViews();
      presetViews.clear();
      presetNames.clear();
      for (int i = 0; i < names.size(); i++) {
        final String name = names.get(i);
        final String id = ids.get(i);
        TextView b = tv(name, 24, cText, false);
        b.setPadding(dp(22), 0, dp(16), 0);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMaxLines(1);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setOnClickListener(v -> {
          if (playlistMode) playPlaylist(name, id);
          else musicCall("select_source", "source", name);
        });
        LinearLayout.LayoutParams bp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 0);
        bp.bottomMargin = dp(8);
        presetList.addView(b, bp);
        presetViews.add(b);
        presetNames.add(name);
      }
    }
    String current = "";
    if (ok) {
      if (playlistMode) {
        current = s.attrs.optString("media_playlist", "");
        String pressed = lastPlaylist.get(musicSelected);
        if (current.isEmpty() && pressed != null && ("playing".equals(s.state) || "paused".equals(s.state))) current = pressed;
      } else {
        current = s.attrs.optString("source", "");
      }
    }
    for (int i = 0; i < presetViews.size(); i++) {
      boolean on = !current.isEmpty() && presetNames.get(i).equalsIgnoreCase(current);
      presetViews.get(i).setBackground(pill(on ? accent : (dark ? 0xFF262A31 : 0xFFEEF1F4), 0, 26));
      presetViews.get(i).setTextColor(on ? onAccent() : cText);
      presetViews.get(i).setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }
    updateProgress();
  }

  private void loadArt(final String path) {
    if (path.isEmpty()) {
      musicArt.setImageDrawable(null);
      musicArtNote.setVisibility(View.VISIBLE);
      setTint(0);
      return;
    }
    Bitmap cached = artCache.get(path);
    if (cached != null) {
      musicArt.setImageBitmap(cached);
      musicArtNote.setVisibility(View.GONE);
      Integer t = artTints.get(path);
      setTint(t == null ? 0 : t);
      return;
    }
    musicArt.setImageDrawable(null);
    musicArtNote.setVisibility(View.VISIBLE);
    ha.fetchBitmap(path, 640, bmp -> {
      if (bmp == null || !path.equals(artPath)) return;
      artCache.put(path, bmp);
      int tint = tintOf(bmp);
      artTints.put(path, tint);
      musicArt.setImageBitmap(bmp);
      musicArtNote.setVisibility(View.GONE);
      setTint(tint);
    });
  }

  private void setTint(int tint) {
    if (tint == musicTint) return;
    musicTint = tint;
    refreshMusic();
  }

  private static String clock(double seconds) {
    long t = Math.max(0, Math.round(seconds));
    return (t / 60) + ":" + Fmt.two((int) (t % 60));
  }

  private void updateProgress() {
    if (progressFill == null || ha == null) return;
    HaClient.State s = musicSelected == null ? null : ha.get(musicSelected);
    double duration = s == null ? -1 : s.attrs.optDouble("media_duration", -1);
    double position = s == null ? -1 : s.attrs.optDouble("media_position", -1);
    if (s != null && position >= 0 && "playing".equals(s.state)) {
      long updated = Fmt.parseDateTime(s.attrs.optString("media_position_updated_at"));
      if (updated > 0) position += (System.currentTimeMillis() - updated) / 1000.0;
    }
    if (duration > 0 && position > duration) position = duration;
    float frac = duration > 0 && position >= 0 ? (float) (position / duration) : 0f;
    ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = frac;
    ((LinearLayout.LayoutParams) progressRest.getLayoutParams()).weight = 1f - frac;
    ((View) progressFill.getParent()).requestLayout();
    musicTime.setText(duration > 0 && position >= 0 ? clock(position) : "");
    musicDuration.setText(duration > 0 ? clock(duration) : "");
  }

  // ------------------------------------------------------------------ page: Boodschappen

  private View buildShopping(JSONObject page) {
    shopEntity = page.optString("list");
    LinearLayout root = hbox();
    root.setPadding(dp(12), dp(12), dp(12), dp(12));

    // To buy
    LinearLayout left = card();
    LinearLayout header = hbox();
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = tv(page.optString("list_title", "TE KOPEN"), 16, cSub, true);
    title.setLetterSpacing(0.14f);
    shopCount = tv("", 18, cSub, false);
    header.addView(title, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    header.addView(shopCount);
    left.addView(header);

    LinearLayout addRow = hbox();
    addRow.setGravity(Gravity.CENTER_VERTICAL);
    shopInput = new EditText(this);
    shopInput.setHint("Wat moet er op het lijstje?");
    shopInput.setHintTextColor(cSub);
    shopInput.setTextColor(cText);
    shopInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
    shopInput.setSingleLine(true);
    shopInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
    shopInput.setBackground(rounded(dark ? 0xFF262A31 : 0xFFEEF1F4, 0, 28));
    shopInput.setPadding(dp(22), 0, dp(16), 0);
    shopInput.setOnEditorActionListener((v, actionId, event) -> {
      boolean enterKey = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
          && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
      if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH || enterKey) {
        addShoppingItem();
        return true;
      }
      return false;
    });
    TextView add = tv("Toevoegen", 22, onAccent(), true);
    add.setGravity(Gravity.CENTER);
    add.setBackground(rounded(cAccent, 0, 28));
    add.setOnClickListener(v -> addShoppingItem());
    addRow.addView(shopInput, lp(0, dp(68), 1));
    LinearLayout.LayoutParams addParams = lp(dp(170), dp(68), 0);
    addParams.leftMargin = dp(10);
    addRow.addView(add, addParams);
    LinearLayout.LayoutParams addRowParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    addRowParams.topMargin = dp(12);
    addRowParams.bottomMargin = dp(12);
    left.addView(addRow, addRowParams);

    shopToBuy = vbox();
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(shopToBuy, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    left.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    LinearLayout.LayoutParams leftParams = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 2);
    leftParams.rightMargin = dp(10);
    root.addView(left, leftParams);

    // Recently bought: tap to put it back on the list
    LinearLayout right = card();
    TextView recentTitle = tv("RECENT GEKOCHT", 16, cSub, true);
    recentTitle.setLetterSpacing(0.14f);
    right.addView(recentTitle);
    TextView hint = tv("Tik om weer op het lijstje te zetten", 17, cSub, false);
    hint.setPadding(0, dp(4), 0, dp(10));
    right.addView(hint);
    shopRecent = vbox();
    ScrollView recentScroll = new ScrollView(this);
    recentScroll.setVerticalScrollBarEnabled(false);
    recentScroll.addView(shopRecent, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    right.addView(recentScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    root.addView(right, lp(dp(360), ViewGroup.LayoutParams.MATCH_PARENT, 0));

    if (!shopEntity.isEmpty()) bind(shopEntity, this::refreshShopping);
    handler.postDelayed(shopTick, 60000);
    return root;
  }

  private final Runnable shopTick = new Runnable() {
    @Override
    public void run() {
      if (currentPage < pageTypes.size() && "shopping".equals(pageTypes.get(currentPage))) refreshShopping();
      handler.postDelayed(this, 60000);
    }
  };

  private void refreshShopping() {
    if (shopToBuy == null || ha == null || shopEntity == null || shopEntity.isEmpty()) return;
    final int generation = ++shopGeneration;
    String body;
    try {
      body = new JSONObject().put("entity_id", shopEntity)
          .put("status", new JSONArray().put("needs_action").put("completed")).toString();
    } catch (Exception e) {
      return;
    }
    ha.request("POST", "/api/services/todo/get_items?return_response", body, (json, array) -> {
      if (generation != shopGeneration) return;
      JSONObject resp = json == null ? null : json.optJSONObject("service_response");
      JSONObject entity = resp == null ? null : resp.optJSONObject(shopEntity);
      JSONArray items = entity == null ? null : entity.optJSONArray("items");
      if (items == null) {
        shopToBuy.removeAllViews();
        shopToBuy.addView(searchNote(json == null ? "De lijst kon niet worden opgehaald." : "Geen lijst gevonden: " + shopEntity));
        shopCount.setText("");
        return;
      }
      renderShopping(items);
    });
  }

  private JSONObject bringNames;

  /** Bring sends catalogue items under their German key (Buttermilch); show the Dutch name. */
  private String bringName(String key) {
    if (bringNames == null) {
      try (InputStream in = getAssets().open("bring_nl.json")) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        bringNames = new JSONObject(out.toString("UTF-8"));
      } catch (Exception e) {
        bringNames = new JSONObject();
      }
    }
    return bringNames.optString(key, key);
  }

  private void renderShopping(JSONArray items) {
    shopToBuy.removeAllViews();
    shopRecent.removeAllViews();
    final List<JSONObject> open = new ArrayList<>();
    final List<JSONObject> done = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject it = items.optJSONObject(i);
      if (it == null) continue;
      if ("completed".equals(it.optString("status"))) done.add(it);
      else open.add(it);
    }
    shopCount.setText(open.size() == 1 ? "1 artikel" : open.size() + " artikelen");
    if (open.isEmpty()) {
      shopToBuy.addView(searchNote("Het lijstje is leeg."));
    }
    // Two columns of big rows, filled left to right.
    LinearLayout row = null;
    for (int i = 0; i < open.size(); i++) {
      if (i % 2 == 0) {
        row = hbox();
        shopToBuy.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
      }
      LinearLayout.LayoutParams cp = lp(0, dp(92), 1);
      cp.setMargins(dp(i % 2 == 0 ? 0 : 6), dp(3), dp(i % 2 == 0 ? 6 : 0), dp(3));
      row.addView(shoppingRow(open.get(i)), cp);
    }
    if (open.size() % 2 == 1 && row != null) row.addView(new View(this), lp(0, dp(92), 1));
    for (JSONObject it : done) {
      final String uid = it.optString("uid", it.optString("summary"));
      final String name = bringName(it.optString("summary"));
      TextView chip = tv(name, 24, cText, false);
      chip.setPadding(dp(18), 0, dp(14), 0);
      chip.setGravity(Gravity.CENTER_VERTICAL);
      chip.setMaxLines(1);
      chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
      chip.setBackground(pill(dark ? 0xFF262A31 : 0xFFEEF1F4, 0, 24));
      chip.setOnClickListener(v -> shoppingCall("update_item", uid, "needs_action"));
      LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 0);
      p.bottomMargin = dp(8);
      shopRecent.addView(chip, p);
    }
  }

  private View shoppingRow(final JSONObject item) {
    final String uid = item.optString("uid", item.optString("summary"));
    final String name = bringName(item.optString("summary"));
    LinearLayout row = hbox();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackground(pill(dark ? 0xFF262A31 : 0xFFEEF1F4, 0, 18));
    row.setPadding(dp(14), dp(4), dp(10), dp(4));
    View box = new View(this);
    GradientDrawable ring = new GradientDrawable();
    ring.setShape(GradientDrawable.OVAL);
    ring.setColor(Color.TRANSPARENT);
    ring.setStroke(dp(3), accent());
    box.setBackground(ring);
    row.addView(box, lp(dp(40), dp(40), 0));
    LinearLayout text = vbox();
    text.setPadding(dp(16), 0, 0, 0);
    TextView title = tv(name, 28, cText, true);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    text.addView(title);
    String note = item.optString("description", "");
    if (!note.isEmpty() && !"null".equals(note)) {
      TextView sub = tv(note, 16, cSub, false);
      sub.setMaxLines(1);
      sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      text.addView(sub);
    }
    row.addView(text, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    // Tap: bought. It moves to the recent list on the right.
    row.setOnClickListener(v -> {
      row.setVisibility(View.INVISIBLE);
      shoppingCall("update_item", uid, "completed");
    });
    return row;
  }

  private void shoppingCall(String service, String item, String status) {
    try {
      JSONObject data = new JSONObject().put("entity_id", shopEntity).put("item", item);
      if (status != null) data.put("status", status);
      ha.callService("todo", service, data);
    } catch (Exception ignored) {
      // plain strings only
    }
    handler.postDelayed(this::refreshShopping, 900);
  }

  private void addShoppingItem() {
    String text = shopInput.getText().toString().trim();
    hideKeyboard();
    if (text.isEmpty()) return;
    shopInput.setText("");
    try {
      ha.callService("todo", "add_item", new JSONObject().put("entity_id", shopEntity).put("item", text));
    } catch (Exception ignored) {
      // plain strings only
    }
    handler.postDelayed(this::refreshShopping, 900);
  }

  // ------------------------------------------------------------------ page: cards (Energie)

  private View buildCards(JSONObject page) {
    LinearLayout outer = vbox();
    outer.setPadding(dp(8), dp(12), dp(8), dp(12));
    LinearLayout root = hbox();
    int[] accents = dark
        ? new int[] {0xFF4FC3F7, 0xFFFFC107, 0xFF81C784, 0xFFBA68C8}
        : new int[] {0xFF0288D1, 0xFFF9A825, 0xFF2E9E5B, 0xFF8E4EC6};
    JSONArray cards = page.optJSONArray("cards");
    for (int c = 0; cards != null && c < cards.length(); c++) {
      JSONObject def = cards.optJSONObject(c);
      int accent = accents[c % accents.length];
      LinearLayout card = card();
      card.setPadding(dp(22), dp(16), dp(22), dp(10));

      LinearLayout head = hbox();
      head.setGravity(Gravity.CENTER_VERTICAL);
      View dot = new View(this);
      GradientDrawable d = new GradientDrawable();
      d.setShape(GradientDrawable.OVAL);
      d.setColor(accent);
      dot.setBackground(d);
      head.addView(dot, lp(dp(12), dp(12), 0));
      TextView title = tv(def.optString("title").toUpperCase(java.util.Locale.ROOT), 16, cSub, true);
      title.setLetterSpacing(0.14f);
      title.setPadding(dp(10), 0, 0, 0);
      head.addView(title);
      card.addView(head);

      JSONObject main = def.optJSONObject("main");
      if (main != null) {
        final String entity = main.optString("entity");
        final int digits = main.optInt("digits", 0);
        final String unit = main.optString("unit");
        final TextView big = tv("–", 72, accent, true);
        big.setPadding(0, dp(8), 0, dp(8));
        card.addView(big);
        bind(entity, () -> big.setText(bigValue(entity, digits, unit)));
      }
      JSONArray lines = def.optJSONArray("lines");
      for (int i = 0; lines != null && i < lines.length(); i++) {
        JSONObject l = lines.optJSONObject(i);
        final String entity = l.optString("entity");
        final int digits = l.optInt("digits", 0);
        final String unit = l.optString("unit");
        View rule = new View(this);
        rule.setBackgroundColor(cBorder);
        LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0);
        rp.topMargin = dp(main != null || i > 0 ? 6 : 16);
        card.addView(rule, rp);
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv(l.optString("label"), 24, cSub, false), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView v = tv("–", main != null ? 28 : 40, cText, true);
        row.addView(v);
        bind(entity, () -> v.setText(bigValue(entity, digits, unit)));
        card.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(main != null ? 50 : 84), 0));
      }
      LinearLayout.LayoutParams cp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
      cp.setMargins(dp(8), 0, dp(8), 0);
      root.addView(card, cp);
    }
    outer.addView(root, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.17f));
    JSONObject graph = page.optJSONObject("graph");
    if (graph != null) {
      outer.addView(buildGraph(graph, accents), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }
    return outer;
  }

  // The power graph under the Energie cards: long-term statistics (5 minute means) over the last 6 or 24 hours.
  private PowerGraph graphView;
  private JSONArray graphSeries;
  private long graphFetchedAt;

  private View buildGraph(JSONObject def, int[] accents) {
    graphSeries = def.optJSONArray("series");
    LinearLayout card = card();
    card.setPadding(dp(22), dp(14), dp(22), dp(10));
    LinearLayout head = hbox();
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = tv(def.optString("title", "VERMOGEN").toUpperCase(java.util.Locale.ROOT), 16, cSub, true);
    title.setLetterSpacing(0.14f);
    head.addView(title);
    graphView = new PowerGraph(this, cSub, cBorder);
    for (int i = 0; graphSeries != null && i < graphSeries.length(); i++) {
      JSONObject sd = graphSeries.optJSONObject(i);
      int color = accents[sd.optInt("accent", i) % accents.length];
      graphView.addSeries(new PowerGraph.Series(color, sd.optString("label")));
      View dot = new View(this);
      GradientDrawable d = new GradientDrawable();
      d.setShape(GradientDrawable.OVAL);
      d.setColor(color);
      dot.setBackground(d);
      LinearLayout.LayoutParams dp = lp(dp(10), dp(10), 0);
      dp.leftMargin = dp(i == 0 ? 22 : 16);
      head.addView(dot, dp);
      TextView l = tv(sd.optString("label"), 16, cText, false);
      l.setPadding(dp(6), 0, 0, 0);
      head.addView(l);
    }
    card.addView(head);
    card.addView(graphView, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    LinearLayout.LayoutParams cp = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    cp.setMargins(dp(8), dp(12), dp(8), 0);
    card.setLayoutParams(cp);
    handler.postDelayed(graphTick, 5 * 60000L);
    return card;
  }

  private final Runnable graphTick = new Runnable() {
    @Override
    public void run() {
      if (currentPage < pageTypes.size() && "cards".equals(pageTypes.get(currentPage))) refreshGraph();
      handler.postDelayed(this, 5 * 60000L);
    }
  };

  private void refreshGraph() {
    if (graphView == null || ha == null || graphSeries == null) return;
    if (System.currentTimeMillis() - graphFetchedAt < 20000) return;
    graphFetchedAt = System.currentTimeMillis();
    // The current day from midnight, with a few hours after now on the axis (never past the next midnight)
    final long now = System.currentTimeMillis();
    final long from = Fmt.startOfDay(now);
    final long to = Math.min(now + 3 * 3600000L, Fmt.addDays(from, 1));
    JSONObject msg = new JSONObject();
    try {
      JSONArray ids = new JSONArray();
      for (int i = 0; i < graphSeries.length(); i++) ids.put(graphSeries.getJSONObject(i).optString("entity"));
      java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
      iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
      msg.put("type", "recorder/statistics_during_period").put("start_time", iso.format(new java.util.Date(from)))
          .put("statistic_ids", ids).put("period", "5minute").put("types", new JSONArray().put("mean"));
    } catch (Exception e) {
      return;
    }
    ha.command(msg, (result, unused) -> {
      if (result == null) {
        graphFetchedAt = 0;
        graphView.setMessage("Geschiedenis niet beschikbaar");
        return;
      }
      graphView.setWindow(from, to);
      for (int i = 0; i < graphSeries.length(); i++) {
        JSONArray rows = result.optJSONArray(graphSeries.optJSONObject(i).optString("entity"));
        int n = rows == null ? 0 : rows.length();
        PowerGraph.Series ser = graphView.series(i);
        ser.times = new long[n];
        ser.values = new float[n];
        for (int k = 0; k < n; k++) {
          JSONObject row = rows.optJSONObject(k);
          ser.times[k] = row.optLong("start") + 150000L;
          ser.values[k] = (float) Math.max(0, row.optDouble("mean", 0));
        }
      }
      graphView.changed();
    });
  }

  /** A value with its unit in a smaller size, for the big numbers on the Energie cards. */
  private CharSequence bigValue(String entity, int digits, String unit) {
    String text = value(entity, digits, unit);
    int space = text.lastIndexOf(' ');
    if (space <= 0 || unit == null || unit.isEmpty()) return text;
    android.text.SpannableString sp = new android.text.SpannableString(text);
    sp.setSpan(new android.text.style.RelativeSizeSpan(0.5f), space, text.length(), 0);
    sp.setSpan(new android.text.style.ForegroundColorSpan(cSub), space, text.length(), 0);
    return sp;
  }
}
