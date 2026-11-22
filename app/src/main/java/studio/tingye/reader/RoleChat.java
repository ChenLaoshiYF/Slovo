package studio.tingye.reader;

import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Short, independent practice conversations powered by the bundled model. */
final class RoleChat {
  private enum Role {
    CAFE("咖啡店店员","a cafe server who takes orders and answers about drinks and prices",
        "Здравствуйте! Что будете заказывать?","您好！想点些什么？"),
    STATION("车站售票员","a railway ticket clerk who asks about destinations and dates",
        "Добрый день! Куда вы хотите поехать?","您好！您打算去哪里？"),
    STUDENT("俄罗斯同学","a friendly Russian classmate who talks about university and hobbies",
        "Привет! На каком факультете ты учишься?","你好！你在哪个院系学习？"),
    TUTOR("俄语老师","a Russian tutor who can briefly explain mistakes in Chinese when asked",
        "Привет! Расскажи немного о себе.","你好！简单介绍一下自己吧。");

    final String title,persona,opening,meaning;
    Role(String title,String persona,String opening,String meaning) {
      this.title=title;
      this.persona=persona;
      this.opening=opening;
      this.meaning=meaning;
    }

    String instruction() {
      return "You are "+persona+". Continue this conversation in one or two short, natural Russian sentences. "
          +"Speak at A1-A2 level. Answer the user in character, then ask one related question. "
          +"If the user asks for an explanation in Chinese, answer briefly in Chinese.";
    }
  }

  private static final class Room {
    final List<String> turns=new ArrayList<>();
    String pending="",failure="",draft="";
  }

  private final WordActivity a;
  private final ExecutorService engine=Executors.newSingleThreadExecutor();
  private final EnumMap<Role,Room> rooms=new EnumMap<>(Role.class);
  private Role role=Role.CAFE;
  private LinearLayout messages;
  private EditText input;
  private View send;
  private boolean generating;

  RoleChat(WordActivity activity) {
    a=activity;
    for(Role option:Role.values())rooms.put(option,new Room());
  }

  void render() {
    Room room=rooms.get(role);
    LinearLayout actions=a.box(false);
    a.wide(actions,a.action(role.title+" ▾",0,false,this::pickRole));
    a.gap(actions,8);
    actions.addView(a.action("重开",0,false,this::reset));
    a.gap(a.body,8);
    a.add(a.body,actions,-2);
    a.gap(a.body,8);
    messages=a.box(true);
    a.add(a.body,messages,-2);
    drawMessages();

    input=null;
    send=null;
    if(!LocalLanguageModel.enabled(a.getPreferences(0))) {
      a.gap(a.body,9);
      a.add(a.body,a.action("开启手机 AI",R.drawable.s_gear,true,()->a.openAiSettings("role_chat")),-2);
      return;
    }
    LinearLayout dock=a.box(false);
    dock.setGravity(Gravity.CENTER_VERTICAL);
    input=new EditText(a);
    input.setHint("用俄语回一句");
    input.setContentDescription("对话输入");
    input.setTextSize(16);
    input.setTextColor(PaperActivity.ink);
    input.setHintTextColor(PaperActivity.muted);
    input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    input.setOnEditorActionListener((v,id,event)->{
      if(id!=EditorInfo.IME_ACTION_SEND)return false;
      send();
      return true;
    });
    input.setText(room.draft);
    input.setSelection(input.length());
    input.setMinHeight(a.dp(48));
    input.setBackground(a.surface(0xddf4f8ee,15,0x90bdccbe));
    a.pad(input,12,6,12,6);
    a.wide(dock,input);
    a.gap(dock,7);
    send=a.action("发送",R.drawable.s_arrow,true,this::send);
    dock.addView(send);
    updateSend();
    a.dock(dock);
    if(!room.turns.isEmpty() || !room.pending.isEmpty())scrollDown();
  }

  void leave() {
    if(input!=null)rooms.get(role).draft=input.getText().toString();
    messages=null;
    input=null;
    send=null;
  }

  private void pickRole() {
    String[] labels=new String[Role.values().length];
    for(Role option:Role.values())labels[option.ordinal()]=option.title;
    new FeedbackDialog(a).setTitle("选一个角色")
        .setSingleChoiceItems(labels,role.ordinal(),(dialog,index)->{
          dialog.dismiss();
          if(role.ordinal()==index)return;
          leave();
          role=Role.values()[index];
          a.show("role_chat");
        }).setNegativeButton("取消",null).show();
  }

