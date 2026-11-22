package studio.tingye.reader;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Builds today's next step locally, with an optional model sentence. */
final class StudyAssistant {
  private static final String SOURCE="studyAdviceSource";
  private static final String CACHE_DAY="studyAdviceDay";
  private static final String CACHE_SOURCE="studyAdviceSavedSource";
  private static final String CACHE_TEXT="studyAdviceText";
  private static final String INSTRUCTION="你是学习搭子。用中文给朋友写一句俄语背词提醒，像发消息一样。只说怎么练，直接给句子，不写标题、数字或统计。";
  private static final String PHONE_INSTRUCTION=INSTRUCTION+" 示例：先在心里想想词义，想不起来再翻卡。";
  private final WordActivity a;
  private final ExecutorService adviceWorker=Executors.newSingleThreadExecutor();
  private int minutes=10;
  private TextView reminderHeadline,reminderMessage,assistantMessage;

  private enum Source {
    PROGRESS("默认建议","按当天词量和近期回忆结果即时整理。"),
    PHONE("词汐内置模型","启用手机 AI，由安装包内的模型写提醒。"),
    CLOUD("我的 API 模型","使用在「AI 与翻译」中配置的服务。进入页面时每日自动调用一次，发送学习统计，可能计费。");

    final String title,hint;
    Source(String title,String hint) { this.title=title; this.hint=hint; }
  }

  StudyAssistant(WordActivity activity) { a=activity; }

  private SharedPreferences prefs() { return a.getPreferences(0); }

  private Source source() {
    try { return Source.valueOf(prefs().getString(SOURCE,Source.PROGRESS.name())); }
    catch(IllegalArgumentException invalid) { return Source.PROGRESS; }
  }

  String sourceLabel() { return source().title; }

  void settings() {
    a.gap(a.body,6);
    a.section("提醒来源","");
    for(Source option:Source.values()) {
      boolean active=option==source();
      LinearLayout card=a.box(true);
      a.click(card,active?0xffe8f3e9:PaperActivity.paper,17,active?0xff8aafa0:0,()->select(option));
      a.pad(card,13,11,13,12);
      LinearLayout heading=a.box(false);
      a.wide(heading,a.txt(option.title,17,PaperActivity.ink,true));
      if(active) a.sym(heading,R.drawable.s_check,PaperActivity.blue,18);
      a.add(card,heading,-2);
      a.gap(card,5);
      a.add(card,a.txt(option.hint,14,PaperActivity.muted,false),-2);
      card.setSelected(active);
      card.setContentDescription(option.title);
      a.add(a.body,card,-2);
      a.gap(a.body,8);
    }
    if(source()==Source.CLOUD) {
      CloudAi.Provider provider=CloudAi.selected(prefs());
      a.row(a.body,"API 配置",
          CloudAi.configured(prefs(),provider)?provider.title:"填写你自己的 API 密钥",
          R.drawable.s_gear,()->a.openAiSettings("reminder_settings"));
    }
  }

  private void select(Source option) {
    if(option==Source.CLOUD && source()!=Source.CLOUD) {
      new FeedbackDialog(a).setTitle("用我的 API 写提醒？")
          .setMessage("打开「我的」或学习助手时，每天最多自动请求一次。只发送待复习、待学和近期回忆统计，服务可能计费。")
          .setNegativeButton("取消",null).setPositiveButton("启用",(dialog,which)->saveSource(option)).show();
    } else saveSource(option);
  }

  private void saveSource(Source option) {
    SharedPreferences.Editor edit=prefs().edit().putString(SOURCE,option.name());
    if(option==Source.PHONE)edit.putBoolean(LocalLanguageModel.ENABLED,true);
    edit.apply();
    a.show("reminder_settings");
  }

  private static final class Advice {
    int due, done, goal, available, seen, confident, fuzzy, forgotten;
    int review, fresh;
    String method, detail, terms;
  }

