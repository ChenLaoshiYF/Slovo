package studio.tingye.reader;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.IntConsumer;

final class DailyGoal {
  static String label(int goal) {
    return goal == 0 ? "只复习" : goal+" 词";
  }

  static void show(WordActivity a, String title, LinearLayout body, int current,
      boolean pickingBook, IntConsumer save) {
    a.add(body,a.txt("每日新词",16,PaperActivity.ink,true),-2);
    a.gap(body,7);
    LinearLayout presets = a.box(false);
    int[] values = {5,10,20,50,100};
    TextView[] chips = new TextView[values.length];
    EditText amount = new EditText(a);
    amount.setSingleLine();
    amount.setTextSize(20);
    amount.setInputType(InputType.TYPE_CLASS_NUMBER);
    amount.setImeOptions(EditorInfo.IME_ACTION_DONE);
    amount.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
    amount.setHint("输入词数");
    amount.setContentDescription("每日新词数量");
    amount.setSelectAllOnFocus(true);

    for (int i=0;i<values.length;i++) {
      int value=values[i];
      TextView option=a.txt(Integer.toString(value),16,PaperActivity.ink,true);
      option.setGravity(Gravity.CENTER);
      option.setMinimumHeight(a.dp(48));
      a.click(option,PaperActivity.paper,12,0,()->amount.setText(Integer.toString(value)));
      if(i>0)a.gap(presets,4);
      a.wide(presets,option);
      chips[i]=option;
    }
    amount.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
      public void onTextChanged(CharSequence s,int start,int before,int count) {}
      public void afterTextChanged(Editable text) {
        if(amount.getError()!=null)amount.setError(null);
        for (int i=0;i<values.length;i++) chips[i].setSelected(text.toString().equals(Integer.toString(values[i])));
      }
    });
    amount.setText(Integer.toString(current));
    a.add(body,presets,-2);
    a.gap(body,4);
    a.add(body,amount,50);
    a.add(body,a.txt("填 0 只复习。到期复习单独计算。",13,PaperActivity.muted,false),-2);

    body.setFocusableInTouchMode(true);
    AlertDialog dialog=new FeedbackDialog(a).setTitle(title).setView(body)
        .setNegativeButton("返回",null)
        .setPositiveButton(pickingBook?"使用这本":"保存",null)
        .create();
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dialog.setOnShowListener(ignored->{
      body.requestFocus();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
        int value;
        try { value=Integer.parseInt(amount.getText().toString().trim()); }
        catch(NumberFormatException error) { value=-1; }
        if(value<0 || value>StudyStore.MAX_NEW_WORDS) {
          amount.setError("请输入 0 到 "+StudyStore.MAX_NEW_WORDS);
          amount.requestFocus();
          return;
        }
        save.accept(value);
        dialog.dismiss();
      });
      amount.setOnEditorActionListener((v,action,event)->{
        if(action!=EditorInfo.IME_ACTION_DONE)return false;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        return true;
      });
    });
    dialog.show();
  }
}
