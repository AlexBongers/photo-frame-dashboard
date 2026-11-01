package nl.fotolijst.panel;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Follows the ambient light sensor: screen brightness, a dim overlay for very dark rooms, and the
 * dark or light theme. The curves and thresholds come from the "ambient" object in panel.json.
 */
final class AmbientLight implements SensorEventListener {
  interface Listener {
    void onLevels(float brightness, float dimAlpha);

    void onTheme(boolean dark);
  }

  private static final String TAG = "Panel";

  private final SensorManager manager;
  private final Sensor sensor;
  private final Listener listener;
  private final AmbientModel model;
  private float lastLux = -1;

  AmbientLight(Context context, JSONObject config, boolean autoTheme, boolean startDark, Listener listener) {
    manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    sensor = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_LIGHT);
    this.listener = listener;
    JSONObject theme = config == null ? null : config.optJSONObject("auto_theme");
    model = new AmbientModel(
        curve(config == null ? null : config.optJSONArray("curve")),
        curve(config == null ? null : config.optJSONArray("dim_curve")),
        config == null ? 2f : (float) config.optDouble("smoothing_seconds", 2),
        autoTheme,
        theme == null ? 250f : (float) theme.optDouble("to_light_lux", 250),
        theme == null ? 80f : (float) theme.optDouble("to_dark_lux", 80),
        theme == null ? 20f : (float) theme.optDouble("hold_seconds", 20),
        startDark);
  }

  boolean available() {
    return sensor != null;
  }

  void start() {
    if (sensor == null) return;
    model.reset();
    manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
  }

  void stop() {
    if (manager != null) manager.unregisterListener(this);
  }

  void setDark(boolean dark) {
    model.setDark(dark);
  }

  float lux() {
    return lastLux;
  }

  float smoothedLux() {
    return model.smoothedLux();
  }

  float brightness() {
    return model.brightness();
  }

  float dim() {
    return model.dim();
  }

  private static float[][] curve(JSONArray config) {
    if (config == null || config.length() < 2) return null;
    try {
      float[][] c = new float[config.length()][2];
      for (int i = 0; i < c.length; i++) {
        JSONArray p = config.getJSONArray(i);
        c[i][0] = (float) p.getDouble(0);
        c[i][1] = (float) p.getDouble(1);
      }
      return c;
    } catch (Exception e) {
      Log.w(TAG, "ambient curve: " + e);
      return null;
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    float lux = event.values[0];
    lastLux = lux;
    AmbientModel.Change change = model.update(lux, System.currentTimeMillis());
    if (change.levels) {
      Log.i(TAG, "ambient " + Math.round(lux) + " lux (smoothed " + Math.round(model.smoothedLux())
          + ") -> brightness " + Math.round(model.brightness() * 100) + "%, dim " + Math.round(model.dim() * 100) + "%");
      listener.onLevels(model.brightness(), model.dim());
    }
    if (change.themeSwitch) {
      Log.i(TAG, "ambient theme -> " + (change.newDark ? "dark" : "light") + " at " + Math.round(model.smoothedLux()) + " lux");
      listener.onTheme(change.newDark);
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    // not used
  }
}