  private Advice plan() {
    Advice advice=new Advice();
    advice.goal=a.data.goal;
    advice.done=a.data.todayNew();
    advice.due=a.data.due();
    advice.available=a.data.newRemaining();
    long now=System.currentTimeMillis();
    long from=(long)(now-7*MemoryModel.DAY);
    try(Cursor cursor=a.data.getReadableDatabase().rawQuery(
        "SELECT grade,COUNT(*) FROM review_log WHERE at>=? AND prior IS NOT NULL GROUP BY grade",
        new String[]{Long.toString(from)})) {
      while(cursor.moveToNext()) {
        int count=cursor.getInt(1);
        switch(cursor.getInt(0)) {
          case 2: advice.confident=count; break;
          case 1: advice.fuzzy=count; break;
          case 0: advice.forgotten=count; break;
          default: break;
        }
      }
    }
    advice.seen=advice.confident+advice.fuzzy+advice.forgotten;
    advice.review=Math.min(advice.due,minutes*3);
    int capacity=Math.max(0,(minutes*6-advice.review*2)/5);
    if(advice.seen>=6 && 2*advice.confident<advice.seen) capacity/=2;
    if(advice.seen==0) capacity=Math.min(10,capacity);
    advice.fresh=Math.min(advice.available,capacity);
    if(advice.seen>=6 && advice.forgotten>=advice.fuzzy && advice.forgotten*3>=advice.seen) {
      advice.method="先回忆，再看释义";
      advice.detail="看到俄文先在心里说出中文。想不起来就看释义，隔几张卡再试一次。";
    } else if(advice.seen>=6 && advice.fuzzy*3>=advice.seen) {
      advice.method="用例句分清容易混的词";
      advice.detail="每个拿不准的词挑一句短例句。下一次先回忆它在句中的意思，再看释义。";
    } else if(advice.seen<6) {
      advice.method="先做一小轮";
      advice.detail="看见俄文先试着回忆，再翻卡核对。做几次到期复习后，建议会跟着你的作答变化。";
    } else {
      advice.method="先清到期词，再学新词";
      advice.detail="记得的词直接过。卡住的先听一遍读音，再回忆词义。";
    }
    ArrayList<StudyStore.Card> risk=new ArrayList<>();
    for(StudyStore.Card card:a.data.cards.values()) {
      if(card.seen>0 && card.due<=now && card.miss>0) risk.add(card);
    }
    risk.sort(Comparator.comparingDouble(c->MemoryModel.recall((now-c.last)/MemoryModel.DAY,c.half)));
    ArrayList<String> terms=new ArrayList<>();
    for(int i=0;i<Math.min(3,risk.size());i++) terms.add(risk.get(i).ru);
    advice.terms=String.join("、",terms);
    return advice;
  }

  void addReminder(Runnable open) {
    LinearLayout card=a.box(true);
    a.click(card,0xeaf4f8ec,19,0xb6fefff5,open);
    a.pad(card,15,14,15,14);
    LinearLayout title=a.box(false);
    a.wide(title,a.txt("学习提醒",14,PaperActivity.blue,true));
    a.sym(title,R.drawable.s_arrow,PaperActivity.blue,17);
    a.add(card,title,-2);
    a.gap(card,7);
    reminderHeadline=a.txt("",19,PaperActivity.ink,true);
    a.add(card,reminderHeadline,-2);
    a.gap(card,6);
    reminderMessage=a.txt("",14,PaperActivity.muted,false);
    a.add(card,reminderMessage,-2);
    a.add(a.body,card,-2);
    refreshReminder();
  }

  void refreshReminder() {
    if(reminderHeadline==null)return;
    Advice advice=plan();
    if(advice.due>0) {
      reminderHeadline.setText(advice.available>0
          ? "先复习 "+advice.due+" 个词，再学 "+advice.available+" 个新词"
          : "今天还有 "+advice.due+" 个词待复习");
    } else if(advice.available>0) {
      reminderHeadline.setText("今天还有 "+advice.available+" 个新词可以学");
    } else {
      reminderHeadline.setText(advice.goal>0 && advice.done>=advice.goal
          ? "今天的 "+advice.goal+" 个新词已练完" : "今天没有待学的词");
    }
    fillSuggestion(reminderMessage,advice);
  }

  void render() {
    Advice advice=plan();
    a.gap(a.body,12);
    a.section("这次能学多久","");
    LinearLayout choices=a.box(false);
    for(int option:new int[]{5,10,20}) {
      if(choices.getChildCount()>0)a.gap(choices,6);
      TextView button=a.txt(option+" 分钟",15,option==minutes?PaperActivity.white:PaperActivity.ink,true);
      button.setGravity(Gravity.CENTER);
      button.setMinHeight(a.dp(48));
      a.glass(button,option==minutes,()->{
        if(minutes==option)return;
        minutes=option;
        renderAgain();
      });
      button.setSelected(option==minutes);
      a.wide(choices,button);
    }
    a.add(a.body,choices,-2);
    a.gap(a.body,15);
    LinearLayout panel=a.box(true);
    panel.setBackground(a.surface(0xf6f9fbf3,18,0x88426055));
    a.pad(panel,15,14,15,14);
    a.add(a.body,panel,-2);
    a.add(panel,a.txt("本机建议",14,PaperActivity.blue,true),-2);
    a.gap(panel,7);
    String target;
    if(advice.review+advice.fresh==0) {
      if(advice.goal==0) target="今天没有到期词";
      else if(advice.done>=advice.goal) target="今天的新词已练完";
      else target="当前词库没有待学新词";
    }
    else if(advice.review==0) target="试着学 "+advice.fresh+" 个新词";
    else if(advice.fresh==0) target="先复习 "+advice.review+" 个词";
    else target="复习 "+advice.review+" 词，再学 "+advice.fresh+" 词";
    a.add(panel,a.txt(target,23,PaperActivity.ink,true),-2);
    a.gap(panel,8);
    a.add(panel,a.txt("待复习 "+advice.due+" 词 · 今日新词 "+advice.done+"/"+advice.goal,
        14,PaperActivity.muted,false),-2);
    if(advice.seen>0) {
      a.gap(panel,3);
      a.add(panel,a.txt("近 7 天复习 "+advice.seen+" 次 · 记住 "+advice.confident+" 次",
          14,PaperActivity.muted,false),-2);
    }
    a.gap(panel,12);
    a.add(panel,a.txt(advice.method,17,PaperActivity.ink,true),-2);
    a.gap(panel,5);
    a.add(panel,a.txt(advice.detail,15,PaperActivity.ink,false),-2);
    if(!advice.terms.isEmpty()) {
      a.gap(panel,7);
      a.add(panel,a.txt("建议先看 "+advice.terms,15,PaperActivity.blue,false),-2);
    }
    a.gap(a.body,12);
    a.add(a.body,a.action("回到斩词",R.drawable.s_arrow,true,()->a.show("home")),-2);
    a.gap(a.body,15);
    a.section("学习提醒","");
    assistantMessage=a.txt("",16,PaperActivity.ink,false);
    a.add(a.body,assistantMessage,-2);
    fillSuggestion(assistantMessage,advice);
    a.gap(a.body,10);
    a.row(a.body,"AI 译句","粘贴短句，选择手机翻译或自己的 API",R.drawable.s_arrow,()->a.show("ai_translate"));
  }

