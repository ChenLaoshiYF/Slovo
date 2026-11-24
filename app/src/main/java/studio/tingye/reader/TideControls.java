package studio.tingye.reader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Controls keep their platform roles, focus order and selection APIs. Only their surface changes. */
final class TideControls {
  private static final int INK = PaperActivity.ink, PINE = PaperActivity.blue, EDGE = 0xff748b7e;
  private static final int[] FOCUS = {android.R.attr.state_focused};
  private static final int[] PRESSED = {android.R.attr.state_pressed};
  private static final int[] CHOSEN = {android.R.attr.state_checked};
  private static final int[] OFF = {-android.R.attr.state_enabled};
  private static final int[] REST = {};
  private static final int PLATE = 0, BUTTON = 1, MORE = 2;

  static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density*n); }

  static Drawable surface(Context c, int top, int bottom, int radius, int edge) {
    return new SoftCorners.Surface(top,bottom,dp(c,radius),edge,dp(c,1));
  }

  static Drawable selectable(Context c, boolean dark, int radius, int style) {
    StateListDrawable states = new StateListDrawable();
    states.addState(OFF, surface(c,0xffd8ded6,0xffc9cfc9,radius,0x66899890));
    states.addState(FOCUS, surface(c,dark ? PINE : 0xfff7f3de,dark ? 0xff173b38 : 0xfff1e3c5,radius,0xff94662f));
    states.addState(CHOSEN,surface(c,0xffdfefe3,0xffcedfd0,radius,0xff648778));
    int top = dark ? 0xff376b62 : style == PLATE ? 0xcdfdfcf5 : 0xfffdfbf2;
    int bottom = dark ? 0xff193e3a : style == PLATE ? 0xc3f0f4e9 : 0xffdbe7db;
    states.addState(REST,surface(c,top,bottom,radius,dark ? 0xff5d8680 : 0x8094a99b));
    return new RippleDrawable(ColorStateList.valueOf(dark ? 0x44edffed : 0x2b527764),
        states, surface(c,-1,-1,radius,0));
  }

  static Drawable row(Context c,int radius) {
    StateListDrawable states=new StateListDrawable();
    states.addState(FOCUS,surface(c,0xffe6efe1,0xffdeeadb,10,0xff8a754b));
    states.addState(REST,new ColorDrawable(Color.TRANSPARENT));
    return new RippleDrawable(ColorStateList.valueOf(0x335e836e),states,surface(c,-1,-1,10,0));
  }

  static Drawable chip(Context c) {
    return new InsetDrawable(selectable(c,false,17,BUTTON),0,dp(c,4),0,dp(c,4));
  }

  static void style(View v) {
    Context c = v.getContext();
    if(v instanceof Switch) {
      Switch toggle=(Switch)v;
      if(toggle.getThumbDrawable() instanceof SoftCorners.Surface)return;
      toggle.setButtonDrawable((Drawable)null);
      toggle.setThumbTintList(null);
      toggle.setTrackTintList(null);
      toggle.setShowText(false);
      toggle.setSwitchMinWidth(dp(c,44));
      toggle.setSplitTrack(false);
      toggle.setThumbDrawable(new SoftCorners.Surface(0xfffcfff7,0xffe1efe3,dp(c,8),
          0xc7ffffff,dp(c,1),dp(c,20),dp(c,20)));
      StateListDrawable track=new StateListDrawable();
      track.addState(CHOSEN,new SoftCorners.Surface(0xff3d7e72,PINE,dp(c,9),0,0,dp(c,44),dp(c,17)));
      track.addState(REST,new SoftCorners.Surface(0xffd6dfd4,0xffc7d1c6,dp(c,9),0,0,dp(c,44),dp(c,17)));
      toggle.setTrackDrawable(track);
      toggle.setFocusable(true);
    } else if (v instanceof CompoundButton) {
      CompoundButton b=(CompoundButton)v;
      if (b.getButtonDrawable() instanceof Mark) return;
      boolean radio=b instanceof RadioButton;
      b.setButtonTintList(null);
      b.setButtonDrawable(new Mark(c,radio ? 1 : 0,b.getText().length()==0 ? 40 : 30));
      b.setTextColor(INK);
      b.setMinHeight(dp(c,48));
      b.setBackground(radio ? new InsetDrawable(selectable(c,false,12,BUTTON),dp(c,2),dp(c,2),dp(c,2),dp(c,2))
          : new RippleDrawable(ColorStateList.valueOf(0x37547864),null,surface(c,-1,-1,16,0)));
      b.setFocusable(true);
    } else if (v instanceof SeekBar) {
      SeekBar seek=(SeekBar)v;
      if (seek.getThumb() instanceof Mark) return;
      int progress=seek.getProgress();
      seek.setProgressTintList(null);
      seek.setThumbTintList(null);
      seek.setProgressDrawable(track(c));
      seek.setThumb(new Mark(c,2,28));
      seek.setSplitTrack(false);
      seek.setProgress(progress);
      seek.setMinimumHeight(dp(c,48));
      seek.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24507166),null,surface(c,-1,-1,18,0)));
    } else if (v instanceof ProgressBar) {
      ProgressBar progress=(ProgressBar)v;
      if (!progress.isIndeterminate() && !(progress.getProgressDrawable() instanceof TideTrack)) {
        int value=progress.getProgress();
        progress.setProgressTintList(null);
        progress.setProgressDrawable(track(c));
        progress.setProgress(value);
      }
    } else if (v instanceof EditText) {
      EditText input=(EditText)v;
      if (input.getBackground() instanceof Field) return;
      boolean search=(input.getImeOptions() & android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
          == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && input.getParent() instanceof LinearLayout;
      if (search) {
        LinearLayout frame=(LinearLayout)input.getParent();
        if(frame.getBackground() instanceof Field) return;
        frame.setAddStatesFromChildren(true);
        frame.setBackground(new Field(c));
        input.setBackgroundColor(Color.TRANSPARENT);
      } else input.setBackground(new Field(c));
      input.setTextColor(INK);
      input.setHintTextColor(0xff59695f);
      input.setHighlightColor(0xffc2decc);
      input.setMinHeight(dp(c,48));
      input.setPadding(dp(c,10),dp(c,7),dp(c,10),dp(c,7));
      if (Build.VERSION.SDK_INT>=29) {
        Drawable cursor=new SoftCorners.Surface(PINE,PINE,dp(c,1),0,0,dp(c,2),dp(c,24));
        input.setTextCursorDrawable(cursor);
      }
    } else if (v instanceof Spinner) {
      Spinner s=(Spinner)v;
      if (!(s.getForeground() instanceof Mark)) {
        s.setBackground(selectable(c,false,13,MORE));
        s.setForeground(new Mark(c,3,22));
        s.setMinimumHeight(dp(c,48));
        s.setPadding(dp(c,10),0,dp(c,31),0);
        s.setPopupBackgroundDrawable(surface(c,0xfffcfbf2,0xffe8eee3,15,0xff9cae9f));
      }
      if (s.getAdapter()!=null && !(s.getAdapter() instanceof Choices)) {
        int position=s.getSelectedItemPosition();
        s.setAdapter(new Choices(s.getAdapter()));
        s.setSelection(position,false);
      }
    } else if (v instanceof CheckedTextView) {
      CheckedTextView t=(CheckedTextView)v;
      if (!(t.getCheckMarkDrawable() instanceof Mark)) {
        t.setCheckMarkTintList(null);
        t.setCheckMarkDrawable(new Mark(c,0,28));
        t.setTextColor(INK);
        t.setMinHeight(dp(c,48));
      }
    }
  }

  static Drawable track(Context c) {
    Drawable bed=surface(c,0xffced6cb,0xffcad4c9,5,0);
    Drawable fill=new ClipDrawable(surface(c,0xff347060,0xff24564e,5,0), Gravity.LEFT, ClipDrawable.HORIZONTAL);
    LayerDrawable layers=new TideTrack(new Drawable[]{bed,fill});
    layers.setId(0,android.R.id.background);
    layers.setId(1,android.R.id.progress);
    for(int i=0;i<2;i++){ layers.setLayerHeight(i,dp(c,5)); layers.setLayerGravity(i,Gravity.CENTER_VERTICAL|Gravity.FILL_HORIZONTAL); }
    return layers;
  }

  private static final class TideTrack extends LayerDrawable {
    TideTrack(Drawable[] layers) { super(layers); }
  }

  static void dialogButton(Button b, boolean primary) {
    Context c=b.getContext();
    b.setGravity(Gravity.CENTER);
    b.setAllCaps(false);
    b.setTextColor(primary ? 0xfffffcf3 : INK);
    b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    b.setBackgroundTintList(null);
    b.setBackground(new InsetDrawable(selectable(c,primary,20,BUTTON),dp(c,3),dp(c,3),dp(c,3),dp(c,3)));
    b.setMinHeight(dp(c,48));
    b.setPadding(dp(c,11),dp(c,5),dp(c,11),dp(c,5));
  }

  private static final class Field extends StateListDrawable {
    Field(Context c) {
      addState(FOCUS,surface(c,0xfffcfff8,0xfff1f6eb,12,PINE));
      addState(REST,surface(c,0xeefcfbf2,0xe8eff3e9,12,0x807f9b89));
    }
  }

  private static final class Choices extends BaseAdapter {
    private final SpinnerAdapter source;
    Choices(SpinnerAdapter source) { this.source=source; }
    public int getCount(){return source.getCount();}
    public Object getItem(int pos){return source.getItem(pos);}
    public long getItemId(int pos){return source.getItemId(pos);}
    public boolean hasStableIds(){return source.hasStableIds();}
    @Override public void registerDataSetObserver(DataSetObserver o){source.registerDataSetObserver(o);}
    @Override public void unregisterDataSetObserver(DataSetObserver o){source.unregisterDataSetObserver(o);}
    public View getView(int pos,View old,ViewGroup parent){return label(source.getView(pos,old,parent));}
    public View getDropDownView(int pos,View old,ViewGroup parent){return label(source.getDropDownView(pos,old,parent));}
    private View label(View v) {
      if(v instanceof TextView) {
        TextView t=(TextView)v;
        t.setTextColor(INK); t.setMinHeight(dp(v.getContext(),48));
      }
      return v;
    }
  }

  private static final class Mark extends Drawable {
    private final int kind,width,height;
    private final float density;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path check=new Path();
    private final Path contour=new Path();
    private boolean checked,enabled=true,pressed;
    Mark(Context c,int kind,int width) {
      this.kind=kind; density=c.getResources().getDisplayMetrics().density;
      this.width=dp(c,width); height=dp(c,kind==2 ? 28:24);
      p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public int getIntrinsicWidth(){return width;}
    @Override public int getIntrinsicHeight(){return height;}
    @Override public boolean isStateful(){return true;}
    @Override protected boolean onStateChange(int[] state) {
      checked=false; pressed=false; enabled=false;
      for(int value:state) {
        if(value==android.R.attr.state_checked)checked=true;
        if(value==android.R.attr.state_enabled)enabled=true;
        if(value==android.R.attr.state_pressed)pressed=true;
      }
      invalidateSelf(); return true;
    }
    public void draw(Canvas canvas) {
      Rect b=getBounds(); if (b.isEmpty()) return;
      int save=canvas.save();
      canvas.translate(kind==3 ? b.right-16*density : b.exactCenterX(),b.exactCenterY());
      canvas.scale(density,density);
      p.setStyle(Paint.Style.FILL);
      if(kind==3) {
        check.reset(); check.moveTo(-4,-2);check.lineTo(0,2);check.lineTo(4,-2);
        p.setColor(PINE);p.setStrokeWidth(1.7f);p.setStyle(Paint.Style.STROKE);canvas.drawPath(check,p);
      } else if(kind==2) {
        SoftCorners.trace(contour,-10,-10,10,10,9);
        p.setColor(pressed ? 0xff194c44 : PINE);canvas.drawPath(contour,p);
        SoftCorners.trace(contour,-5.8f,-5.8f,5.8f,5.8f,4);
        p.setColor(0xffeff7eb);canvas.drawPath(contour,p);
        p.setColor(0xff97bdab);canvas.drawCircle(0,0,2.7f,p);
      } else {
        float radius=kind==1 ? 9:5;
        p.setColor(!enabled ? 0xffc6cec5 : checked ? PINE : 0xfffafbf3);
        SoftCorners.trace(contour,-8.5f,-8.5f,8.5f,8.5f,radius);
        canvas.drawPath(contour,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.4f);p.setColor(checked ? PINE : EDGE);
        canvas.drawPath(contour,p);
        if(checked) {
          p.setColor(0xfff5faec);
          if(kind==1){p.setStyle(Paint.Style.FILL);canvas.drawCircle(0,0,3.5f,p);}
          else {check.reset();check.moveTo(-4,0);check.lineTo(-1,3);check.lineTo(4.5f,-3);p.setStrokeWidth(1.9f);canvas.drawPath(check,p);}
        }
      }
      canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int a){}
    @Override public void setColorFilter(ColorFilter f){}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
