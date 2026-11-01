package nl.fotolijst.panel;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import java.util.Calendar;
import org.json.JSONObject;

/**
 * Turns the screen off at night. Inside the configured hours, and (optionally) once the room has been
 * dark for a few minutes and nobody touched the screen, it lets Android's own screen timeout switch the
 * display off. It wakes the screen at the end of the window with an alarm, or earlier when the room
 * light comes on. Needs the "Modify system settings" permission, which the user grants once.
 */
final class NightMode {
  private static final String TAG = "Panel";
  private static final String PREFS = "panel";
  private static final String KEY_ACTIVE = "night_active";
  private static final String KEY_SAVED = "night_saved_timeout";
  private static final int SLEEP_TIMEOUT_MS = 15000;

  private final Context app;
  private final boolean enabled;
  private final int fromMin;
  private final int toMin;
  private final boolean onlyDark;
  private final float darkLux;
  private final long darkMs;
  private final long idleMs;
  private final float wakeLux;
  private long darkSince = -1;

  NightMode(Context context, JSONObject config) {
    app = context.getApplicationContext();
    JSONObject c = config == null ? new JSONObject() : config;
    enabled = c.optBoolean("enabled", true);
    fromMin = minutes(c.optString("from", "23:30"));
    toMin = minutes(c.optString("to", "07:00"));
    onlyDark = c.optBoolean("only_when_dark", true);
    darkLux = (float) c.optDouble("dark_lux", 25);
    darkMs = (long) (c.optDouble("dark_minutes", 3) * 60000);
    idleMs = (long) (c.optDouble("idle_seconds", 120) * 1000);
    wakeLux = (float) c.optDouble("wake_lux", 60);
  }

  private static int minutes(String hhmm) {
    try {
      String[] p = hhmm.split(":");
      return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    } catch (Exception e) {
      return 0;
    }
  }

  boolean enabled() {
    return enabled;
  }

  boolean canWrite() {
    return Settings.System.canWrite(app);
  }

  String describe() {
    String hours = Fmt.two(fromMin / 60) + ":" + Fmt.two(fromMin % 60) + "–" + Fmt.two(toMin / 60) + ":" + Fmt.two(toMin % 60);
    if (!enabled) return "uit";
    if (!canWrite()) return "toestemming nodig (" + hours + ")";
    return "aan, " + hours + (onlyDark ? ", als het donker is" : "");
  }

  boolean inWindow() {
    Calendar c = Calendar.getInstance();
    int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    return fromMin <= toMin ? now >= fromMin && now < toMin : now >= fromMin || now < toMin;
  }

  /** Called about every 10 s while the screen is on. Returns true when it put the screen to sleep. */
  boolean tick(Activity activity, float smoothedLux, long lastTouchMs, long nowMs) {
    if (!enabled || !inWindow() || !canWrite()) {
      darkSince = -1;
      return false;
    }
    if (nowMs - lastTouchMs < idleMs) return false;
    if (onlyDark) {
      if (smoothedLux < 0 || smoothedLux >= darkLux) {
        darkSince = -1;
        return false;
      }
      if (darkSince < 0) darkSince = nowMs;
      if (nowMs - darkSince < darkMs) return false;
    }
    sleep(activity);
    return true;
  }

