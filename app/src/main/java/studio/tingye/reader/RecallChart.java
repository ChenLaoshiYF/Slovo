package studio.tingye.reader;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A seven-day projection over words this learner has actually practised. */
final class RecallChart extends View {
  private final double[] points;
  private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();

  RecallChart(Context context, double[] points) {
    super(context);
    this.points = points;
    setContentDescription("预计记住的比例，今天 " + Math.round(points[0] * 100)
        + "%，七天后 " + Math.round(points[7] * 100) + "%");
  }

  @Override protected void onDraw(Canvas canvas) {
    float dp = getResources().getDisplayMetrics().density;
    float left = 37 * dp, top = 16 * dp, right = getWidth() - 11 * dp;
    float bottom = getHeight() - 26 * dp;
    brush.setTextSize(11 * dp);
    for (int i = 0; i <= 2; i++) {
      float y = top + (bottom - top) * i / 2;
      brush.setColor(0xffd8dce3);
      brush.setStrokeWidth(dp);
      canvas.drawLine(left, y, right, y, brush);
      brush.setColor(PaperActivity.muted);
      canvas.drawText((100 - i * 50) + "%", 0, y + 4 * dp, brush);
    }
    canvas.drawText("今天", left, bottom + 18 * dp, brush);
    canvas.drawText("7 天后", right - brush.measureText("7 天后"), bottom + 18 * dp, brush);
    path.reset();
    for (int i = 0; i < points.length; i++) {
      float x = left + (right - left) * i / 7;
      float y = bottom - (bottom - top) * (float) points[i];
      if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
      brush.setColor(PaperActivity.blue);
      canvas.drawCircle(x, y, 2.4f * dp, brush);
    }
    brush.setStyle(Paint.Style.STROKE);
    brush.setStrokeWidth(2 * dp);
    canvas.drawPath(path, brush);
    brush.setStyle(Paint.Style.FILL);
  }
}

