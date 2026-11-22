package studio.tingye.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

/** Predicted and observed self-assessment averages for nonempty probability bands. */
final class MemoryCalibrationPlot extends View {
  private final int[] count;
  private final double[] predictions, observations;
  private final float unit, type;
  private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final DashPathEffect dash;
  private final int max;

  MemoryCalibrationPlot(Context context, int[] count, double[] predictions, double[] observations) {
    super(context);
    this.count=count; this.predictions=predictions; this.observations=observations;
    max=Math.max(1,Math.max(count[0],Math.max(count[1],count[2])));
    unit=getResources().getDisplayMetrics().density;
    type=Math.min(1.5f*unit,getResources().getDisplayMetrics().scaledDensity);
    dash=new DashPathEffect(new float[]{4*unit,4*unit},0);
    setContentDescription("预测与自评的对照图，横轴为预计记住率，纵轴为自评折算。各组数值列在图下方。");
  }

  private void line(Canvas c,float x1,float y1,float x2,float y2,int color,float thickness) {
    pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(thickness*unit);pen.setColor(color);
    c.drawLine(x1,y1,x2,y2,pen);
  }
  private void label(Canvas c,String text,float x,float y,Paint.Align align) {
    pen.setStyle(Paint.Style.FILL);pen.setColor(0xff495e54);pen.setTextSize(11*type);pen.setTextAlign(align);
    c.drawText(text,x,y,pen);
  }

  @Override protected void onDraw(Canvas c) {
    pen.setTextSize(11*type);
    float left=pen.measureText("100%")+7*unit,right=getWidth()-12*unit,top=22*type,bottom=getHeight()-38*type;
    for(int i=0;i<=2;i++) {
      float f=i/2f,x=left+f*(right-left),y=bottom-f*(bottom-top);
      line(c,left,y,right,y,0xffd9e3d9,.8f);
      label(c,(i*50)+"%",left-5*unit,y+3*type,Paint.Align.RIGHT);
      label(c,(i*50)+"%",x,bottom+13*type,i==0?Paint.Align.LEFT:i==2?Paint.Align.RIGHT:Paint.Align.CENTER);
    }
    pen.setPathEffect(dash);line(c,left,bottom,right,top,0xff74897f,1.25f);pen.setPathEffect(null);
    label(c,"实际自评",left,12*type,Paint.Align.LEFT);
    label(c,"评分前预计",(left+right)/2,getHeight()-3*type,Paint.Align.CENTER);
    for(int i=0;i<count.length;i++) if(count[i]>0) {
      float x=left+(float)(predictions[i]/count[i])*(right-left);
      float y=bottom-(float)(observations[i]/count[i])*(bottom-top);
      float radius=(3.7f+3.5f*(float)Math.sqrt(count[i]/(double)max))*unit;
      pen.setColor(0xffa35432);pen.setStyle(Paint.Style.FILL);c.drawCircle(x,y,radius,pen);
      pen.setColor(0xfffafbf5);pen.setStrokeWidth(unit);pen.setStyle(Paint.Style.STROKE);c.drawCircle(x,y,radius,pen);
    }
  }
}
