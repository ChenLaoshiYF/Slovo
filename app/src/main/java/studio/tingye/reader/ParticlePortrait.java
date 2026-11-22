package studio.tingye.reader;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.util.*;

/** A deforming, perspective-projected surface of particles with selectable vocabulary anchors. */
final class ParticlePortrait extends View {
  interface Pick {
    void open(StudyStore.Card card);
  }

  private static final int BANDS = 8;
  static final String HIGH_QUALITY = "particleHighQuality";
  // One periodic table serves all particles. Interpolation keeps slow motion continuous.
  private static final int CYCLE = 4096;
  private static final float[] SINE = new float[CYCLE + 1];
  static {
    for (int i = 0; i <= CYCLE; i++) SINE[i] = (float) Math.sin(i * 2 * Math.PI / CYCLE);
  }
  private static float sine(double radians) {
    double pos = radians * (CYCLE / (2 * Math.PI));
    int base = (int) Math.floor(pos), k = base & (CYCLE - 1);
    return SINE[k] + (SINE[k + 1] - SINE[k]) * (float)(pos - base);
  }
  private final int particleCount;
  private final boolean highQuality;
  private final float[] lat, ring, angle, wave, vz, px, py;
  private final float[][] points;
  private final int[] count = new int[BANDS], colors = new int[BANDS];
  private final float[] traits = new float[3], habits = new float[3];
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Rect visible = new Rect();
  private final ArrayList<StudyStore.Card> nodes = new ArrayList<>();
  private final int[] anchors;
  private final Pick pick;
  private final float dp, seed;
  private final boolean lowPower;
  private Bitmap still;
  private final Paint copy = new Paint();
  private int capturedWidth, capturedHeight;
  private float yaw = .16f,
      tilt = .28f,
      lastX,
      lastY,
      downX,
      downY,
      cosY,
      sinY,
      cosT,
      sinT,
      scale,
      cx,
      cy;
  private Bitmap layer;
  private Canvas layerCanvas;
  private final Rect layerBounds = new Rect();
  private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final float[] streamLines = new float[72 * 4];
  private boolean scheduled;
  private VelocityTracker velocity;
  private boolean dragging;
  private float spinVelocity, tiltVelocity;
  private final android.database.ContentObserver motionSetting =
      new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
          previous = 0;
          invalidate();
        }
      };
  private final Runnable frame =
      () -> {
        scheduled = false;
        invalidate();
      };

  private double time;
  private long previous;
  private final float[] projected = new float[3];

  ParticlePortrait(Context context, StudyStore store, int[] preferences, int identity,
      boolean highQuality, Pick pick) {
    super(context);
    this.pick = pick;
    dp = getResources().getDisplayMetrics().density;
    lowPower = ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode();
    this.highQuality = highQuality && !lowPower;
    particleCount = this.highQuality ? 1960 : 1060;
    lat = new float[particleCount];
    ring = new float[particleCount];
    angle = new float[particleCount];
    wave = new float[particleCount];
    vz = new float[particleCount];
    px = new float[particleCount];
    py = new float[particleCount];
    points = new float[BANDS][2*particleCount];
    seed = (identity & 0xffff) / 10430f;
    setContentDescription("三维粒子像"+(this.highQuality?"，高画质":"")+"，横滑旋转，点亮点查看单词");
    int seen = 0, repeats = 0;
    for (StudyStore.Card c : store.cards.values()) {
      if (c.seen > 0) {
        seen++;
        repeats += Math.min(6, c.seen - 1);
      }
      nodes.add(c);
    }
    nodes.sort((a, b) -> Long.compare(b.last, a.last));
    if (nodes.size() > 24) nodes.subList(24, nodes.size()).clear();
    habits[0] = (float) Math.tanh(seen / 55.0);
    habits[1] = Math.min(1, repeats / (float) Math.max(1, seen * 2));
    habits[2] = (float) Math.tanh(store.practiceDays() / 8.0);
    for (int k = 0; k < 3; k++) setPreference(k, preferences[k]);
    for (int i = 0; i < particleCount; i++) {
      lat[i] = 1 - 2 * (i + .5f) / particleCount;
      ring[i] = (float) Math.sqrt(1 - lat[i] * lat[i]);
      angle[i] = (float) ((i * 2.39996323 + seed) % (2 * Math.PI));
      wave[i] = (float) Math.sin(i * 1.39);
    }
    anchors = new int[nodes.size()];
    HashSet<Integer> used = new HashSet<>();
    for (int i = 0; i < anchors.length; i++) {
      int h = Math.floorMod(nodes.get(i).k.hashCode(), particleCount - 90) + 45;
      while (!used.add(h)) h = (h + 37) % particleCount;
      anchors[i] = h;
    }
    for (int i = 0; i < BANDS; i++)
      colors[i] = Color.argb(80 + i * 23, 85 + i * 20, 180 + i * 10, 184 + i * 10);
    setFocusable(true);
  }

  void setPreference(int axis, int value) {
    traits[axis] = .68f * value / 100f + .32f * habits[axis];
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    if (w < 1 || h < 1) return;
    float raster = Math.min(1f, (highQuality ? 780f : 480f) / Math.max(w, h));
    layer =
        Bitmap.createBitmap(
            Math.max(1, Math.round(w * raster)),
            Math.max(1, Math.round(h * raster)),
            Bitmap.Config.ARGB_8888);
    layerCanvas = new Canvas(layer);
    layerCanvas.scale(layer.getWidth() / (float) w, layer.getHeight() / (float) h);
    layer.setHasAlpha(false);
    layerBounds.set(0, 0, w, h);
    cx = w * .5f;
    cy = h * .48f;
    scale = Math.min(w * .28f, h * .31f);
    still=null;
  }

  @Override public boolean isOpaque() { return layer!=null; }

  private void prepareBackdrop(ViewGroup parent) {
    capturedWidth=parent.getWidth();
    capturedHeight=parent.getHeight();
    if (still==null) still=Bitmap.createBitmap(layer.getWidth(),layer.getHeight(),Bitmap.Config.ARGB_8888);
    still.setHasAlpha(false);
    Canvas glow = new Canvas(still);
    glow.drawColor(0xff233d3b);
    glow.scale(layer.getWidth()/(float)getWidth(),layer.getHeight()/(float)getHeight());
    if (parent.getBackground() instanceof PaperActivity.PortraitPaper) {
      FieldArt river=((PaperActivity.PortraitPaper)parent.getBackground()).art;
      river.setBounds(0,0,capturedWidth,capturedHeight);
      int saved=glow.save();
      glow.translate(-getLeft(),-getTop());
      river.draw(glow);
      glow.restoreToCount(saved);
    }
    Paint wash = new Paint();
    wash.setShader(new RadialGradient(
              cx,
              cy,
              Math.max(1, scale * 1.65f),
              new int[] {0x5043b9b6, 0x253b9e99, 0},
              null,
              Shader.TileMode.CLAMP));
    glow.drawRect(0,0,getWidth(),getHeight(),wash);
    copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
  }

  private void project(float x, float y, float z, float[] out) {
    float a = x * cosY + z * sinY, b = -x * sinY + z * cosY;
    float yy = y * cosT - b * sinT, zz = y * sinT + b * cosT;
    float perspective = 1f / (1 - .14f * zz);
    out[0] = cx + a * scale * perspective;
    out[1] = cy - yy * scale * perspective;
    out[2] = zz;
  }

  private void axis(Canvas c, float x, float y, float z, String label) {
    project(-x, -y, -z, projected);
    float ex = projected[0], ey = projected[1];
    project(x, y, z, projected);
    p.setColor(0x5cc4e6d9);
    p.setStrokeWidth(.75f * dp);
    c.drawLine(ex, ey, projected[0], projected[1], p);
    p.setColor(0xffd1e7df);
    p.setTextSize(11 * dp);
    float tx =
        Math.max(
            6 * dp, Math.min(getWidth() - p.measureText(label) - 6 * dp, projected[0] + 3 * dp));
    c.drawText(
        label, tx, Math.min(getHeight() - 8 * dp, Math.max(16 * dp, projected[1] - 3 * dp)), p);
  }

  private void surface(float l, float r, float a, double phase) {
    double fold = a * 3 + l * (2.2 + traits[0] * 2) + phase + seed;
    float radius =
        r
            * (.79f
                + .19f * sine(fold)
                + .085f * sine(2 * a - 3 * l - phase + Math.PI / 2));
    double u = a + .60 * l + .13 * sine(phase + l * 4);
    float x =
        radius * sine(u + Math.PI / 2) * (1.10f + traits[0] * .26f)
            + .20f * sine(3 * l + phase * .6);
    float y = l * (.85f + traits[1] * .4f) + .14f * r * sine(2 * a + phase);
    float z = radius * sine(u) + .13f * sine(4 * l + a - phase);
    project(x, y, z, projected);
  }

  private void currents(Canvas c, double phase) {
    p.setStrokeCap(Paint.Cap.ROUND);
    for (int band = 0; band < 4; band++) {
      float lastX = 0, lastY = 0, lastDepth = -2;
      int used = 0;
      for (int j = 0; j <= 72; j++) {
        float a = (float) (2 * Math.PI * j / 72);
        float l = (float) (-.42 + band * .27 + .22 * Math.sin(a * 2 + phase * .36 + band * .8));
        surface(l, (float) Math.sqrt(1 - l * l), a, phase);
        float depth = projected[2];
        if (j > 0 && depth > .02f && lastDepth > .02f) {
          streamLines[used++] = lastX;
          streamLines[used++] = lastY;
          streamLines[used++] = projected[0];
          streamLines[used++] = projected[1];
        }
        lastX = projected[0];
        lastY = projected[1];
        lastDepth = depth;
      }
      p.setColor(band % 2 == 0 ? 0x40a4f8e6 : 0x40b0c1ff);
      p.setStrokeWidth(dp);
      c.drawLines(streamLines, 0, used, p);
    }
  }

  @Override
  protected void onDraw(Canvas screen) {
    if (layerCanvas == null) return;
    // Rasterise this small moving layer once. Software GPUs otherwise expand round points
    // to hundreds of separate draws. The scenery and controls keep their native resolution.
    Canvas c = layerCanvas;
    ViewGroup card = (ViewGroup)getParent();
    if (still==null || capturedWidth!=card.getWidth() || capturedHeight!=card.getHeight())
      prepareBackdrop(card);
    c.drawBitmap(still, null, layerBounds, copy);
    long now = SystemClock.uptimeMillis();
    boolean moving = ValueAnimator.areAnimatorsEnabled() && !lowPower;
    if (moving && previous != 0) {
      float dt = Math.min(50, now - previous) * .001f;
      time += dt * .65;
      if (!dragging && (spinVelocity != 0 || tiltVelocity != 0)) {
        float decay = (float)Math.exp(-8f * dt);
        yaw += spinVelocity * (1 - decay) / 8f;
        tilt = Math.max(-1.2f, Math.min(1.2f, tilt + tiltVelocity * (1 - decay) / 8f));
        spinVelocity *= decay;
        tiltVelocity *= decay;
        if (Math.abs(spinVelocity) + Math.abs(tiltVelocity) < .025f) spinVelocity = tiltVelocity = 0;
      }
    } else if (!moving) spinVelocity = tiltVelocity = 0;
    previous = now;
    p.setColor(-1);
    float spin = yaw + (float) Math.sin(time * .5) * .18f + .52f;
    cosY = (float) Math.cos(spin);
    sinY = (float) Math.sin(spin);
    cosT = (float) Math.cos(tilt + .22f);
    sinT = (float) Math.sin(tilt + .22f);
    axis(c, 1.44f, 0, 0, "X 探索");
    axis(c, 0, 1.38f, 0, "Y 沉浸");
    axis(c, 0, 0, 1.5f, "Z 节奏");
    Arrays.fill(count, 0);
    double phase = time * (.50 + traits[2] * 1.1);
    for (int i = 0; i < particleCount; i++) {
      float l = lat[i], r = ring[i], a = angle[i];
      surface(l, r, a, phase);
      px[i] = projected[0];
      py[i] = projected[1];
      vz[i] = projected[2];
      int bucket = Math.max(0, Math.min(BANDS - 1, (int) ((vz[i] + 1.15f) * 3.2f + wave[i] * .6f)));
      int n = count[bucket];
      points[bucket][n] = px[i];
      points[bucket][n + 1] = py[i];
      count[bucket] += 2;
    }
    p.setStrokeCap(Paint.Cap.ROUND);
    for (int b = 0; b < BANDS; b++) {
      if (b >= 6) {
        p.setColor(0x2456ddce);
        p.setStrokeWidth(dp * (3.5f + b * .25f));
        c.drawPoints(points[b], 0, count[b], p);
      }
      p.setColor(colors[b]);
      p.setStrokeWidth(dp * (1.0f + b * .25f));
      c.drawPoints(points[b], 0, count[b], p);
    }
    currents(c, phase);
    long wall = System.currentTimeMillis();
    for (int j = 0; j < anchors.length; j++) {
      int k = anchors[j];
      if (vz[k] < -.1f) continue;
      boolean due = nodes.get(j).due <= wall;
      p.setColor(due ? 0x60ffc586 : 0x558bf6ec);
      c.drawCircle(px[k], py[k], 6.5f * dp, p);
      p.setColor(due ? 0xffffd596 : 0xffbefff0);
      c.drawCircle(px[k], py[k], 2.8f * dp, p);
    }
    screen.drawBitmap(layer, null, layerBounds, blit);
    if (!scheduled && moving && hasWindowFocus() && isShown() && getLocalVisibleRect(visible)) {
      scheduled = true;
      if (highQuality || dragging || spinVelocity != 0 || tiltVelocity != 0) postOnAnimation(frame);
      else postOnAnimationDelayed(frame, 16);
    }
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    removeCallbacks(frame);
    scheduled = false;
    previous = 0;
    if (!focused) stopDrag();
    if (focused) invalidate();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    getContext()
        .getContentResolver()
        .registerContentObserver(
            android.provider.Settings.Global.getUriFor(
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            motionSetting);
  }

  @Override
  protected void onDetachedFromWindow() {
    getContext().getContentResolver().unregisterContentObserver(motionSetting);
    removeCallbacks(frame);
    scheduled = false;
    stopDrag();
    super.onDetachedFromWindow();
  }

  private void stopDrag() {
    if (velocity != null) { velocity.recycle(); velocity = null; }
    dragging = false;
    spinVelocity = tiltVelocity = 0;
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    float x = e.getX(), y = e.getY();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        stopDrag();
        velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        downX = lastX = x;
        downY = lastY = y;
        return true;
      case MotionEvent.ACTION_MOVE:
        if (velocity != null) velocity.addMovement(e);
        if (!dragging && Math.abs(x - downX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
            && Math.abs(x - downX) > Math.abs(y - downY)) {
          dragging = true;
          getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!dragging) return true;
        yaw += (x - lastX) * 5.2f / Math.max(1, getWidth());
        tilt = Math.max(-1.2f, Math.min(1.2f, tilt + (y - lastY) * 3.8f / Math.max(1, getHeight())));
        lastX = x;
        lastY = y;
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
        if (dragging && velocity != null && !lowPower && ValueAnimator.areAnimatorsEnabled()) {
          velocity.addMovement(e);
          velocity.computeCurrentVelocity(1000);
          spinVelocity = Math.max(-4f, Math.min(4f, velocity.getXVelocity() * 5.2f / Math.max(1,getWidth())));
          tiltVelocity = Math.max(-3f, Math.min(3f, velocity.getYVelocity() * 3.8f / Math.max(1,getHeight())));
        }
        if (velocity != null) { velocity.recycle(); velocity = null; }
        boolean wasDrag = dragging;
        dragging = false;
        if (!wasDrag && Math.hypot(x - downX, y - downY) < 8 * dp) {
          performClick();
          double best = 22 * dp;
          int selected = -1;
          for (int i = 0; i < anchors.length; i++) {
            int k = anchors[i];
            double d = Math.hypot(x - px[k], y - py[k]);
            if (vz[k] >= -.1f && d < best) {
              best = d;
              selected = i;
            }
          }
          if (selected >= 0) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            pick.open(nodes.get(selected));
          }
        }
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      case MotionEvent.ACTION_CANCEL:
        stopDrag();
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