  private void reset() {
    Room room=rooms.get(role);
    if(!room.pending.isEmpty()) {
      a.tip("等这一句回完再重开");
      return;
    }
    if(room.turns.isEmpty() && room.failure.isEmpty())return;
    new FeedbackDialog(a).setTitle("重开这段对话？")
        .setNegativeButton("取消",null)
        .setPositiveButton("重开",(dialog,which)->{
          room.turns.clear();
          room.failure="";
          drawMessages();
          a.scroll.scrollTo(0,0);
        }).show();
  }

  private void drawMessages() {
    if(messages==null)return;
    messages.removeAllViews();
    Room room=rooms.get(role);
    bubble(role.opening,false,role.meaning);
    for(int i=0;i<room.turns.size();i++)bubble(room.turns.get(i),i%2==0,"");
    if(!room.pending.isEmpty()) {
      bubble(room.pending,true,"");
      a.add(messages,a.txt("对方正在回话…",14,PaperActivity.muted,false),-2);
    }
    if(!room.failure.isEmpty())a.add(messages,a.txt(room.failure,14,PaperActivity.muted,false),-2);
  }

  private void bubble(String text,boolean mine,String meaning) {
    LinearLayout outer=a.box(false);
    outer.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
    LinearLayout bubble=a.box(true);
    bubble.setBackground(a.surface(mine?0xffdceee1:0xfdf8f8ef,17,0xc6d0e0d1));
    a.pad(bubble,12,8,10,9);
    LinearLayout line=a.box(false);
    line.setGravity(Gravity.CENTER_VERTICAL);
    TextView words=a.txt(text,16,PaperActivity.ink,false);
    words.setMaxWidth(a.dp(Math.max(130,a.getResources().getConfiguration().screenWidthDp-(mine?108:133))));
    a.selectableWords(words,"role_chat");
    line.addView(words,-2,-2);
    if(!mine && !PhotoText.russianSource(text).isEmpty()) {
      line.addView(a.iconButton(R.drawable.s_audio,"听这句",()->{
        a.voice.playExtracted(PhotoText.russianSource(text));
      }),a.dp(48),a.dp(48));
    }
    a.add(bubble,line,-2);
    if(!meaning.isEmpty())a.add(bubble,a.txt(meaning,14,PaperActivity.muted,false),-2);
    outer.addView(bubble,-2,-2);
    a.add(messages,outer,-2);
    a.gap(messages,8);
  }

  private void send() {
    if(generating || input==null || !LocalLanguageModel.enabled(a.getPreferences(0)))return;
    String message=input.getText().toString().trim();
    if(message.isEmpty())return;
    Role chosen=role;
    Room room=rooms.get(chosen);
    List<String> history=new ArrayList<>(room.turns);
    room.pending=message;
    room.failure="";
    room.draft="";
    input.setText("");
    a.hideKeys();
    generating=true;
    updateSend();
    drawMessages();
    scrollDown();
    engine.execute(()->{
      String result="";
      String failure="";
      try {
        result=LocalLanguageModel.chat(a.getApplicationContext(),chosen.instruction(),history,message)
            .replaceAll("[\\s\\p{Cntrl}]+"," ").trim();
        if(result.isEmpty() || result.equalsIgnoreCase(message))throw new IOException("没有可用回复");
      } catch(IOException | RuntimeException error) {
        failure="这句没有生成。可以试一句短一点的。";
      }
      String reply=result,problem=failure;
      a.ui.post(()->{
        if(a.isDestroyed())return;
        finish(chosen,message,reply,problem);
      });
    });
  }

  private void finish(Role chosen,String message,String reply,String problem) {
    Room room=rooms.get(chosen);
    room.pending="";
    room.failure=problem;
    if(problem.isEmpty()) {
      room.turns.add(message);
      room.turns.add(reply);
      if(room.turns.size()>48)room.turns.subList(0,2).clear();
    } else {
      room.draft=message;
      if(chosen==role && input!=null && input.length()==0)input.setText(message);
    }
    generating=false;
    updateSend();
    if(chosen==role && messages!=null) {
      drawMessages();
      scrollDown();
    }
  }

  private void updateSend() {
    if(send==null)return;
    send.setEnabled(!generating);
    send.setAlpha(generating?.56f:1f);
  }

  private void scrollDown() {
    if(messages==null)return;
    android.widget.ScrollView scroll=a.scroll;
    scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
  }

  void close() {
    engine.shutdownNow();
  }
}
