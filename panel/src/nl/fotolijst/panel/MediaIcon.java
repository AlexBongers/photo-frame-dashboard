package nl.fotolijst.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Transport and volume icons drawn with shapes, so the panel needs no icon font. */
final class MediaIcon extends View {
  static final int PLAY = 0;
  static final int PAUSE = 1;
  static final int PREV = 2;
  static final int NEXT = 3;
  static final int SHUFFLE = 4;
  static final int REPEAT = 5;
  static final int VOLUME = 6;
  static final int MUTE = 7;
  static final int NOTE = 8;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final RectF rect = new RectF();
  private int kind;
  private int color;

  MediaIcon(Context context, int kind, int color) {
    super(context);
    this.kind = kind;
    this.color = color;
  }

  void set(int newKind, int newColor) {
    if (newKind != kind || newColor != color) {
      kind = newKind;
      color = newColor;
      invalidate();
    }
  }

  @Override
  protected void onDraw(Canvas c) {
    float s = Math.min(getWidth(), getHeight());
    c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    switch (kind) {
      case PLAY:
        triangle(c, s * 0.3f, s * 0.2f, s * 0.8f, s * 0.5f, s * 0.3f, s * 0.8f);
        break;
      case PAUSE:
        rect.set(s * 0.26f, s * 0.2f, s * 0.42f, s * 0.8f);
        c.drawRoundRect(rect, s * 0.04f, s * 0.04f, paint);
        rect.set(s * 0.58f, s * 0.2f, s * 0.74f, s * 0.8f);
        c.drawRoundRect(rect, s * 0.04f, s * 0.04f, paint);
        break;
      case PREV:
        rect.set(s * 0.2f, s * 0.22f, s * 0.31f, s * 0.78f);
        c.drawRoundRect(rect, s * 0.03f, s * 0.03f, paint);
        triangle(c, s * 0.8f, s * 0.22f, s * 0.36f, s * 0.5f, s * 0.8f, s * 0.78f);
        break;
      case NEXT:
        rect.set(s * 0.69f, s * 0.22f, s * 0.8f, s * 0.78f);
        c.drawRoundRect(rect, s * 0.03f, s * 0.03f, paint);
        triangle(c, s * 0.2f, s * 0.22f, s * 0.64f, s * 0.5f, s * 0.2f, s * 0.78f);
        break;
      case SHUFFLE:
        stroke(s * 0.07f);
        c.drawLine(s * 0.15f, s * 0.3f, s * 0.4f, s * 0.3f, paint);
        c.drawLine(s * 0.4f, s * 0.3f, s * 0.6f, s * 0.7f, paint);
        c.drawLine(s * 0.6f, s * 0.7f, s * 0.78f, s * 0.7f, paint);
        c.drawLine(s * 0.15f, s * 0.7f, s * 0.4f, s * 0.7f, paint);
        c.drawLine(s * 0.4f, s * 0.7f, s * 0.6f, s * 0.3f, paint);
        c.drawLine(s * 0.6f, s * 0.3f, s * 0.78f, s * 0.3f, paint);
        paint.setStyle(Paint.Style.FILL);
        triangle(c, s * 0.76f, s * 0.2f, s * 0.9f, s * 0.3f, s * 0.76f, s * 0.4f);
        triangle(c, s * 0.76f, s * 0.6f, s * 0.9f, s * 0.7f, s * 0.76f, s * 0.8f);
        break;
      case REPEAT:
        stroke(s * 0.07f);
        rect.set(s * 0.14f, s * 0.26f, s * 0.86f, s * 0.74f);
        c.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint);
        paint.setStyle(Paint.Style.FILL);
        triangle(c, s * 0.66f, s * 0.14f, s * 0.82f, s * 0.26f, s * 0.66f, s * 0.38f);
        break;
      case VOLUME:
      case MUTE:
        paint.setStyle(Paint.Style.FILL);
        rect.set(s * 0.14f, s * 0.38f, s * 0.3f, s * 0.62f);
        c.drawRect(rect, paint);
        triangle(c, s * 0.3f, s * 0.38f, s * 0.52f, s * 0.2f, s * 0.52f, s * 0.8f);
        c.drawRect(s * 0.3f, s * 0.38f, s * 0.52f, s * 0.62f, paint);
        if (kind == VOLUME) {
          stroke(s * 0.06f);
          rect.set(s * 0.46f, s * 0.34f, s * 0.7f, s * 0.66f);
          c.drawArc(rect, -50, 100, false, paint);
          rect.set(s * 0.42f, s * 0.2f, s * 0.84f, s * 0.8f);
          c.drawArc(rect, -50, 100, false, paint);
        } else {
          stroke(s * 0.07f);
          c.drawLine(s * 0.62f, s * 0.38f, s * 0.86f, s * 0.62f, paint);
          c.drawLine(s * 0.86f, s * 0.38f, s * 0.62f, s * 0.62f, paint);
        }
        break;
      default: // NOTE
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(s * 0.34f, s * 0.74f, s * 0.14f, paint);
        c.drawCircle(s * 0.7f, s * 0.64f, s * 0.14f, paint);
        rect.set(s * 0.44f, s * 0.22f, s * 0.5f, s * 0.74f);
        c.drawRect(rect, paint);
        rect.set(s * 0.8f, s * 0.12f, s * 0.86f, s * 0.64f);
        c.drawRect(rect, paint);
        path.reset();
        path.moveTo(s * 0.44f, s * 0.22f);
        path.lineTo(s * 0.86f, s * 0.12f);
        path.lineTo(s * 0.86f, s * 0.26f);
        path.lineTo(s * 0.44f, s * 0.36f);
        path.close();
        c.drawPath(path, paint);
        break;
    }
  }

  private void stroke(float width) {
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(width);
  }

  private void triangle(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
    path.reset();
    path.moveTo(x1, y1);
    path.lineTo(x2, y2);
    path.lineTo(x3, y3);
    path.close();
    paint.setStyle(Paint.Style.FILL);
    c.drawPath(path, paint);
  }
}
