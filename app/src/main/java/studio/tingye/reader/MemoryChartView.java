package studio.tingye.reader;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.IntConsumer;

/** Interactive retention plot. The solid and dashed strokes stay distinguishable without colour. */
final class MemoryChartView extends View {
  private final MemoryForecast values;
  private final boolean compare;
  private final IntConsumer onSelect;
  private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path solid = new Path(), alternative = new Path(), area = new Path(), baseline = new Path();
  private final RectF plot = new RectF();
  private final float density, font;
  private final int last, slop;
  private final DashPathEffect dash;
  private LinearGradient fill;
  private float downX, downY;
  private boolean dragging;
  private int selected;

  MemoryChartView(Context context, MemoryForecast values, int selected, boolean compare, IntConsumer onSelect) {
    super(context);
    this.values = values;
    this.selected = selected;
    this.compare = compare;
    this.onSelect = onSelect;
    last = values.unassisted.length - 1;
    density = getResources().getDisplayMetrics().density;
    font = Math.min(1.5f * density, getResources().getDisplayMetrics().scaledDensity);
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
    dash = new DashPathEffect(new float[] {5*density, 4*density}, 0);
    setFocusable(!values.priority.isEmpty());
    setClickable(!values.priority.isEmpty());
    describe();
  }

  private void describe() {
    String s = values.priority.isEmpty() ? "记忆预测，等待第一条练习记录"
        : "记忆预测，第" + selected + "天，预计记住" + MemoryForecast.percent(values.unassisted[selected]);
    setContentDescription(s);
  }

  private float x(int day) { return plot.left + plot.width() * day / last; }
  private float y(double fraction) { return plot.bottom - plot.height() * (float)fraction; }
  private void path(Path path, double[] points) {
    path.reset();
    path.moveTo(x(0), y(points[0]));
    for (int i = 1; i <= last; i++) path.lineTo(x(i), y(points[i]));
  }

  @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    brush.setTextSize(11*font);
    float start = brush.measureText("100%") + 10*density;
    plot.set(start, 14*density, Math.max(start+1, w-5*density), Math.max(15*density, h-27*font));
    path(solid, values.unassisted);
    path(alternative, values.rehearsed);
    path(baseline, values.reference);
    area.set(solid);
    area.lineTo(plot.right, plot.bottom);
    area.lineTo(plot.left, plot.bottom);
    area.close();
    fill = new LinearGradient(0, plot.top, 0, plot.bottom, 0x553e9b88, 0x043e9b88, Shader.TileMode.CLAMP);
  }

  private void stroke(int color, float width) {
    brush.setColor(color); brush.setStrokeWidth(width * density);
    brush.setStyle(Paint.Style.STROKE); brush.setStrokeJoin(Paint.Join.ROUND);
    brush.setPathEffect(null); brush.setShader(null);
  }

  private void label(Canvas canvas, String s, float x, float y, Paint.Align align) {
    brush.setColor(0xff495e54); brush.setStyle(Paint.Style.FILL); brush.setTextAlign(align);
    brush.setTextSize(11*font); brush.setShader(null); brush.setPathEffect(null);
    canvas.drawText(s,x,y,brush);
  }

  @Override protected void onDraw(Canvas c) {
    int divisions = getHeight()/density >= 300 ? 10 : 5;
    for (int n = 0; n <= divisions; n++) {
      float y = y(n / (double)divisions);
      stroke(0xffd9e3d9, .8f); c.drawLine(plot.left,y,plot.right,y,brush);
      label(c, n*100/divisions+"%",plot.left-5*density,y+3.5f*font,Paint.Align.RIGHT);
    }
    int tick = last==30 ? 5 : last==14 ? 2 : 1;
    for (int d=0; d<=last; d+=tick) {
      stroke(0xffe2eae0,.65f);
      c.drawLine(x(d),plot.top,x(d),plot.bottom,brush);
      label(c, d==0 ? "今" : d+"天", x(d), plot.bottom+18*font,
          d==0 ? Paint.Align.LEFT : d==last ? Paint.Align.RIGHT : Paint.Align.CENTER);
    }
    if (values.priority.isEmpty()) {
      label(c,"练过一个词就能看到曲线", plot.centerX(),plot.centerY(),Paint.Align.CENTER);
      return;
    }
    brush.setStyle(Paint.Style.FILL); brush.setColor(-1); brush.setShader(fill);
    c.drawPath(area,brush);
    stroke(0xff687d91, 1.7f); brush.setPathEffect(dash); c.drawPath(baseline,brush);
    stroke(0xff206556, 2.6f); c.drawPath(solid,brush);
    brush.setStyle(Paint.Style.FILL);
    for (int d=0; d<=last; d++)
      c.drawCircle(x(d),y(values.unassisted[d]),(last<=14 ? 2.4f : 1.6f)*density,brush);
    if (compare) {
      stroke(0xffa95834, 2.2f); brush.setPathEffect(dash); c.drawPath(alternative,brush);
    }
    float x = x(selected), y = y(values.unassisted[selected]);
    stroke(0xaf426e60, 1); c.drawLine(x,plot.top,x,plot.bottom,brush);
    brush.setStyle(Paint.Style.FILL); brush.setColor(0xfff9fbf6); c.drawCircle(x,y,5*density,brush);
    brush.setColor(0xff206556); c.drawCircle(x,y,3.2f*density,brush);
  }

  private boolean select(int value) {
    value = Math.max(0, Math.min(last, value));
    if (value == selected || values.priority.isEmpty()) return false;
    selected = value;
    describe(); onSelect.accept(value); invalidate();
    return true;
  }

  private void selectAt(float x) { select(Math.round((x - plot.left) * last / plot.width())); }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (values.priority.isEmpty()) return false;
    float x = e.getX(), y = e.getY();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: downX=x; downY=y; dragging=false; return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging && Math.abs(x-downX)>slop && Math.abs(x-downX)>Math.abs(y-downY)) {
          dragging=true; getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (dragging) selectAt(x);
        return true;
      case MotionEvent.ACTION_UP:
        if (dragging || Math.hypot(x-downX,y-downY)<slop) { selectAt(x); performClick(); }
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      case MotionEvent.ACTION_CANCEL:
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default: return super.onTouchEvent(e);
    }
  }

  @Override public boolean performClick() { super.performClick(); return true; }

  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.SeekBar");
    if (values.priority.isEmpty()) return;
    info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,last,selected));
    if (selected < last) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    if (selected > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
  }

  @Override public boolean performAccessibilityAction(int action, Bundle args) {
    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return select(selected+1);
    if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return select(selected-1);
    if (action == android.R.id.accessibilityActionSetProgress && args != null)
      return select(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));
    return super.performAccessibilityAction(action,args);
  }

  @Override public boolean onKeyDown(int code, KeyEvent event) {
    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return select(selected+1);
    if (code == KeyEvent.KEYCODE_DPAD_LEFT) return select(selected-1);
    return super.onKeyDown(code,event);
  }
}