  private SharedPreferences prefs() {
    return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** Sleep right now and wake after the given number of minutes, ignoring the hours and the light. For testing. */
  boolean testSleep(Activity activity, int minutes) {
    if (!canWrite()) return false;
    sleep(activity, System.currentTimeMillis() + minutes * 60000L);
    return true;
  }

  private void sleep(Activity activity) {
    Calendar wake = Calendar.getInstance();
    wake.set(Calendar.HOUR_OF_DAY, toMin / 60);
    wake.set(Calendar.MINUTE, toMin % 60);
    wake.set(Calendar.SECOND, 0);
    wake.set(Calendar.MILLISECOND, 0);
    if (wake.getTimeInMillis() <= System.currentTimeMillis() + 60000) wake.add(Calendar.DAY_OF_MONTH, 1);
    sleep(activity, wake.getTimeInMillis());
  }

  private void sleep(Activity activity, long wakeAtMs) {
    SharedPreferences p = prefs();
    if (!p.getBoolean(KEY_ACTIVE, false)) {
      int current = Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 60000);
      p.edit().putBoolean(KEY_ACTIVE, true).putInt(KEY_SAVED, current).apply();
    }
    Log.i(TAG, "night: screen off, wake at " + Fmt.time(wakeAtMs));
    Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_TIMEOUT_MS);
    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAtMs, wakePending(app));
    Watcher.start(app, wakeLux, Math.max(1000, wakeAtMs - System.currentTimeMillis()));
  }

  private static PendingIntent wakePending(Context context) {
    return PendingIntent.getBroadcast(context, 0, new Intent(context, NightWakeReceiver.class),
        PendingIntent.FLAG_UPDATE_CURRENT);
  }

  /** Ends the night state: restores the screen timeout and stops the alarm and the light watcher. */
  static boolean endNight(Context context) {
    Context app = context.getApplicationContext();
    SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    boolean was = p.getBoolean(KEY_ACTIVE, false);
    if (!was) return false;
    try {
      if (p.contains(KEY_SAVED) && Settings.System.canWrite(app)) {
        Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, p.getInt(KEY_SAVED, 60000));
      }
    } catch (Exception e) {
      Log.w(TAG, "night: could not restore the screen timeout: " + e);
    }
    p.edit().remove(KEY_ACTIVE).remove(KEY_SAVED).apply();
    ((AlarmManager) app.getSystemService(Context.ALARM_SERVICE)).cancel(wakePending(app));
    Watcher.stop();
    Log.i(TAG, "night: over, screen timeout restored");
    return true;
  }

  /** The alarm or the room light: end the night and switch the screen on. */
  static void wake(Context context) {
    endNight(context);
    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    @SuppressWarnings("deprecation")
    PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "panel:wake");
    lock.acquire(5000);
    Log.i(TAG, "night: wake");
  }

  /** Keeps the CPU awake just enough to watch the light sensor while the screen is off. */
  private static final class Watcher implements SensorEventListener {
    private static Watcher current;
    private final Context app;
    private final float wakeLux;
    private final SensorManager sensors;
    private final PowerManager.WakeLock lock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable wakeTask = new Runnable() {
      @Override
      public void run() {
        Log.i(TAG, "night: wake time reached");
        wake(app);
      }
    };
    private int bright;
    private int covered;
    private float baseline = -1;
    private long baselineAt;

    private Watcher(Context app, float wakeLux) {
      this.app = app;
      this.wakeLux = wakeLux;
      sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
      PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
      lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panel:night");
    }

    /**
     * The vendor background manager turns alarms of unlisted apps into non-waking ones, so the wake
     * time is also kept by an in-process timer, with the CPU held awake by a partial wake lock. The
     * alarm stays as a backup.
     */
    static synchronized void start(Context app, float wakeLux, long wakeInMs) {
      stop();
      Watcher w = new Watcher(app, wakeLux);
      w.lock.acquire(wakeInMs + 5 * 60000L);
      w.handler.postDelayed(w.wakeTask, wakeInMs);
      Sensor light = w.sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
      if (light != null) w.sensors.registerListener(w, light, SensorManager.SENSOR_DELAY_NORMAL);
      current = w;
    }

    static synchronized void stop() {
      if (current == null) return;
      current.handler.removeCallbacks(current.wakeTask);
      current.sensors.unregisterListener(current);
      if (current.lock.isHeld()) current.lock.release();
      current = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
      float lux = event.values[0];
      bright = lux >= wakeLux ? bright + 1 : 0;
      if (bright >= 4) {
        Log.i(TAG, "night: light " + Math.round(lux) + " lux, waking");
        wake(app);
        return;
      }
      // A finger or hand held over the sensor wakes it too: the light drops to a third of what it was.
      long now = System.currentTimeMillis();
      if (baseline < 0) {
        baseline = lux;
        baselineAt = now;
      }
      boolean dip = baseline >= 4 && lux <= baseline * 0.35f;
      covered = dip ? covered + 1 : 0;
      if (!dip) {
        baseline += 0.05f * (lux - baseline);
        baselineAt = now;
      }
      if (covered >= 3) {
        Log.i(TAG, "night: sensor covered (" + Math.round(lux) + " lux against " + Math.round(baseline) + "), waking");
        wake(app);
      }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
      // not used
    }
  }
}
