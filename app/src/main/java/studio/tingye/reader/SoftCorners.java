package studio.tingye.reader;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Each corner uses two cubic curves, with zero curvature where they meet the straight edges. */
final class SoftCorners {
  private SoftCorners() {}

  static void trace(Path path,float l,float t,float r,float b,float radius) {
    path.reset();
    if (l>=r || t>=b) return;
    float d=Math.min(Math.max(0,radius)*1.22f,Math.min(r-l,b-t)*.5f);
    if(d==0) {
      path.addRect(l,t,r,b,Path.Direction.CW);
      return;
    }
    float a=.18f*d, k=.36f*d, m=.72f*d;
    path.moveTo(l+d,t);
    path.lineTo(r-d,t);
    path.cubicTo(r-m,t,r-k,t,r-a,t+a);
    path.cubicTo(r,t+k,r,t+m,r,t+d);
    path.lineTo(r,b-d);
    path.cubicTo(r,b-m,r,b-k,r-a,b-a);
    path.cubicTo(r-k,b,r-m,b,r-d,b);
    path.lineTo(l+d,b);
    path.cubicTo(l+m,b,l+k,b,l+a,b-a);
    path.cubicTo(l,b-k,l,b-m,l,b-d);
    path.lineTo(l,t+d);
    path.cubicTo(l,t+m,l,t+k,l+a,t+a);
    path.cubicTo(l+k,t,l+m,t,l+d,t);
    path.close();
  }

  static final class Surface extends Drawable {
    private final Path face=new Path(), border=new Path();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int top,bottom,edge;
    private final float radius,stroke;
    private int alpha=255;
    private ColorFilter filter;
    private Shader gradient;
    private final int width,height;

    Surface(int top,int bottom,float radius,int edge,float stroke) {
      this(top,bottom,radius,edge,stroke,-1,-1);
    }

    Surface(int top,int bottom,float radius,int edge,float stroke,int width,int height) {
      this.top=top;
      this.bottom=bottom;
      this.radius=radius;
      this.edge=edge;
      this.stroke=stroke;
      this.width=width;
      this.height=height;
    }

    @Override protected void onBoundsChange(Rect b) {
      trace(face,b.left,b.top,b.right,b.bottom,radius);
      if(edge!=0) {
        float s=stroke*.5f;
        trace(border,b.left+s,b.top+s,b.right-s,b.bottom-s,Math.max(0,radius-s));
      }
      gradient=top!=bottom && b.height()>0
          ? new LinearGradient(0,b.top,0,b.bottom,top,bottom,Shader.TileMode.CLAMP) : null;
    }

    @Override public void draw(Canvas canvas) {
      paint.setColorFilter(filter);
      paint.setStyle(Paint.Style.FILL);
      paint.setShader(gradient);
      paint.setColor(gradient==null?top:0xffffffff);
      paint.setAlpha(paint.getAlpha()*alpha/255);
      canvas.drawPath(face,paint);
      if(edge!=0) {
        paint.setShader(null);
        paint.setColor(edge);
        paint.setAlpha(paint.getAlpha()*alpha/255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        canvas.drawPath(border,paint);
      }
    }

    @Override public void getOutline(Outline outline) {
      if(!face.isEmpty()) outline.setConvexPath(face);
      outline.setAlpha(alpha/255f);
    }

    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
    @Override public void setAlpha(int value) { alpha=value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter value) { filter=value; invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }
}
