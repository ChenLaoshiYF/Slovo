package studio.tingye.reader;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/** A single crop of the river photograph travels between the two real view bounds. */
final class PhotoTransition extends Drawable {
  private final ViewGroup host;
  private final RectF bounds = new RectF();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Path contour=new Path();
  private final Matrix matrix = new Matrix();
  private final Bitmap photo;
  private final BitmapShader texture;
  private final float radius;
  private View target;
  private ValueAnimator motion;

  PhotoTransition(ViewGroup host) {
    this.host = host;
    photo = FieldArt.bitmap(host.getResources(), FieldArt.LAND[4]);
    texture = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    radius = 16 * host.getResources().getDisplayMetrics().density;
  }

  RectF capture(View view) {
    if (motion != null) return new RectF(bounds);
    if (view == null || !view.isShown()) return null;
    Rect rect = new Rect();
    if (!view.getLocalVisibleRect(rect)) return null;
    host.offsetDescendantRectToMyCoords(view, rect);
    return new RectF(rect);
  }

  void cancel() {
    if (motion != null) motion.cancel();
    motion = null;
    host.getOverlay().remove(this);
    if (target != null) target.setAlpha(1f);
    target = null;
  }

  void move(RectF from, View destination) {
    if (from == null || destination == null || !ValueAnimator.areAnimatorsEnabled()) return;
    target = destination;
    destination.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
      public boolean onPreDraw() {
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(this);
        if (target != destination || !destination.isAttachedToWindow()) return true;
        RectF to = capture(destination);
        if (to == null) { cancel(); return true; }
        setBounds(0, 0, host.getWidth(), host.getHeight());
        bounds.set(from);
        destination.setAlpha(0);
        host.getOverlay().add(PhotoTransition.this);
        motion = ValueAnimator.ofFloat(0, 1);
        motion.setDuration(245);
        motion.setInterpolator(PaperActivity.SETTLE);
        motion.addUpdateListener(a -> {
          float t = (float)a.getAnimatedValue();
          bounds.set(from.left + (to.left - from.left)*t, from.top + (to.top - from.top)*t,
              from.right + (to.right - from.right)*t, from.bottom + (to.bottom - from.bottom)*t);
          float blend = Math.min(1, Math.max(0, (t - .50f) * 2f));
          paint.setAlpha(Math.round(255 * (1 - blend)));
          destination.setAlpha(blend);
          invalidateSelf();
          if (t >= 1) {
            host.getOverlay().remove(PhotoTransition.this);
            motion = null;
            target = null;
          }
        });
        paint.setAlpha(255);
        motion.start();
        return true;
      }
    });
  }

  @Override public void draw(Canvas canvas) {
    float scale = Math.max(bounds.width()/photo.getWidth(), bounds.height()/photo.getHeight());
    matrix.setScale(scale, scale);
    matrix.postTranslate(bounds.centerX() - photo.getWidth()*scale*.5f,
        bounds.centerY() - photo.getHeight()*scale*.5f);
    texture.setLocalMatrix(matrix);
    paint.setShader(texture);
    SoftCorners.trace(contour,bounds.left,bounds.top,bounds.right,bounds.bottom,radius);
    canvas.drawPath(contour,paint);
  }
  @Override public void setAlpha(int value) { paint.setAlpha(value); }
  @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
