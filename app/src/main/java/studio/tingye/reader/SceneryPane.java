package studio.tingye.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A cover-cropped photograph shared by the page and its stationary glass surfaces. */
final class SceneryPane extends View {
  static final int DOCK = 1, HEADER = 2, TABS=3, SHEET=4, SIDE_SHEET=5;
  static final String CROSSFADE = "sceneryCrossfade";
  private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "Landscape photographs");
    thread.setDaemon(true);
    return thread;
  });
  private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(28 * 1024) {
    @Override protected int sizeOf(String key, Bitmap bitmap) {
      return bitmap.getAllocationByteCount() / 1024;
    }
  };

  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
  private final RectF target = new RectF();
  private final int[] origin = new int[2];
  private final Bitmap[] textures = new Bitmap[2];
  private final View[] surfaces=new View[6];
  private View card;
  private TextView location,cardLocation;
  private Bitmap[] previous;
  private ValueAnimator blending;
  private int fadeAlpha=255;
  private int requestedScene=-1, visibleScene=-1, captionScene=-1;
  private boolean requestedGlass,shimmer;
  private SceneFrame pending;
  private volatile int generation;

  private static final class SceneFrame {
    final int scene;
    final Bitmap[] photos;
    final boolean animate,glow;
    SceneFrame(int scene,Bitmap[] photos,boolean animate,boolean glow) {
      this.scene=scene;
      this.photos=photos;
      this.animate=animate;
      this.glow=glow;
    }
  }

  SceneryPane(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void bindGlass(View view, int level) {
    if(surfaces[level]==view)return;
    surfaces[level]=view;
    if (view != null) view.setBackground(new Glass(view, level));
  }

  void bindCard(View view) {
    card=view;
    cardLocation=null;
    if(view!=null) view.setBackground(new CardPhoto());
  }

  void bindLocation(TextView label) {
    location=label;
    labelLocation();
  }

  void bindCardLocation(TextView label) {
    cardLocation=label;
    labelLocation();
  }

  private void labelLocation() {
    if(captionScene<0)return;
    String label=FieldArt.SCENE_LABELS[captionScene];
    if(location!=null)location.setText(label);
    if(cardLocation!=null)cardLocation.setText(label);
  }

  void scene(int next, boolean needsGlass, boolean animate,boolean glow) {
    if(next==requestedScene && needsGlass==requestedGlass)return;
    requestedScene=next;
    requestedGlass=needsGlass;
    pending=null;
    int ticket = ++generation;
    if(next==visibleScene && (!needsGlass||textures[DOCK]!=null))return;
    String name = FieldArt.SCENES[next];
    AssetManager assets = getResources().getAssets();
    Bitmap[] ready = cached(name, needsGlass);
    if (ready != null) {
      accept(new SceneFrame(next,ready,animate,glow));
      return;
    }
    LOADER.execute(() -> {
      if (generation != ticket) return;
      try {
        Bitmap[] photos = new Bitmap[2];
        photos[0] = read(assets, name + "/photo");
        if(generation!=ticket)return;
        if (needsGlass) {
          photos[DOCK] = read(assets, name + "/dock");
        }
        post(() -> { if (generation == ticket) accept(new SceneFrame(next,photos,animate,glow)); });
      } catch (IOException e) {
        android.util.Log.e("Slovo", "Landscape unavailable: " + name, e);
        post(()->{ if(generation==ticket)requestedScene=-1; });
      }
    });
  }

  private static Bitmap[] cached(String name, boolean glass) {
    Bitmap base = CACHE.get(name + "/photo");
    if (base == null) return null;
    if (!glass) return new Bitmap[] {base, null};
    Bitmap soft = CACHE.get(name + "/dock");
    return soft==null?null:new Bitmap[]{base,soft};
  }

  private static Bitmap read(AssetManager assets, String key) throws IOException {
    Bitmap cache = CACHE.get(key);
    if (cache != null) return cache;
    try (InputStream input = assets.open("scenery/" + key + ".webp")) {
      Bitmap bitmap = BitmapFactory.decodeStream(input);
      if (bitmap == null) throw new IOException(key);
      bitmap.prepareToDraw();
      CACHE.put(key, bitmap);
      return bitmap;
    }
  }

  private void accept(SceneFrame frame) {
    if(blending!=null) {
      pending=frame;
      return;
    }
    previous=frame.animate && textures[0]!=null && textures[0]!=frame.photos[0]
        ? textures.clone() : null;
    System.arraycopy(frame.photos,0,textures,0,textures.length);
    visibleScene=frame.scene;
    if(previous==null) {
      captionScene=visibleScene;
      labelLocation();
      redraw();
      return;
    }
    shimmer=frame.glow;
    fadeAlpha=0;
    blending=ValueAnimator.ofInt(0,255);
    blending.setDuration(frame.glow?415:320);
    blending.setInterpolator(new android.view.animation.PathInterpolator(.25f,.20f,.40f,1f));
    blending.addUpdateListener(animation->{
      fadeAlpha=(int)animation.getAnimatedValue();
      if(fadeAlpha>=128 && captionScene!=visibleScene) {
        captionScene=visibleScene;
        labelLocation();
      }
      redraw();
    });
    blending.addListener(new AnimatorListenerAdapter() {
      @Override public void onAnimationEnd(Animator animation) {
        previous=null;
        fadeAlpha=255;
        blending=null;
        SceneFrame queued=pending;
        pending=null;
        if(queued!=null && queued.scene==requestedScene)accept(queued);
        else redraw();
      }
    });
    blending.start();
    redraw();
  }

  private void redraw() {
    invalidate();
    for(View surface:surfaces) {
      if(surface!=null && surface.getBackground()!=null)surface.getBackground().invalidateSelf();
    }
    if(card!=null && card.getBackground()!=null)card.getBackground().invalidateSelf();
  }

  private static Bitmap texture(Bitmap[] photos,int level) {
    return photos[level]!=null ? photos[level] : photos[0];
  }

  private void crossfade(Canvas canvas,int level,float x,float y,Paint brush) {
    if(previous!=null) drawPhoto(canvas,texture(previous,level),x,y,brush);
    brush.setAlpha(fadeAlpha);
    drawPhoto(canvas,texture(textures,level),x,y,brush);
    brush.setAlpha(255);
  }

  private void drawPhoto(Canvas canvas, Bitmap image, float x, float y, Paint brush) {
    if (image == null || getWidth() == 0 || getHeight() == 0) return;
    float zoom = Math.max(getWidth() / (float) image.getWidth(),
        getHeight() / (float) image.getHeight());
    float width = image.getWidth() * zoom, height = image.getHeight() * zoom;
    float left = (getWidth() - width) / 2f - x, top = (getHeight() - height) / 2f - y;
    target.set(left, top, left + width, top + height);
    canvas.drawBitmap(image, null, target, brush);
  }

  @Override protected void onDraw(Canvas canvas) {
    canvas.drawColor(0xffced8c9);
    crossfade(canvas,0,0,0,paint);
    canvas.drawColor(0x19f4efdb);
    wash(canvas);
  }

  private void wash(Canvas canvas) {
    if(shimmer && previous!=null)
      canvas.drawColor((32-Math.abs(fadeAlpha*2-255)*32/255)<<24 | 0x00e2f2e7);
  }

  @Override protected void onDetachedFromWindow() {
    generation++;
    requestedScene=-1;
    pending=null;
    if(blending!=null) {
      blending.removeAllListeners();
      blending.cancel();
      blending=null;
    }
    previous=null;
    fadeAlpha=255;
    captionScene=visibleScene;
    super.onDetachedFromWindow();
  }

  private final class Glass extends Drawable {
    private final View view;
    private final int level;
    private final Paint brush = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] position = new int[2];
    private final Path clip = new Path();
    private final RectF panel=new RectF();
    private Shader tint;

    Glass(View view, int level) {
      this.view = view;
      this.level = level;
      rim.setStyle(Paint.Style.STROKE);
      rim.setStrokeWidth(getResources().getDisplayMetrics().density*.95f);
    }

    @Override protected void onBoundsChange(Rect bounds) {
      float dp=getResources().getDisplayMetrics().density;
      panel.set(bounds);
      if(level==TABS||level==DOCK)panel.inset(8*dp,level==DOCK?2*dp:4*dp);
      if(panel.width()<=0||panel.height()<=0)return;
      SoftCorners.trace(clip,panel.left,panel.top,panel.right,panel.bottom,
          level==HEADER?0:((level==SHEET||level==SIDE_SHEET)?21:23)*dp);
      int[] colors;
      if(level==HEADER) colors=new int[]{0xf3fafbf3,0xdef1f6eb,0x72e9f3e9};
      else if(level==TABS) colors=new int[]{0xd0f9faef,0xbcecf4e8,0xe3f2f5ec};
      else if((level==SHEET||level==SIDE_SHEET)) colors=new int[]{0xd5faf9f0,0xc6f6f6ed,0xcbf1f4ea};
      else colors=new int[]{0x84fbfcf2,0xa6e7f0e5,0xc5f3f6ed};
      tint=level==HEADER
          ? new LinearGradient(panel.left,panel.top,panel.right,panel.top,colors,
              new float[]{0,.50f,1},Shader.TileMode.CLAMP)
          : new LinearGradient(panel.left,panel.top,panel.left,panel.bottom,colors,null,Shader.TileMode.CLAMP);
      rim.setShader(new LinearGradient(panel.left,panel.top,panel.right,panel.bottom,
          new int[]{0xecffffff,0x44ffffff,0x53718073,0xbdffffff},null,Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      if (bounds.isEmpty()||panel.width()<=0||panel.height()<=0) return;
      view.getLocationInWindow(position);
      getLocationInWindow(origin);
      int saved = canvas.save();
      canvas.clipPath(clip);
      if(level==HEADER) {
        if(previous!=null)headerPhoto(canvas,previous[0],255);
        headerPhoto(canvas,textures[0],fadeAlpha);
      } else crossfade(canvas,DOCK,position[0]-origin[0],position[1]-origin[1],brush);
      brush.setShader(tint);
      canvas.drawRect(panel, brush);
      brush.setShader(null);
      wash(canvas);
      canvas.restoreToCount(saved);
      if(level==HEADER) {
        brush.setColor(0x7f789387);
        canvas.drawRect(panel.left,panel.bottom-getResources().getDisplayMetrics().density,
            panel.right,panel.bottom,brush);
        brush.setColor(0xffffffff);
      } else canvas.drawPath(clip,rim);
    }

    private void headerPhoto(Canvas canvas,Bitmap photo,int opacity) {
      if(photo==null)return;
      float scale=Math.max(panel.width()/photo.getWidth(),panel.height()/photo.getHeight());
      float height=photo.getHeight()*scale;
      float top=panel.top-(height-panel.height())*.47f;
      target.set(panel.left,top,panel.left+photo.getWidth()*scale,top+height);
      brush.setAlpha(opacity);
      canvas.drawBitmap(photo,null,target,brush);
      brush.setAlpha(255);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }

  private final class CardPhoto extends Drawable {
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF area=new RectF(),crop=new RectF();
    private final Path mask=new Path();
    private Shader shade;

    @Override protected void onBoundsChange(Rect bounds) {
      area.set(bounds);
      float radius=16*getResources().getDisplayMetrics().density;
      SoftCorners.trace(mask,area.left,area.top,area.right,area.bottom,radius);
      shade=new LinearGradient(0,area.top,0,area.bottom,0x57223838,0xc5172a28,Shader.TileMode.CLAMP);
    }

    private void photo(Canvas canvas,Bitmap image,int alpha) {
      if(image==null) return;
      float factor=Math.max(area.width()/image.getWidth(),area.height()/image.getHeight());
      float width=image.getWidth()*factor,height=image.getHeight()*factor;
      crop.set(area.centerX()-width*.5f,area.centerY()-height*.53f,
          area.centerX()+width*.5f,area.centerY()+height*.47f);
      if(crop.bottom<area.bottom) crop.offset(0,area.bottom-crop.bottom);
      brush.setAlpha(alpha);
      canvas.drawBitmap(image,null,crop,brush);
    }

    @Override public void draw(Canvas canvas) {
      if(area.isEmpty()) return;
      int saved=canvas.save();
      canvas.clipPath(mask);
      canvas.drawColor(0xff2b4945);
      if(previous!=null) photo(canvas,previous[0],255);
      photo(canvas,textures[0],fadeAlpha);
      brush.setAlpha(255);
      brush.setShader(shade);
      canvas.drawRect(area,brush);
      brush.setShader(null);
      canvas.restoreToCount(saved);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }
}

