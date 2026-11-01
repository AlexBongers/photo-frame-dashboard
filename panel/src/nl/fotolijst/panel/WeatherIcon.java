package nl.fotolijst.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Flat weather icons drawn with a few shapes, so no icon font or images are needed. */
final class WeatherIcon extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private String condition = "";
  private final boolean dark;

  WeatherIcon(Context context, boolean dark) {
    super(context);
    this.dark = dark;
  }

  void setCondition(String c) {
    String next = c == null ? "" : c;
    if (!next.equals(condition)) {
      condition = next;
      invalidate();
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float w = getWidth();
    float h = getHeight();
    float s = Math.min(w, h);
    canvas.translate((w - s) / 2f, (h - s) / 2f);
    int sun = 0xFFFFB300;
    int cloud = dark ? 0xFFB0BEC5 : 0xFF90A4AE;
    int darkCloud = dark ? 0xFF78909C : 0xFF607D8B;
    int rain = 0xFF4FC3F7;
    switch (condition) {
      case "sunny":
        sun(canvas, s * 0.5f, s * 0.5f, s * 0.22f, sun);
        break;
      case "clear-night":
        paint.setColor(0xFFFFE082);
        canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.26f, paint);
        paint.setColor(dark ? 0xFF0F1115 : 0xFFF5F6F8);
        canvas.drawCircle(s * 0.62f, s * 0.42f, s * 0.22f, paint);
        break;
      case "partlycloudy":
        sun(canvas, s * 0.38f, s * 0.36f, s * 0.17f, sun);
        cloud(canvas, s * 0.1f, s * 0.38f, s * 0.8f, cloud);
        break;
      case "rainy":
      case "snowy-rainy":
        cloud(canvas, s * 0.1f, s * 0.12f, s * 0.8f, darkCloud);
        drops(canvas, s, 2, rain, false);
        break;
      case "pouring":
        cloud(canvas, s * 0.1f, s * 0.1f, s * 0.8f, darkCloud);
        drops(canvas, s, 3, rain, false);
        break;
      case "snowy":
        cloud(canvas, s * 0.1f, s * 0.12f, s * 0.8f, cloud);
        drops(canvas, s, 3, 0xFFFFFFFF, true);
        break;
      case "lightning":
      case "lightning-rainy":
        cloud(canvas, s * 0.1f, s * 0.1f, s * 0.8f, darkCloud);
        paint.setColor(0xFFFFD600);
        paint.setStrokeWidth(s * 0.07f);
        canvas.drawLine(s * 0.52f, s * 0.62f, s * 0.42f, s * 0.8f, paint);
        canvas.drawLine(s * 0.42f, s * 0.8f, s * 0.56f, s * 0.8f, paint);
        canvas.drawLine(s * 0.56f, s * 0.8f, s * 0.46f, s * 0.95f, paint);
        break;
      case "fog":
        paint.setColor(cloud);
        paint.setStrokeWidth(s * 0.07f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 4; i++) {
          float y = s * (0.3f + i * 0.14f);
          canvas.drawLine(s * 0.15f, y, s * 0.85f, y, paint);
        }
        break;
      default:
        cloud(canvas, s * 0.1f, s * 0.25f, s * 0.8f, cloud);
        break;
    }
  }

  private void sun(Canvas c, float cx, float cy, float r, int color) {
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
    c.drawCircle(cx, cy, r, paint);
    paint.setStrokeWidth(r * 0.28f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    for (int i = 0; i < 8; i++) {
      double a = Math.PI / 4 * i;
      c.drawLine((float) (cx + Math.cos(a) * r * 1.4), (float) (cy + Math.sin(a) * r * 1.4),
          (float) (cx + Math.cos(a) * r * 1.8), (float) (cy + Math.sin(a) * r * 1.8), paint);
    }
  }

  private void cloud(Canvas c, float x, float y, float size, int color) {
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
    c.drawCircle(x + size * 0.32f, y + size * 0.5f, size * 0.22f, paint);
    c.drawCircle(x + size * 0.56f, y + size * 0.36f, size * 0.28f, paint);
    c.drawCircle(x + size * 0.78f, y + size * 0.54f, size * 0.18f, paint);
    rect.set(x + size * 0.1f, y + size * 0.52f, x + size * 0.9f, y + size * 0.72f);
    c.drawRoundRect(rect, size * 0.1f, size * 0.1f, paint);
  }

  private void drops(Canvas c, float s, int n, int color, boolean flakes) {
    paint.setColor(color);
    paint.setStrokeWidth(s * 0.06f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    for (int i = 0; i < n; i++) {
      float x = s * (0.3f + i * 0.2f);
      if (flakes) {
        c.drawCircle(x, s * 0.85f, s * 0.05f, paint);
      } else {
        c.drawLine(x, s * 0.76f, x - s * 0.05f, s * 0.92f, paint);
      }
    }
  }
}
