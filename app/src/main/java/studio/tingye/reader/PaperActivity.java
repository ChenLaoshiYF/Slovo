package studio.tingye.reader;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;

abstract class PaperActivity extends Activity {
  static final int paper = 0xfff5f3ec,
      ink = 0xff203a36,
      blue = 0xff255651,
      muted = 0xff54655e,
      line = 0xffd5dad0,
      white = 0xfffdfbf5,
      hot = 0xffaf462d;
  static final int[] pigments = {0xff38527e, 0xffbb4e32, 0xff487168, 0xff80632f, 0xff655183};
  static final int[] stocks = {0xffe1e8f1, 0xfffae2cf, 0xffdfebe4, 0xfff4e6bc, 0xffeae2f2};
  LinearLayout shell, body, bar, nav;
  private LinearLayout root;
  private SceneryPane scenery;
  private final SceneRotation rotation=new SceneRotation();
  private String themedScreen="";
  private int themeScene, fixedScene=Integer.MIN_VALUE;
  private final java.util.WeakHashMap<View,Boolean> fitted = new java.util.WeakHashMap<>();
  FrameLayout stage;
  private FrameLayout tabTrack;
  private View indicator, tabBorder;
  private int activeTab = -1;
  private final ImageView[] tabIcons = new ImageView[PaperActivity.TAB_IDS.length];
  private final TextView[] tabLabels = new TextView[PaperActivity.TAB_IDS.length];
  static final String[] TAB_IDS = {"role_chat", "home", "shelf", "search", "atlas"};
  static final PathInterpolator SETTLE = new PathInterpolator(.18f, .78f, .22f, 1f);
  static final PathInterpolator RELEASE = new PathInterpolator(.18f, 1.24f, .30f, 1f);
  ScrollView scroll;
  ScrollView sideScroll;
  LinearLayout sideBody;
  private LinearLayout workspace;
  int place;

  boolean tablet() { return getResources().getConfiguration().screenWidthDp>=600; }

  /** Open a second reading column only when both columns remain comfortable to read. */
  LinearLayout secondary() {
    if(getResources().getConfiguration().screenWidthDp<840)return body;
    workspace.getLayoutParams().width=-1;
    workspace.requestLayout();
    sideScroll.setVisibility(View.VISIBLE);
    return sideBody;
  }

