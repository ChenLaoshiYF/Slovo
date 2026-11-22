package studio.tingye.reader;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.IntConsumer;

/** Compact, selectable chart for actual practice counts and strength history. */
final class MemorySeriesChart extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF plot = new RectF();
  private final Path highlight=new Path();
  private final String[] dates;
  private final int[][] points;
  private final int[] colors;
  private final boolean columns;
  private final int ceiling;
  private final float dp;
  private final IntConsumer change;
  private int selection;
  private float downX, downY;
  private boolean dragging;

  MemorySeriesChart(Context context, String[] dates, int[][] points, int[] colors, boolean columns,
      int selection, IntConsumer change) {
    super(context);
    this.dates = dates;
    this.points = points;
    this.colors = colors;
    this.columns = columns;
    this.selection = selection;
    this.change = change;
    dp = getResources().getDisplayMetrics().density;
    int max = 0;
    for (int[] point : points) {
      int total = 0;
      for (int value : point) total = columns ? total+value : Math.max(total,value);
      max = Math.max(total,max);
    }
    int step = max<=8 ? 1 : max<=20 ? 5 : max<=80 ? 10 : max<=200 ? 25 : max<=400 ? 50 : 100;
    ceiling = Math.max(4*step,(max+4*step-1)/(4*step)*(4*step));
    setClickable(true);
    setFocusable(true);
    describe();
  }

  private void describe() {
    int count = 0;
    for (int number : points[selection]) count = columns ? count+number : Math.max(count,number);
    setContentDescription((columns ? "学习情况柱图，" : "记忆持久度趋势，")+dates[selection]+"，"+count+" 条");
  }

  @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
    paint.setTextSize(dp*11);
    plot.set(paint.measureText(Integer.toString(ceiling))+9*dp,10*dp,w-4*dp,h-27*dp);
  }

  private float x(int i) { return plot.left+plot.width()*(i+.5f)/dates.length; }
  private float y(int i) { return plot.bottom-plot.height()*i/(float)ceiling; }

  @Override protected void onDraw(Canvas canvas) {
    float width = plot.width()/dates.length;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0x145c9e89);
    SoftCorners.trace(highlight,x(selection)-width*.49f,plot.top,x(selection)+width*.49f,
        plot.bottom+22*dp,5*dp);
    canvas.drawPath(highlight,paint);
    for (int i=0; i<=4; i++) {
      int count = ceiling*i/4;
      paint.setColor(0xffd9e3d9);
      canvas.drawLine(plot.left,y(count),plot.right,y(count),paint);
      text(canvas,Integer.toString(count),plot.left-4*dp,y(count)+4*dp,Paint.Align.RIGHT,0xff55665d);
    }
    for (int i=0; i<dates.length; i++) {
      text(canvas,dates[i],x(i),plot.bottom+15*dp,Paint.Align.CENTER,
          i==selection ? 0xff204e43 : 0xff596a5e);
    }
    for (int series=0; series<colors.length; series++) {
      paint.setColor(colors[series]);
      paint.setStrokeWidth(2*dp);
      for (int i=0; i<dates.length; i++) {
        if (columns) {
          int start = 0;
          for (int layer=0; layer<series; layer++) start += points[i][layer];
          int end = start+points[i][series];
          if (end>start) canvas.drawRect(x(i)-width*.29f,y(end),x(i)+width*.29f,y(start),paint);
        } else {
          if (i>0 && points[i][0]>0 && points[i-1][0]>0)
            canvas.drawLine(x(i-1),y(points[i-1][series]),x(i),y(points[i][series]),paint);
          if (points[i][0]>0) canvas.drawCircle(x(i),y(points[i][series]),2.6f*dp,paint);
        }
      }
    }
  }

  private void text(Canvas canvas,String text,float x,float y,Paint.Align alignment,int color) {
    paint.setColor(color);
    paint.setTextAlign(alignment);
    paint.setTextSize(11*dp);
    canvas.drawText(text,x,y,paint);
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX=event.getX(); downY=event.getY(); dragging=false; return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging && Math.abs(event.getX()-downX)>Math.abs(event.getY()-downY)
            && Math.abs(event.getX()-downX)>ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
          dragging=true;
          getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (dragging) select(event.getX());
        return true;
      case MotionEvent.ACTION_UP:
        select(event.getX());
        performClick();
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      case MotionEvent.ACTION_CANCEL:
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default: return super.onTouchEvent(event);
    }
  }

  private void select(float x) {
    int index = (int)Math.floor((x-plot.left)*dates.length/plot.width());
    selectIndex(index);
  }

  private boolean selectIndex(int index) {
    int selected = Math.max(0,Math.min(dates.length-1,index));
    if (selected==selection) return false;
    selection=selected;
    describe();
    change.accept(selection);
    invalidate();
    return true;
  }

  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.SeekBar");
    info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,dates.length-1,selection));
    if (selection<dates.length-1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    if (selection>0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
  }

  @Override public boolean performAccessibilityAction(int action,Bundle args) {
    if (action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return selectIndex(selection+1);
    if (action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return selectIndex(selection-1);
    if (action==android.R.id.accessibilityActionSetProgress && args!=null) {
      return selectIndex(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));
    }
    return super.performAccessibilityAction(action,args);
  }

  @Override public boolean onKeyDown(int code,KeyEvent event) {
    if (code==KeyEvent.KEYCODE_DPAD_RIGHT) return selectIndex(selection+1);
    if (code==KeyEvent.KEYCODE_DPAD_LEFT) return selectIndex(selection-1);
    return super.onKeyDown(code,event);
  }

  @Override public boolean performClick() { super.performClick(); return true; }
}

