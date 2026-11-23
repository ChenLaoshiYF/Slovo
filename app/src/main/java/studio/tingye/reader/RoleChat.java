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
      return "你是耐心的俄语老师，通过角色扮演教中国初学者。当前角色是 "+persona+"。"
          +"始终围绕俄语学习，无论用户用什么语言，都帮他用俄语表达意思。"
          +"使用简单的 A1-A2 俄语，必要时用一句中文解释。纠正明显语法错误。"
          +"每次至少给一句完整俄语，最后问一个符合当前场景的俄语问题。不要编造词义，不要夹用英文。 /no_think";

    }

    String practice() {
      switch(this) {
        case CAFE:return "Мне, пожалуйста, чай.\n请给我一杯茶。\nЧто вы хотите заказать?";
        case STATION:return "Мне нужен билет до Москвы.\n我需要一张去莫斯科的票。\nКогда вы хотите поехать?";
        case STUDENT:return "Я учусь в университете.\n我在大学读书。\nЧто ты изучаешь?";
        default:return "Я учу русский язык.\n我正在学俄语。\nКак тебя зовут?";
      }
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
  private boolean cloud() { return a.getPreferences(0).getBoolean("roleChatCloud",false); }

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
    a.gap(a.body,6);
    a.add(a.body,a.action(cloud()?"使用我的 API ▾":"使用手机 AI ▾",0,false,this::pickEngine),-2);
    a.gap(a.body,5);
    a.add(a.body,a.txt(cloud()?"发送时会把当前对话传给你选择的服务。":"常见场景用校订课卡辅助，自由对话由手机模型生成。复杂问题可切换自己的 API。",13,PaperActivity.muted,false),-2);
    a.gap(a.body,8);
    messages=a.box(true);
    a.add(a.body,messages,-2);
    drawMessages();

    input=null;
    send=null;
    if(!cloud() && !LocalLanguageModel.enabled(a.getPreferences(0))) {
      a.gap(a.body,9);
      a.add(a.body,a.action("开启手机 AI",R.drawable.s_gear,true,()->a.openAiSettings("role_chat")),-2);
      return;
    }
    LinearLayout dock=a.box(false);
    dock.setGravity(Gravity.CENTER_VERTICAL);
    input=new EditText(a);
    input.setHint("中文也可以，我会带你用俄语说");
    input.setContentDescription("对话输入");
    input.setTextSize(16);
    input.setTextColor(PaperActivity.ink);
    input.setHintTextColor(PaperActivity.muted);
    input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(240)});
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
    if(a.getResources().getConfiguration().screenWidthDp>=840) {
      LinearLayout side=a.secondary();
      a.add(side,a.txt("这一轮怎么说",20,PaperActivity.blue,true),-2);
      a.gap(side,14);
      a.add(side,a.txt(role.practice(),19,PaperActivity.ink,false),-2);
      a.gap(side,16);
      a.add(side,a.action("听示范",R.drawable.s_audio,false,()->a.voice.playExtracted(PhotoText.russianSource(role.practice()))),-2);
      a.gap(side,16);
      a.add(side,a.txt("想到什么就说。卡住时可以用中文问“这句怎么说”，或让对方再说慢一点。长按对话中的俄文可以查词。",15,PaperActivity.muted,false),-2);
    }
    if(!room.turns.isEmpty() || !room.pending.isEmpty())scrollDown();
  }

  private void pickEngine() {
    new FeedbackDialog(a).setTitle("对话使用哪个模型？")
        .setSingleChoiceItems(new String[]{"手机 AI · 离线","我的 API · 联网"},cloud()?1:0,(d,index)->{
          d.dismiss();
          if(index==1 && !CloudAi.configured(a.getPreferences(0),CloudAi.selected(a.getPreferences(0)))) {
            a.openAiSettings("role_chat");return;
          }
          leave();
          a.getPreferences(0).edit().putBoolean("roleChatCloud",index==1)
              .putBoolean(LocalLanguageModel.ENABLED,true).apply();
          a.show("role_chat");
        }).setNegativeButton("取消",null).show();
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
    int window=a.getResources().getConfiguration().screenWidthDp;
    words.setMaxWidth(a.dp(Math.max(130,Math.min(window>=840?480:680,window)-(mine?108:133))));
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
    if(generating || input==null || (!cloud() && !LocalLanguageModel.enabled(a.getPreferences(0))))return;
    String message=input.getText().toString().trim();
    if(message.isEmpty())return;
    Role chosen=role;
    boolean useCloud=cloud();
    CloudAi.Provider provider=CloudAi.selected(a.getPreferences(0));
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
        String reviewed=TutorGuide.answer(chosen.name(),message);
        String grounded=chosen.instruction();
        if(!useCloud && reviewed.isEmpty())grounded+=a.dictionaryContext(message);
        result=(!useCloud && !reviewed.isEmpty())?reviewed:(useCloud?CloudAi.chat(a.getPreferences(0),provider,chosen.instruction(),history,message).text
            :LocalLanguageModel.chat(a.getApplicationContext(),grounded,history,message)).trim();
        if(result.isEmpty() || result.equalsIgnoreCase(message))throw new IOException("没有可用回复");
        if(!useCloud && result.matches("(?s).*[А-Яа-яЁё][\\u4e00-\\u9fff].*"))
          result="这句先从常用表达练起。\n"+chosen.practice();
        if(!result.contains("?") && !result.contains("？"))
          result+="\n"+chosen.practice().substring(chosen.practice().lastIndexOf('\n')+1);
        if(!result.matches("(?s).*[А-Яа-яЁё]{2,}.*"))
          result+="\n\n练一句\n"+chosen.practice();
      } catch(Exception error) {
        android.util.Log.w("Slovo","Role chat failed",error);
        failure="这句没有生成，输入已保留。可以重试或切换模型。";
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

  void fromWord(String word) {
    leave();role=Role.TUTOR;
    rooms.get(role).draft="请教我 "+word+" 的意思和用法，并给一句简单俄语例句。";
    a.show("role_chat");
  }

  void save(android.os.Bundle state) {
    if(input!=null)rooms.get(role).draft=input.getText().toString();
    state.putString("chatRole",role.name());
    for(Role option:Role.values()) {
      Room room=rooms.get(option);
      state.putStringArrayList("chat_"+option.name(),new ArrayList<>(room.turns));
      state.putString("chatDraft_"+option.name(),room.pending.isEmpty()?room.draft:room.pending);
    }
  }

  void restore(android.os.Bundle state) {
    try { role=Role.valueOf(state.getString("chatRole",Role.CAFE.name())); }
    catch(IllegalArgumentException ignored) { role=Role.CAFE; }
    for(Role option:Role.values()) {
      Room room=rooms.get(option);
      ArrayList<String> history=state.getStringArrayList("chat_"+option.name());
      if(history!=null && history.size()%2==0)room.turns.addAll(history);
      room.draft=state.getString("chatDraft_"+option.name(),"");
    }
  }

  void close() {
    engine.shutdownNow();
  }
}