  void touchAction(View v, Runnable action) {
    if (v.getBackground() instanceof FieldArt) {
      v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x38effff3),null,surface(white,16,0)));
      v.setFocusable(true);
    }
    v.setOnClickListener(
        x -> {
          x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          onContentAction();
          action.run();
        });
  }

  void onContentAction() {}

  int themeScene(String screen) {
    android.content.SharedPreferences prefs=getPreferences(0);
    int chosen=prefs.getInt(FieldArt.SCENE_PREF,-1);
    if (chosen<0 || chosen>=FieldArt.SCENES.length) chosen=-1;
    if (!screen.equals(themedScreen) || fixedScene!=chosen) {
      themeScene=chosen>=0?chosen:rotation.next(prefs);
      themedScreen=screen;
      fixedScene=chosen;
    }
    return themeScene;
  }

  void nextStudyScenery() {
    themedScreen="";
  }

  void finishTheme(String screen) {
    if (scenery == null) return;
    int scene=themeScene(screen);
    FrameLayout headerFrame=(FrameLayout)bar.getParent();
    boolean home = screen.equals("home");
    if(!home) scenery.bindCard(null);
    scenery.bindGlass(headerFrame, SceneryPane.HEADER);
    scenery.bindGlass(shell.findViewWithTag("scenery.dock"), SceneryPane.DOCK);
    scenery.bindGlass(tabTrack,SceneryPane.TABS);
    scenery.bindGlass(body,SceneryPane.SHEET);
    if(sideBody!=null && sideScroll.getVisibility()==View.VISIBLE)scenery.bindGlass(sideBody,SceneryPane.SIDE_SHEET);
    android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
    boolean animate=ValueAnimator.areAnimatorsEnabled() && !power.isPowerSaveMode();
    boolean glow=getPreferences(0).getBoolean(SceneryPane.CROSSFADE,false);
    scenery.bindLocation(bar.findViewWithTag("scenery.place"));
    scenery.scene(scene,true,animate,glow);
    if (!fitted.containsKey(shell)) {
      fitControls(shell);
      fitted.put(shell,true);
    }
  }

  void scenicCard(View card) {
    scenery.bindCard(card);
  }

  void scenicCardLocation(TextView label) {
    scenery.bindCardLocation(label);
  }

  private void fitControls(View v) {
    TideControls.style(v);
    if (v instanceof ViewGroup) {
      ViewGroup group=(ViewGroup)v;
      for (int i=0;i<group.getChildCount();i++) fitControls(group.getChildAt(i));
    }
  }

  void scenicDock(View controls) {
    controls.setTag("scenery.dock");
    scenery.bindGlass(controls, SceneryPane.DOCK);
  }

  void joinedGlass(View view, boolean primary) {
    Drawable coating = TideControls.surface(this,
        primary ? 0xd4315950 : 0x98fffff6,
        primary ? 0xe51b3934 : 0x90d5e3d9,
        24, primary ? 0x88edffed : 0xccfffff6);
    view.setBackground(new RippleDrawable(
        ColorStateList.valueOf(primary ? 0x49ecfff6 : 0x43265043),
        coating, surface(0xffffffff, 24, 0)));
  }

  void glass(View v, boolean deep, Runnable action) {
    FieldArt water = new FieldArt(getResources(), place, deep ? FieldArt.DARK : FieldArt.LIGHT, 25);
    v.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(deep ? 0x55e7ffff : 0x44365050),
            water,
            surface(0xffffffff, 25, 0)));
    touchAction(v, action);
    pressMotion(v);
    v.setFocusable(true);
  }

  private void pressMotion(View v) {
    StateListAnimator states = new StateListAnimator();
    for (boolean down : new boolean[] {true, false}) {
      float size = down ? .965f : 1f;
      ObjectAnimator scale = ObjectAnimator.ofPropertyValuesHolder(v,
          PropertyValuesHolder.ofFloat(View.SCALE_X, size),
          PropertyValuesHolder.ofFloat(View.SCALE_Y, size));
      scale.setDuration(down ? 58 : 185);
      scale.setInterpolator(down ? SETTLE : RELEASE);
      states.addState(down ? new int[] {android.R.attr.state_pressed} : new int[0], scale);
    }
    v.setStateListAnimator(states);
    v.jumpDrawablesToCurrentState();
  }

  void arrive(View v, float dx, float dy, int duration) {
    if (!ValueAnimator.areAnimatorsEnabled()) return;
    v.animate().cancel();
    v.setTranslationX(dp(dx));
    v.setTranslationY(dp(dy));
    v.animate().translationX(0).translationY(0).setDuration(duration).setInterpolator(SETTLE).start();
  }

  void landscape(View v, int location, int mode, int curve) {
    FieldArt photo=new FieldArt(getResources(),location,mode,curve);
    if(mode==FieldArt.NIGHT && v instanceof ViewGroup)
      v.setBackground(new PortraitPaper((ViewGroup)v,photo));
    else v.setBackground(photo);
  }

  /** The moving portrait owns its opaque rectangle; its parent only draws the caption strip. */
  static final class PortraitPaper extends Drawable {
    final FieldArt art;
    private final ViewGroup owner;
    PortraitPaper(ViewGroup owner,FieldArt art) {
      this.owner=owner; this.art=art;
    }
    @Override protected void onBoundsChange(Rect b){art.setBounds(b);}
    @Override public void draw(Canvas c) {
      View graph=owner.getChildCount()==0 ? null : owner.getChildAt(0);
      int save=c.save();
      if(graph instanceof ParticlePortrait && graph.getVisibility()==View.VISIBLE && graph.isOpaque())
        c.clipRect(0,graph.getBottom(),owner.getWidth(),owner.getHeight());
      art.draw(c);
      c.restoreToCount(save);
    }
    @Override public void setAlpha(int a){art.setAlpha(a);}
    @Override public void setColorFilter(ColorFilter f){art.setColorFilter(f);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(paper);
    getWindow().setNavigationBarColor(paper);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  int dp(float x) {
    return Math.round(x * getResources().getDisplayMetrics().density);
  }

  LinearLayout box(boolean vertical) {
    return box(vertical,0);
  }

  LinearLayout box(boolean vertical,int clippedRadius) {
    LinearLayout v = new LinearLayout(this) {
      private final Path contour=clippedRadius==0?null:new Path();
      @Override public void onViewAdded(View child) {
        super.onViewAdded(child);
        TideControls.style(child);
      }
      @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        super.onSizeChanged(w,h,oldW,oldH);
        if(contour!=null)SoftCorners.trace(contour,0,0,w,h,dp(clippedRadius));
      }
      @Override protected void dispatchDraw(Canvas canvas) {
        if(contour==null) { super.dispatchDraw(canvas); return; }
        int save=canvas.save();
        canvas.clipPath(contour);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
      }
    };
    v.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    if (!vertical) v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  void pad(View v, int l, int t, int r, int b) {
    v.setPadding(dp(l), dp(t), dp(r), dp(b));
  }

  Drawable surface(int color, int radius, int edge) {
    return TideControls.surface(this,color,color,radius,edge);
  }

  void click(View v, int color, int radius, int edge, Runnable run) {
    v.setBackground(color == paper || color == white
        ? v instanceof TextView ? TideControls.chip(this) : TideControls.row(this,radius)
        : new RippleDrawable(ColorStateList.valueOf(color == blue ? 0x55ffffff : 0x32446e5e),
              surface(color,radius,edge),surface(white,radius,0)));
    touchAction(v, run);
    v.setFocusable(true);
  }

  TextView txt(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    t.setIncludeFontPadding(false);
    t.setGravity(Gravity.CENTER_VERTICAL);
    return t;
  }

  TextView serif(String s, int size, int color) {
    TextView t = txt(s, size, color, false);
    t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    return t;
  }

  void add(LinearLayout p, View v, int height) {
    p.addView(v, new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height)));
  }

  void wide(LinearLayout p, View v) {
    p.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
  }

  void gap(LinearLayout p, int n) {
    View v = new View(this);
    p.addView(
        v,
        new LinearLayout.LayoutParams(
            p.getOrientation() == LinearLayout.VERTICAL ? 1 : dp(n),
            p.getOrientation() == LinearLayout.VERTICAL ? dp(n) : 1));
  }

  void rule(LinearLayout p) {
    View v = new View(this);
    v.setBackgroundColor(0x668a9c88);
    p.addView(v, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  ImageView icon(int glyph, int color) {
    ImageView i = new ImageView(this);
    i.setImageResource(glyph);
    i.setColorFilter(color);
    i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return i;
  }

  void sym(LinearLayout row, int glyph, int color, int size) {
    row.addView(icon(glyph, color), new LinearLayout.LayoutParams(dp(size), dp(size)));
  }

  View action(String label, int glyph, boolean primary, Runnable run) {
    LinearLayout v = box(false);
    v.setMinimumHeight(dp(52));
    v.setGravity(Gravity.CENTER);
    glass(v, primary, run);
    pad(v, 9, 10, 9, 10);
    if (glyph != 0) {
      sym(v, glyph, primary ? white : blue, 20);
      gap(v, 8);
    }
    v.addView(txt(label, 16, primary ? white : ink, true));
    v.setContentDescription(label);
    return v;
  }

  ImageButton iconButton(int glyph, String label, Runnable run) {
    ImageButton b = new ImageButton(this);
    b.setImageResource(glyph);
    b.setColorFilter(ink);
    pad(b, 12, 12, 12, 12);
    b.setContentDescription(label);
    glass(b, false, run);
    return b;
  }

  void title(String label, String aux) {
    gap(body, 16);
    add(body, txt(label, 31, ink, true), -2);
    if (!aux.isEmpty()) {
      gap(body, 5);
      add(body, txt(aux, 14, muted, false), -2);
    }
    gap(body, 20);
  }

  void section(String left, String right) {
    section(body, left, right);
  }

  void section(LinearLayout host, String left, String right) {
    gap(host, 17);
    LinearLayout r = box(false);
    wide(r, txt(left, 18, ink, true));
    r.addView(txt(right, 12, muted, false));
    add(host, r, -2);
    gap(host, 10);
  }

  void row(LinearLayout p, String name, String desc, int glyph, Runnable open) {
    LinearLayout r = box(false);
    click(r, paper, 3, 0, open);
    pad(r, 13, 10, 12, 10);
    r.setMinimumHeight(dp(62));
    if(glyph!=R.drawable.s_arrow) {
      sym(r, glyph, blue, 21);
      gap(r, 12);
    }
    LinearLayout stack = box(true);
    add(stack, txt(name, 16, ink, true), -2);
    if (!desc.isEmpty()) {
      gap(stack, 4);
      add(stack, txt(desc, 13, muted, false), -2);
    }
    wide(r, stack);
    sym(r, R.drawable.s_arrow, muted, 16);
    add(p, r, -2);
    rule(p);
  }

  void layout(String name, boolean back, Runnable backFn, Runnable capture) {
    if (root == null) {
      root = box(true);
      FrameLayout layers = new FrameLayout(this);
      scenery = new SceneryPane(this);
      layers.addView(scenery,new FrameLayout.LayoutParams(-1,-1));
      layers.addView(root,new FrameLayout.LayoutParams(-1,-1));
      stage = new FrameLayout(this);
      root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
      setContentView(layers);
    }
    if (shell != null) {
      shell.animate().cancel();
      if (shell.getVisibility() != View.INVISIBLE) stage.removeView(shell);
    }
    shell = box(true);
    shell.setBackgroundColor(Color.TRANSPARENT);
    shell.setFocusableInTouchMode(true);
    stage.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    if (back) showTabs(false);
    FrameLayout headerFrame=new FrameLayout(this);
    bar = box(false);
    pad(bar, 16, 0, 16, 0);
    headerFrame.addView(bar,new FrameLayout.LayoutParams(-1,-1));
    add(shell,headerFrame,60);
    if (back) {
      bar.addView(iconButton(R.drawable.s_back, "返回", backFn), dp(48), dp(48));
      gap(bar,7);
    }
    LinearLayout titles=box(true);
    TextView heading=txt(name,back ? 18 : 21,ink,true);
    heading.setSingleLine(true);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    TextView caption=txt("",12,muted,false);
    caption.setSingleLine(true);
    caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
    caption.setTag("scenery.place");
    caption.setPadding(0,dp(3),0,0);
    add(titles,heading,-2); add(titles,caption,-2);
    wide(bar,titles);
    if (capture != null)
      bar.addView(iconButton(R.drawable.s_camera, "拍照取词", capture), dp(48), dp(48));
    scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(false);
    FrameLayout readingArea=new FrameLayout(this);
    shell.addView(readingArea,new LinearLayout.LayoutParams(-1,0,1));
    workspace=box(false);
    workspace.setGravity(Gravity.TOP);
    int width=getResources().getConfiguration().screenWidthDp;
    FrameLayout.LayoutParams columns=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER_HORIZONTAL);
    readingArea.addView(workspace,columns);
    workspace.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
    body = box(true);
    pad(body,12,3,12,16);
    body.setBackground(TideControls.surface(this,0xe9faf8ef,0xe2f2f3e9,16,0x79ffffff));
    FrameLayout.LayoutParams sheet=new FrameLayout.LayoutParams(-1,-2);
    sheet.setMargins(dp(8),dp(7),dp(8),dp(8));
    scroll.addView(body,sheet);
    sideScroll=new ScrollView(this);
    sideScroll.setVerticalScrollBarEnabled(false);
    sideBody=box(true);
    pad(sideBody,16,12,16,20);
    sideBody.setBackground(TideControls.surface(this,0xe9faf8ef,0xe2f2f3e9,16,0x79ffffff));
    FrameLayout.LayoutParams sideSheet=new FrameLayout.LayoutParams(-1,-2);
    sideSheet.setMargins(dp(4),dp(7),dp(8),dp(8));
    sideScroll.addView(sideBody,sideSheet);
    workspace.addView(sideScroll,new LinearLayout.LayoutParams(0,-1,.85f));
    sideScroll.setVisibility(View.GONE);
  }

  static int tabIndex(String id) {
    return java.util.Arrays.asList(TAB_IDS).indexOf(id);
  }

  void showTabs(boolean show) {
    if (tabTrack == null) return;
    tabTrack.setVisibility(show ? View.VISIBLE : View.GONE);
    tabBorder.setVisibility(show ? View.VISIBLE : View.GONE);
  }

  private void positionIndicator(boolean animated) {
    if (tabTrack.getWidth() == 0 || activeTab < 0) return;
    float x = (activeTab + .5f) * tabTrack.getWidth() / TAB_IDS.length - dp(60) / 2f;
    indicator.animate().cancel();
    if (animated && ValueAnimator.areAnimatorsEnabled())
      indicator.animate().translationX(x).setDuration(280).setInterpolator(SETTLE).start();
    else indicator.setTranslationX(x);
  }

  void tabs(String current, java.util.function.Consumer<String> select) {
    if (tabTrack == null) {
      tabBorder = new View(this);
      tabBorder.setBackgroundColor(Color.TRANSPARENT);
      add(root, tabBorder, 1);
      tabTrack = new FrameLayout(this);
      tabTrack.setBackground(TideControls.surface(this,0xeefaf9ef,0xffecefe5,0,0));
      add(root, tabTrack, 64);
      indicator = new View(this);
      indicator.setBackground(TideControls.surface(this,0xcafffff5,0x939bc6b5,17,0xc1fffff9));
      indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tabTrack.addView(indicator, new FrameLayout.LayoutParams(dp(60), dp(35)));
      nav = box(false);
      tabTrack.addView(nav, new FrameLayout.LayoutParams(-1, -1));
      String[] titles = {"对话", "斩词", "词库", "搜词", "我的"};
      int[] icons = {R.drawable.s_book, R.drawable.s_home, R.drawable.s_shelf, R.drawable.s_search, R.drawable.s_orb};
      for (int i = 0; i < TAB_IDS.length; i++) {
        final int index = i;
        LinearLayout item = box(true);
        item.setGravity(Gravity.CENTER);
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25365346), null, surface(white, 14, 0)));
        item.setDefaultFocusHighlightEnabled(true);
        item.setOnClickListener(v -> {
          if (activeTab == index) return;
          v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          select.accept(TAB_IDS[index]);
        });
        item.setFocusable(true);
        item.setContentDescription(titles[i]);
        FrameLayout capsule = new FrameLayout(this);
        tabIcons[i] = icon(icons[i], muted);
        capsule.addView(tabIcons[i], new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        item.addView(capsule, dp(44), dp(26));
        gap(item, 3);
        tabLabels[i] = txt(titles[i], 12, muted, false);
        tabLabels[i].setGravity(Gravity.CENTER);
        item.addView(tabLabels[i]);
        nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
      }
      tabTrack.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
        indicator.setTranslationY(((ViewGroup)nav.getChildAt(0)).getChildAt(0).getTop()-dp(4));
        if (r-l != or-ol) positionIndicator(false);
      });
    }
    boolean visible = tabTrack.getVisibility() == View.VISIBLE;
    showTabs(true);
    int target = tabIndex(current);
    if (target == activeTab) return;
    boolean animate = visible && activeTab >= 0;
    activeTab = target;
    for (int i = 0; i < TAB_IDS.length; i++) {
      boolean on = i == target;
      nav.getChildAt(i).setSelected(on);
      tabIcons[i].setColorFilter(on ? blue : muted);
      tabLabels[i].setTextColor(on ? blue : muted);
    }
    positionIndicator(animate);
  }
}
