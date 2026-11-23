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
    POLISH("俄文润色","", "Пришлите свой текст.","贴一段俄文，我帮你改顺，再解释一处修改。"),
    EXPAND("俄文扩写","", "О чём вы хотите написать?","给我一句俄文，把它扩成一个短段落。"),
    COMPOSE("中文题目写俄文","", "Что напишем сегодня?","用中文告诉我主题、写给谁，以及想表达什么。"),
    TRANSLATE("中俄互译","", "Что перевести?","发中文或俄文都可以。"),
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
      switch(this) {
        case POLISH:return "润色用户的俄文，保留所有事实和语气。先给修改后的俄文，再用一句中文解释格、体或搭配的修改。 /no_think";
        case EXPAND:return "将用户的俄文自然扩写为三句连贯俄文，保留原文视角和事实。不要翻译题目。 /no_think";
        case COMPOSE:return "按用户的中文要求写俄文成稿。写邮件时注意收件人、称呼、语气和落款。不要杜撰姓名日期。只在关键信息缺失时询问。 /no_think";
        case TRANSLATE:return LocalLanguageModel.TRANSLATION;
        default:break;
      }
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
  private Role role=Role.POLISH;
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
    a.gap(actions,6);
    actions.addView(a.action(cloud()?"我的 API":"手机 AI",0,false,this::pickEngine));
    a.gap(actions,6);
    actions.addView(a.action("重开",0,false,this::reset));
    a.add(a.body,actions,-2);
    if(cloud())a.add(a.body,a.txt("对话会发给你选择的 API 服务。",13,PaperActivity.muted,false),-2);
    a.gap(a.body,12);
    messages=a.box(true);
    a.add(a.body,messages,-2);
    drawMessages();

    input=null;
    send=null;
    LinearLayout dock=a.box(false);
    dock.setGravity(Gravity.CENTER_VERTICAL);
    input=new EditText(a);
    input.setHint("写下想说的话…");
    input.setSingleLine(true);
    input.setContentDescription("对话输入");
    input.setTextSize(16);
    input.setTextColor(PaperActivity.ink);
    input.setHintTextColor(PaperActivity.muted);
    input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(900)});
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
      a.add(side,a.txt("试试这句",20,PaperActivity.blue,true),-2);
      String example;
      switch(role) {
        case POLISH: example="Я учиться в университете и изучаю русский язык."; break;
        case EXPAND: example="Осенью мы поехали на Байкал."; break;
        case COMPOSE: example="给老师写一封俄文邮件，问他周五下午是否方便见面。"; break;
        case TRANSLATE: example="我订了两张明天去莫斯科的车票。"; break;
        default: example=role.practice().split("\n")[0];
      }
      a.gap(side,12);
      a.add(side,a.txt(example,19,PaperActivity.ink,false),-2);
      a.add(side,a.action("放入输入框",0,false,()->{input.setText(example);input.setSelection(input.length());input.requestFocus();}),-2);
      if(!PhotoText.russianSource(example).isEmpty())a.add(side,a.action("听示范",R.drawable.s_audio,false,()->a.voice.playExtracted(example)),-2);
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
    new FeedbackDialog(a).setTitle("想练什么？")
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
    if(generating || input==null)return;
    String message=input.getText().toString().trim();
    if(message.isEmpty())return;
    if(!cloud() && !LocalLanguageModel.enabled(a.getPreferences(0))) {
      rooms.get(role).draft=message;
      new FeedbackDialog(a).setTitle("开启手机 AI？").setMessage("模型会在手机上运行，断网也能用。")
          .setNegativeButton("取消",null).setPositiveButton("开启并发送",(d,i)->{
            a.getPreferences(0).edit().putBoolean(LocalLanguageModel.ENABLED,true).apply();send();
          }).show();
      return;
    }
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
        boolean writing=chosen.persona.isEmpty();
        String reviewed=writing?"":TutorGuide.answer(chosen.name(),message);
        String grounded=chosen.instruction();
        if(!writing && !useCloud && reviewed.isEmpty())grounded+=a.dictionaryContext(message);
        result=(!useCloud && !reviewed.isEmpty())?reviewed:(useCloud?CloudAi.chat(a.getPreferences(0),provider,chosen.instruction(),history,message).text
            :writing?WritingPractice.answer(a.getApplicationContext(),chosen.name(),grounded,history,message):LocalLanguageModel.chat(a.getApplicationContext(),grounded,history,message)).trim();
        if(result.isEmpty() || (!writing && result.equalsIgnoreCase(message)))throw new IOException("没有可用回复");
        if(!writing && !useCloud && result.matches("(?s).*[А-Яа-яЁё][\\u4e00-\\u9fff].*"))
          result="这句先从常用表达练起。\n"+chosen.practice();
        if(!writing && !result.contains("?") && !result.contains("？"))
          result+="\n"+chosen.practice().substring(chosen.practice().lastIndexOf('\n')+1);
        if(!writing && !result.matches("(?s).*[А-Яа-яЁё]{2,}.*"))
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
      a.learnFromChat(chosen.name(),message,reply);
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
