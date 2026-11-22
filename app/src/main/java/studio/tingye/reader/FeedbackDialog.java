package studio.tingye.reader;

import android.app.*;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

/** A fitted inset sheet with platform dialog and selection semantics. */
final class FeedbackDialog extends AlertDialog.Builder {
  FeedbackDialog(Context c) {
    super(c);
  }

  @Override
  public AlertDialog create() {
    AlertDialog dialog = super.create();
    Window window = dialog.getWindow();
    Context c = dialog.getContext();
    int inset = TideControls.dp(c,8);
    window.setBackgroundDrawable(new InsetDrawable(
        TideControls.surface(c,0xfffdfbf1,0xffe7eee3,22,0xffa8baaa),inset));
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setDimAmount(.36f);
    View root = window.getDecorView();
    root.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          public void onViewAttachedToWindow(View v) {
            v.post(() -> attach(v));
          }

          public void onViewDetachedFromWindow(View v) {
            v.removeOnAttachStateChangeListener(this);
          }
        });
    return dialog;
  }

  private static void attach(View v) {
    TideControls.style(v);
    if (v instanceof Button && !(v instanceof CompoundButton))
      TideControls.dialogButton((Button)v,v.getId()==android.R.id.button1);
    if (v instanceof ListView) {
      ListView list = (ListView)v;
      Context c = v.getContext();
      list.setSelector(new RippleDrawable(ColorStateList.valueOf(0x46447562),null,
          TideControls.surface(c,-1,-1,12,0)));
      list.setDividerHeight(TideControls.dp(c,1));
      list.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob) -> {
        for (int i=0;i<list.getChildCount();i++) TideControls.style(list.getChildAt(i));
      });
    }
    if (v instanceof Button || v instanceof Spinner || v instanceof ListView) {
      v.setOnTouchListener(
          (item, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                && item.isEnabled()
                && event.getX() >= 0
                && event.getY() >= 0
                && event.getX() < item.getWidth()
                && event.getY() < item.getHeight())
              item.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return false;
          });
    }
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) attach(group.getChildAt(i));
    }
  }
}
