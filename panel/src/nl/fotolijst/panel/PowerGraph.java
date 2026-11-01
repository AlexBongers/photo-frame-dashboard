package nl.fotolijst.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** An area chart of a few power series over a time window, drawn with plain canvas calls. */
final class PowerGraph extends View {
  static final class Series {
    final int color;
    final String label;
    long[] times = new long[0];
    float[] values = new float[0];

    Series(int color, String label) {
      this.color = color;
      this.label = label;
    }
  }

  private final List<Series> series = new ArrayList<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path line = new Path();
  private final Path area = new Path();
  private final int textColor;
  private final int gridColor;
  private final float density;
  private long from;
  private long to;
  private String message = "";

  PowerGraph(Context context, int textColor, int gridColor) {
    super(context);
    this.textColor = textColor;
    this.gridColor = gridColor;
    density = context.getResources().getDisplayMetrics().density;
  }

  void addSeries(Series s) {
    series.add(s);
  }

  Series series(int index) {
    return series.get(index);
  }

  void setWindow(long fromMs, long toMs) {
    from = fromMs;
    to = toMs;
  }

  void setMessage(String text) {
    message = text;
    invalidate();
  }

  void changed() {
    message = "";
    invalidate();
  }

  private float dp(float v) {
    return v * density;
  }

  @Override
  protected void onDraw(Canvas c) {
    float left = dp(52);
    float right = getWidth() - dp(4);
    float top = dp(8);
    float bottom = getHeight() - dp(26);
    float w = right - left;
    float h = bottom - top;
    if (w <= 0 || h <= 0 || to <= from) return;

    float max = 100;
    for (Series s : series) for (float v : s.values) max = Math.max(max, v);
    float step = niceStep(max / 3f);
    float top1 = step * (float) Math.ceil(max / step);

    paint.setTextSize(dp(13));
    paint.setStrokeWidth(dp(1));
    for (float v = 0; v <= top1 + 0.1f; v += step) {
      float y = bottom - h * v / top1;
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(gridColor);
      c.drawLine(left, y, right, y, paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(textColor);
      paint.setTextAlign(Paint.Align.RIGHT);
      String label = v >= 1000 ? Fmt.num(v / 1000, v % 1000 == 0 ? 0 : 1) + " kW" : Math.round(v) + " W";
      c.drawText(label, left - dp(8), y + dp(4), paint);
    }

    // Hour labels on whole hours, every 2, 3 or 6 hours depending on the window
    long span = to - from;
    int every = span > 30 * 3600000L ? 6 : span > 8 * 3600000L ? 3 : 1;
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(from);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    cal.add(Calendar.HOUR_OF_DAY, 1);
    paint.setTextAlign(Paint.Align.CENTER);
    while (cal.getTimeInMillis() < to) {
      if (cal.get(Calendar.HOUR_OF_DAY) % every == 0) {
        float x = left + w * (cal.getTimeInMillis() - from) / (float) span;
        paint.setColor(textColor);
        c.drawText(Fmt.two(cal.get(Calendar.HOUR_OF_DAY)) + ":00", x, getHeight() - dp(6), paint);
      }
      cal.add(Calendar.HOUR_OF_DAY, 1);
    }

    boolean any = false;
    for (Series s : series) {
      int n = s.values.length;
      if (n < 2) continue;
      any = true;
      line.reset();
      area.reset();
      float lastX = left;
      for (int i = 0; i < n; i++) {
        float x = left + w * Math.max(0, Math.min(1, (s.times[i] - from) / (float) span));
        float y = bottom - h * Math.min(1f, s.values[i] / top1);
        if (i == 0) {
          line.moveTo(x, y);
          area.moveTo(x, bottom);
          area.lineTo(x, y);
        } else {
          line.lineTo(x, y);
          area.lineTo(x, y);
        }
        lastX = x;
      }
      area.lineTo(lastX, bottom);
      area.close();
      paint.setStyle(Paint.Style.FILL);
      paint.setShader(new LinearGradient(0, top, 0, bottom, (s.color & 0x00FFFFFF) | 0x66000000,
          (s.color & 0x00FFFFFF) | 0x05000000, Shader.TileMode.CLAMP));
      c.drawPath(area, paint);
      paint.setShader(null);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(2.5f));
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setColor(s.color);
      c.drawPath(line, paint);
    }
    paint.setStrokeWidth(dp(1));
    if (!any || !message.isEmpty()) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(textColor);
      paint.setTextSize(dp(16));
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText(message.isEmpty() ? "Geen gegevens" : message, left + w / 2, top + h / 2, paint);
    }
  }

  private static float niceStep(float raw) {
    float pow = (float) Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
    float f = raw / pow;
    float n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5f ? 2.5f : f <= 5 ? 5 : 10;
    return n * pow;
  }
}
