package nl.fotolijst.panel;

/**
 * The maths behind the light sensor reaction, with no Android classes so it can be tested on a computer.
 * Feed it lux readings; it keeps a smoothed lux, the screen brightness and a dim overlay to apply, and
 * decides when to switch between the dark and the light theme (only after the light stayed past a
 * threshold for a while, with a gap between the two thresholds so it cannot flap).
 */
final class AmbientModel {
  static final float[][] DEFAULT_BRIGHTNESS = {
      {0, 0.03f}, {3, 0.06f}, {10, 0.12f}, {30, 0.25f}, {150, 0.50f}, {600, 0.80f}, {2000, 1.0f}};
  /** Alpha of a black overlay on top of the screen, to go darker than the backlight can. */
  static final float[][] DEFAULT_DIM = {{0, 0.55f}, {3, 0.45f}, {10, 0.30f}, {30, 0.12f}, {80, 0f}};

  private final float[][] brightnessCurve;
  private final float[][] dimCurve;
  private final float tauMs;
  private final boolean autoTheme;
  private final float toLightLux;
  private final float toDarkLux;
  private final long holdMs;

  private float smoothed = -1;
  private long lastMs;
  private float brightness = -1;
  private float dim = -1;
  private boolean dark;
  private long themeCandidateSince = -1;

  AmbientModel(float[][] brightnessCurve, float[][] dimCurve, float smoothingSeconds,
      boolean autoTheme, float toLightLux, float toDarkLux, float holdSeconds, boolean startDark) {
    this.brightnessCurve = brightnessCurve == null ? DEFAULT_BRIGHTNESS : brightnessCurve;
    this.dimCurve = dimCurve == null ? DEFAULT_DIM : dimCurve;
    this.tauMs = Math.max(0.3f, smoothingSeconds) * 1000f;
    this.autoTheme = autoTheme;
    this.toLightLux = toLightLux;
    this.toDarkLux = Math.min(toDarkLux, toLightLux);
    this.holdMs = (long) (Math.max(0f, holdSeconds) * 1000);
    this.dark = startDark;
  }

  void reset() {
    smoothed = -1;
    brightness = -1;
    dim = -1;
    themeCandidateSince = -1;
  }

  void setDark(boolean value) {
    dark = value;
    themeCandidateSince = -1;
  }

  float smoothedLux() {
    return smoothed;
  }

  float brightness() {
    return brightness;
  }

  float dim() {
    return dim;
  }

  boolean dark() {
    return dark;
  }

  /** Piecewise linear on a log scale of lux. */
  static float interpolate(float[][] curve, float lux) {
    float x = (float) Math.log10(Math.max(0f, lux) + 1f);
    if (x <= Math.log10(curve[0][0] + 1f)) return curve[0][1];
    for (int i = 1; i < curve.length; i++) {
      float x0 = (float) Math.log10(curve[i - 1][0] + 1f);
      float x1 = (float) Math.log10(curve[i][0] + 1f);
      if (x <= x1) {
        float t = x1 > x0 ? (x - x0) / (x1 - x0) : 1f;
        return curve[i - 1][1] + t * (curve[i][1] - curve[i - 1][1]);
      }
    }
    return curve[curve.length - 1][1];
  }

  /** Result of one reading. */
  static final class Change {
    boolean levels;
    boolean themeSwitch;
    boolean newDark;
  }

  Change update(float lux, long nowMs) {
    if (smoothed < 0) {
      smoothed = lux;
    } else {
      float alpha = 1f - (float) Math.exp(-(nowMs - lastMs) / tauMs);
      smoothed += alpha * (lux - smoothed);
    }
    lastMs = nowMs;
    Change change = new Change();

    float b = Math.max(0.02f, Math.min(1f, interpolate(brightnessCurve, smoothed)));
    float d = Math.max(0f, Math.min(0.85f, interpolate(dimCurve, smoothed)));
    if (brightness < 0 || Math.abs(b - brightness) >= 0.02f || Math.abs(d - dim) >= 0.03f) {
      brightness = b;
      dim = d;
      change.levels = true;
    }

    if (autoTheme) {
      boolean wantLight = dark && smoothed >= toLightLux;
      boolean wantDark = !dark && smoothed <= toDarkLux;
      if (wantLight || wantDark) {
        if (themeCandidateSince < 0) themeCandidateSince = nowMs;
        if (nowMs - themeCandidateSince >= holdMs) {
          dark = wantDark;
          themeCandidateSince = -1;
          change.themeSwitch = true;
          change.newDark = dark;
        }
      } else {
        themeCandidateSince = -1;
      }
    }
    return change;
  }
}