  private void renderAgain() {
    a.body.removeAllViews();
    render();
    a.finishTheme("assistant");
  }

  private String adaptive(Advice advice) {
    if(advice.due==0 && advice.available==0)
      return advice.done>0?"收个尾吧。可以再过一遍刚学的词，碰到能直接想起来的就跳过。"
          :"到期词排好后会出现在这里。想开始时，可以先挑一本正在用的词库。";
    if(advice.due>0 && !advice.terms.isEmpty()) return "可以先过一遍 "+advice.terms+"，卡住再看释义。";
    if(advice.seen>=6 && advice.fuzzy*3>=advice.seen)
      return "近七天有 "+advice.fuzzy+" 次答得模糊。挑一个容易混的词，放进短例句里再回想。";
    return advice.detail;
  }

  private String cacheSource(Source mode,CloudAi.Provider provider) {
    return mode==Source.CLOUD?mode.name()+"/"+provider.name():mode.name();
  }

  private void fillSuggestion(TextView view,Advice advice) {
    Source mode=source();
    CloudAi.Provider provider=CloudAi.selected(prefs());
    String day=StudyStore.today();
    String cache=cacheSource(mode,provider);
    boolean available=mode==Source.PHONE && LocalLanguageModel.enabled(prefs())
        || mode==Source.CLOUD && CloudAi.configured(prefs(),provider);
    if(available && day.equals(prefs().getString(CACHE_DAY,""))
        && cache.equals(prefs().getString(CACHE_SOURCE,""))) {
      view.setText(prefs().getString(CACHE_TEXT,adaptive(advice)));
      return;
    }
    view.setText(adaptive(advice));
    if(!available)return;
    String attempt="studyAdviceAttempt_"+mode.name();
    if(day.equals(prefs().getString(attempt,"")))return;
    prefs().edit().putString(attempt,day).apply();
    // The model gets only aggregate counts and a generic method. Individual words stay local.
    String approach=advice.due+advice.available==0?"结束前顺手回想刚练的词":advice.method;
    String prompt=mode==Source.PHONE?"用一句话提醒我"+approach+"。"
        :"待复习 "+advice.due+" 词，待学 "+advice.available+" 词。最近七天复习 "+advice.seen
            +" 次，其中记住 "+advice.confident+" 次。建议做法："+approach+"。";
    adviceWorker.execute(()->{
      String generated;
      try {
        generated=mode==Source.PHONE
            ? LocalLanguageModel.complete(a,PHONE_INSTRUCTION,prompt,48)
            : CloudAi.complete(prefs(),provider,INSTRUCTION,prompt).text;
      } catch(Exception failure) {
        android.util.Log.i("Slovo","Optional study reminder unavailable: "+failure.getClass().getSimpleName());
        return;
      }
      String clean=generated.replaceAll("\\s+"," ").trim().replaceAll("^[「“]+|[」”]+$","");
      if(clean.length()<7 || clean.length()>56 || clean.contains("建议做法")
          || clean.contains("用一句话") || clean.contains("待复习") || clean.contains("待学")
          || clean.matches(".*[0-9].*"))return;
      a.ui.post(()->{
        if(a.isDestroyed() || !day.equals(StudyStore.today())
            || !cache.equals(cacheSource(source(),CloudAi.selected(prefs()))))return;
        prefs().edit().putString(CACHE_DAY,day).putString(CACHE_SOURCE,cache).putString(CACHE_TEXT,clean).apply();
        if(a.screen.equals("atlas") && reminderMessage!=null)reminderMessage.setText(clean);
        if(a.screen.equals("assistant") && assistantMessage!=null)assistantMessage.setText(clean);
      });
    });
  }

  void close() {
    adviceWorker.shutdownNow();
  }
}
