package studio.tingye.reader;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.FutureTask;

/** Check the actual Android layout tree at the current window size and font scale. */
final class InterfaceAudit {
  private final Instrumentation test;
  private WordActivity a;
  private int controls;
  InterfaceAudit(Instrumentation test){this.test=test;}
  private void main(Runnable task) throws Exception {
    FutureTask<Void> result=new FutureTask<>(task,null);
    test.runOnMainSync(result);
    result.get();
  }
  void run() throws Exception {
    a=(WordActivity)test.startActivitySync(new Intent(test.getTargetContext(),WordActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    for(int i=0;i<100 && a.data==null;i++)Thread.sleep(100);
    for(String page:new String[]{"role_chat","home","shelf","search","atlas","portrait","memory","lab","bookwords","detail",
        "settings","quotes","keepsakes","appearance","reminder_settings","assistant","ai_translate","ai_settings","updates","credits"}){
      main(()->{
        switch(page){
          case "detail":a.open(a.data.word(a.lex.books.get(0).words.get(0)),"search");break;
          case "memory":a.memoryChart();break;
          case "lab":a.openLab("atlas");break;
          default:a.show(page);
        }
      });
      test.waitForIdleSync();Thread.sleep(350);
      main(()->{if(!page.equals(a.screen))throw new AssertionError("expected "+page+" got "+a.screen);
        inspect(a.shell,page);if(a.nav.isShown())inspect(a.nav,page);});
      if(page.equals("shelf"))capture("shelf");
      if(page.equals("home")){
        main(()->{
          View reveal=described(a.shell,"看释义");
          if(reveal==null)throw new AssertionError("review card did not open");
          reveal.performClick();
        });
        test.waitForIdleSync();Thread.sleep(650);
        main(()->{
          if(described(a.shell,"朗读例句")==null || !containsText(a.shell,"IPA  "))
            throw new AssertionError("missing pronunciation or example on revealed card "+a.quizKey);
          View meaning=a.shell.findViewWithTag("review.translation");Rect visible=new Rect();
          if(meaning==null || !meaning.getGlobalVisibleRect(visible) || visible.height()<meaning.getHeight()-2)
            throw new AssertionError("example translation is below the fold");
          inspect(a.shell,"revealed review");
        });
        capture("review");
        main(()->{
          android.database.sqlite.SQLiteDatabase db=a.data.getWritableDatabase();db.beginTransaction();
          try {
            String previous=a.quizKey;a.grade(previous,2);
            if(a.data.session.active()){
              if(previous.equals(a.quizKey) || described(a.shell,"朗读例句")!=null)
                throw new AssertionError("stale example on next card");
              a.reveal(4);
              if(described(a.shell,"朗读例句")==null)throw new AssertionError("next card missing example");
            }
          }finally{db.endTransaction();}
        });
      }
    }
    android.util.Log.i("SlovoChecks","LAYOUT tested 20 screens, "+controls+" measured buttons");
    roles();
    keyboard();
  }
  private boolean containsText(View root,String prefix){
    if(root instanceof TextView && ((TextView)root).getText().toString().startsWith(prefix))return true;
    if(root instanceof ViewGroup){
      ViewGroup group=(ViewGroup)root;
      for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),prefix))return true;
    }
    return false;
  }

  private void capture(String name) throws Exception {
    android.graphics.Bitmap image=test.getUiAutomation().takeScreenshot();
    if(image==null)throw new AssertionError("screenshot "+name);
    // Export emulator evidence through the shared gallery on scoped-storage devices.
    if(android.os.Build.VERSION.SDK_INT<29){image.recycle();return;}
    android.content.ContentValues values=new android.content.ContentValues();
    values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"slovo-"+name+".png");
    values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SlovoQA");
    android.content.ContentResolver resolver=a.getContentResolver();
    android.net.Uri uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
    if(uri==null)throw new AssertionError("missing gallery entry");
    try(java.io.OutputStream out=resolver.openOutputStream(uri)){
      if(!image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))throw new AssertionError("PNG export");
    }finally{image.recycle();}
  }

  private View described(View root,String label){
    if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
    if(root instanceof ViewGroup){
      ViewGroup g=(ViewGroup)root;
      for(int i=0;i<g.getChildCount();i++){
        View match=described(g.getChildAt(i),label);
        if(match!=null)return match;
      }
    }
    return null;
  }

  private void keyboard() throws Exception {
    main(()->a.show("role_chat"));test.waitForIdleSync();
    EditText input=(EditText)described(a.shell,"对话输入");
    int[] before=new int[2],after=new int[2];
    main(()->{
      described(a.shell,"发送").getLocationOnScreen(before);
      input.requestFocus();
      ((android.view.inputmethod.InputMethodManager)a.getSystemService("input_method")).showSoftInput(input,1);
    });
    Thread.sleep(900);
    int[] typed=new int[2];
    main(()->{
      if(android.os.Build.VERSION.SDK_INT>=30 && !input.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()))
        throw new AssertionError("keyboard did not appear");
      described(a.shell,"发送").getLocationOnScreen(after);
      input.setText("可以再说一遍吗");input.setSelection(input.length());
    });
    Thread.sleep(400);
    main(()->{
      described(a.shell,"发送").getLocationOnScreen(typed);
      if(before[0]!=after[0] || !Arrays.equals(after,typed))throw new AssertionError("send button drifted");
      a.hideKeys();
    });
    Thread.sleep(700);
    main(()->{
      described(a.shell,"发送").getLocationOnScreen(typed);
      if(!Arrays.equals(before,typed))throw new AssertionError("send button shifted after hiding keyboard");
    });
  }

  private void inspect(View view,String page){
    if(!view.isShown() || !(view instanceof ViewGroup))return;
    ViewGroup group=(ViewGroup)view;
    if(group instanceof LinearLayout && group.isClickable() && ((LinearLayout)group).getGravity()==Gravity.CENTER
        && group.getChildCount()>0 && group.getWidth()>0){
      Rect bounds=new Rect(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE);
      for(int i=0;i<group.getChildCount();i++){
        View v=group.getChildAt(i);
        if(v.getVisibility()!=View.VISIBLE || v.getWidth()==0 || v.getHeight()==0)continue;
        bounds.left=Math.min(bounds.left,v.getLeft());bounds.top=Math.min(bounds.top,v.getTop());
        bounds.right=Math.max(bounds.right,v.getRight());bounds.bottom=Math.max(bounds.bottom,v.getBottom());
      }
      if(bounds.right>=bounds.left){
        int dx=Math.abs(bounds.left+bounds.right-group.getWidth()-group.getPaddingLeft()+group.getPaddingRight());
        int dy=Math.abs(bounds.top+bounds.bottom-group.getHeight()-group.getPaddingTop()+group.getPaddingBottom());
        if(dx>4 || dy>4 || bounds.left<0 || bounds.right>group.getWidth()+1)
          throw new AssertionError(page+" "+group.getContentDescription()+" content "+bounds+" in "+group.getWidth()+"x"+group.getHeight());
        controls++;
      }
    }
    for(int i=0;i<group.getChildCount();i++)inspect(group.getChildAt(i),page);
  }
  private void roles() throws Exception {
    Field field=WordActivity.class.getDeclaredField("roleChat");field.setAccessible(true);
    RoleChat chat=(RoleChat)field.get(a);
    Class<?> roleType=Class.forName("studio.tingye.reader.RoleChat$Role");
    // Route through the actual composer and per-role rooms, including switching away and back.
    Method select=RoleChat.class.getDeclaredMethod("selectRole",roleType);select.setAccessible(true);
    android.content.SharedPreferences prefs=a.getPreferences(0);
    boolean local=prefs.getBoolean(LocalLanguageModel.ENABLED,false),cloud=prefs.getBoolean("roleChatCloud",false);
    prefs.edit().putBoolean(LocalLanguageModel.ENABLED,true).putBoolean("roleChatCloud",false).commit();
    try {
      main(()->a.show("role_chat"));test.waitForIdleSync();
      for(String[] turn:new String[][]{{"STATION","我想去莫斯科","москв"},{"CAFE","我想要一杯不加糖的咖啡","кофе"},
          {"STATION","明天","москв"},{"CAFE","打包","собой"},{"STATION","往返票","обратный"},
          {"STUDENT","我喜欢天文学",""},{"TUTOR","我总是弄混俄语的过去时","читала"}}){
        main(()->{
          try{select.invoke(chat,Enum.valueOf((Class)roleType,turn[0]));}
          catch(Exception e){throw new RuntimeException(e);}
          EditText input=(EditText)described(a.shell,"对话输入");
          input.setText(turn[1]);described(a.shell,"发送").performClick();
        });
        long deadline=android.os.SystemClock.uptimeMillis()+120000;
        boolean[] done={false};
        while(!done[0] && android.os.SystemClock.uptimeMillis()<deadline){
          Thread.sleep(150);main(()->done[0]=described(a.shell,"发送").isEnabled());
        }
        if(!done[0])throw new AssertionError("reply timed out");
        android.os.Bundle snapshot=new android.os.Bundle();
        main(()->chat.save(snapshot));
        ArrayList<String> turns=snapshot.getStringArrayList("chat_"+turn[0]);
        if(turns.size()<2 || !turns.get(turns.size()-2).equals(turn[1]))throw new AssertionError("message was not saved");
        String result=turns.get(turns.size()-1);
        android.util.Log.i("SlovoChecks","UIROLE "+turn[0]+" "+turn[1]+" => "+result);
        if(!result.split("\n")[0].matches(".*[А-Яа-яЁё]{2,}.*") || !result.toLowerCase(Locale.ROOT).contains(turn[2]))
          throw new AssertionError(turn[1]+": "+result);
        if(turn[0].equals("CAFE") && turns.toString().contains("Москв"))throw new AssertionError("rooms mixed");
      }
    } finally {prefs.edit().putBoolean(LocalLanguageModel.ENABLED,local).putBoolean("roleChatCloud",cloud).commit();}

  }
}
