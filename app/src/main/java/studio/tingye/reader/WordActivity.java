package studio.tingye.reader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class WordActivity extends PaperActivity {
  final ExecutorService io = Executors.newSingleThreadExecutor();
  final Handler ui = new Handler(Looper.getMainLooper());
  Lexicon lex;
  StudyStore data;
  Voice voice;
  private final DictionaryDetails details=new DictionaryDetails(this);
  private final AppUpdates updates=new AppUpdates(this);
  String screen = "role_chat", query = "", detailFrom = "search", attachKey = "";
  String quoteFrom = "home";
  String labFrom = "home", restoredQuiz = "", bookQuery = "";
  int restoredAnswer = -1, detailScroll, bookFilter;
  private StudyLab lab;
  private MemoryPage memory;
  private final StudyAssistant assistant=new StudyAssistant(this);
  private final AiPanel ai=new AiPanel(this);
  private final RoleChat roleChat=new RoleChat(this);
  private String assistantFrom="home";
  private final PocketNotebook notebook = new PocketNotebook(this);
  Lexicon.Word shown;
  int answer = -1, searchGeneration, limit = 24;
  private long lastDirectGrade;
  private int historyOffset=-1;
  private boolean maySwipeHistory;
  private float historyX,historyY;
  String quizKey = "";
  List<String> choices = new ArrayList<>();
  List<String> scanned = new ArrayList<>();
  private PhotoText photoText = new PhotoText("");
  private String photoNotice = "";
  final OfflineTranslation translations=new OfflineTranslation(this);
  private final PhotoTranslator photoTranslator = new PhotoTranslator(this);
  private int photoWordLimit = 32;
  LinearLayout matches, photoTools, photoFooter;
  EditText searchBox;
  boolean scanning;
  private ViewTreeObserver.OnGlobalLayoutListener keyboardListener;
  int searchTitleCount;
  private int resolvingGeneration = -1;
  final LinkedHashSet<String> checked = new LinkedHashSet<>();
  static final int CAMERA = 61, PHOTO = 62, MEMO = 63;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    layout("", false, null, null);

    if (state != null) {
      roleChat.restore(state);
      quoteFrom = state.getString("quoteFrom", "home");
      labFrom = state.getString("labFrom", "home");
      assistantFrom = state.getString("assistantFrom","home");
      restoredQuiz = state.getString("quizKey", "");
      restoredAnswer = state.getInt("answer", -1);
      detailScroll = state.getInt("detailScroll");
      bookQuery = state.getString("bookQuery", "");
      bookFilter = state.getInt("bookFilter");
      query = state.getString("q", "");
      attachKey = state.getString("attach", "");
      screen = state.getString("screen", "home");
      detailFrom = state.getString("detailFrom", "search");
      listBook = state.getString("listBook", "mine");
      listLimit = state.getInt("listLimit", 30);
      photoText = new PhotoText(state.getString("photoText", ""));
      photoNotice = state.getString("photoNotice", "");
      scanned = photoText.russianWords;
      photoWordLimit = state.getInt("photoWordLimit", 32);
      ArrayList<String> ticks = state.getStringArrayList("ticks");
      if (ticks != null) checked.addAll(ticks);
    }
    io.execute(
        () -> {
          try {
            Lexicon l = new Lexicon(this);
            StudyStore s = new StudyStore(this, l);
            ui.post(
                () -> {
                  if (isDestroyed()) {
                    s.close();
                    l.close();
                    return;
                  }
                  lex = l;
                  data = s;
                  voice = new Voice(this, l, io);
                  if (screen.equals("detail") && state != null) {
                    shown = data.word(state.getString("word", ""));
                    if (shown == null && state.containsKey("ru"))
                      shown =
                          new Lexicon.Word(
                              state.getString("word"),
                              state.getString("ru"),
                              state.getString("zh", ""),
                              state.getString("pos", ""));
                    if (shown == null) screen = "search";
                  }
                  if (screen.equals("quiz") && !data.resumable()) screen = "home";
                  show(screen);
                  io.execute(() -> {
                    android.content.res.Resources res = getResources();
                    for (int i = 0; i < Math.min(4, l.books.size()); i++)
                      FieldArt.bitmap(res, coverResource(l.books.get(i)));
                    for (int location : new int[] {3, 4}) {
                      FieldArt.bitmap(res, FieldArt.LAND[location]);
                      FieldArt.bitmap(res, FieldArt.MIST[location]);
                    }
                  });
                  shell.postOnAnimation(
                      () ->
                          shell.postOnAnimation(
                              () -> {
                                if (!isDestroyed()) {
                                  reportFullyDrawn();
                                  android.util.Log.i("Slovo", "STUDY_READY");
                                }
                              }));
                });
          } catch (Exception e) {
            ui.post(() -> fail(e));
          }
        });
  }

  @Override
  public void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    roleChat.save(b);
    b.putString("screen", screen);
    b.putString("quoteFrom", quoteFrom);
    b.putString("labFrom", labFrom);
    b.putString("assistantFrom",assistantFrom);
    b.putString("quizKey", quizKey);
    b.putInt("answer", answer);
    b.putInt("detailScroll", detailScroll);
    b.putString("bookQuery", bookQuery);
    b.putInt("bookFilter", bookFilter);
    b.putString("q", query);
    b.putString("attach", attachKey);
    b.putString("photoText", photoText.text);
    b.putString("photoNotice", photoNotice);
    b.putInt("photoWordLimit", photoWordLimit);
    b.putStringArrayList("ticks", new ArrayList<>(checked));
    b.putString("detailFrom", detailFrom);
    b.putString("listBook", listBook);
    b.putInt("listLimit", listLimit);
    if (shown != null) {
      b.putString("word", shown.k);
      b.putString("ru", shown.ru);
      b.putString("zh", shown.zh);
      b.putString("pos", shown.pos);
    }
  }

  void fail(Throwable e) {
    android.util.Log.e("Slovo", "Operation failed", e);
    if (!isDestroyed())
      new FeedbackDialog(this)
          .setTitle("这次操作未完成")
          .setMessage(e.getLocalizedMessage())
          .setPositiveButton("返回", null)
          .show();
  }

  void tip(String s) {
    if (isFinishing() || isDestroyed() || shell == null || !shell.isAttachedToWindow()) return;
    TextView label = txt(s, 14, white, false);
    label.setBackground(surface(ink, 12, 0));
    pad(label, 15, 9, 15, 9);
    PopupWindow p = new PopupWindow(label, -2, -2, false);
    p.setTouchable(false);
    p.showAtLocation(shell, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(85));
    ui.postDelayed(
        () -> {
          if (p.isShowing()) p.dismiss();
        },
        1800);
  }

  private View riverPhoto;
  private PhotoTransition riverMotion;
  private boolean switchingTab;
  private long pageDrawnAt;

  private void navigateTab(String id) {
    switchingTab = true;
    try { show(id); } finally { switchingTab = false; }
  }
  @Override
  public void onBackPressed() {
    if(screen.equals("home") && historyOffset>=0) {
      backToCurrent();
      return;
    }
    returnToParent();
  }

  private void returnToParent() {
    if (screen.equals("detail")) {
      int y = detailScroll;
      show(detailFrom);
      ScrollView destination = scroll;
      if (y > 0 && !detailFrom.equals("memory") && !detailFrom.equals("lab"))
        destination.post(() -> destination.scrollTo(0, y));
    }
    else if (screen.equals("updates")) show("settings");
    else if (screen.equals("appearance")) show("settings");
    else if (screen.equals("reminder_settings")) show("settings");
    else if (screen.equals("keepsakes")) show("quotes");
    else if (screen.equals("lab")) { if (!lab.back()) show(labFrom); }
    else if (screen.equals("assistant")) show(assistantFrom);
    else if (screen.equals("ai_translate")) show("assistant");
    else if (screen.equals("role_chat")) super.onBackPressed();
    else if (screen.equals("ai_settings")) ai.back();
    else if (screen.equals("memory")) { if (!memory.back()) show("atlas"); }
    else if (screen.equals("settings") || screen.equals("portrait")) show("atlas");
    else if (screen.equals("quotes")) show(quoteFrom);
    else if (screen.equals("credits")) show("settings");
    else if (screen.equals("bookwords")) show("shelf");
    else if (!screen.equals("home")) show("home");
    else super.onBackPressed();
  }

  void hideKeys() {
    View v = getCurrentFocus();
    if (v != null)
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(v.getWindowToken(), 0);
  }

  void show(String next) {
    if (data == null) return;
    String from = screen;
    if (from.equals("role_chat") && !from.equals(next)) roleChat.leave();
    if (!next.equals("home") && !next.equals("quiz") && !next.equals("done")) historyOffset=-1;
    if (from.startsWith("ai_") && !from.equals(next)) ai.leave();
    boolean hadPage = nav != null;
    if (!switchingTab) onContentAction();
    if (getCurrentFocus() instanceof EditText) hideKeys();
    if (keyboardListener != null) {
      shell.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
      keyboardListener = null;
    }
    if (voice != null) voice.stop();
    searchGeneration++;
    if (next.equals("quiz") || next.equals("done")) next = "home";
    boolean acrossRiver = (from.equals("atlas") && next.equals("portrait"))
        || (from.equals("portrait") && next.equals("atlas"));
    if (acrossRiver && riverMotion == null) riverMotion = new PhotoTransition(stage);
    RectF oldRiver = acrossRiver ? riverMotion.capture(riverPhoto) : null;
    if (riverMotion != null) riverMotion.cancel();
    pageDrawnAt = SystemClock.elapsedRealtime();
    riverPhoto = null;
    screen = next;
    place = Math.floorMod(Calendar.getInstance().get(Calendar.DAY_OF_YEAR), FieldArt.LAND.length);
    if (screen.equals("portrait") || screen.equals("atlas")) place = 4;
    boolean sub =
        next.equals("detail")
            || next.equals("settings")
            || next.equals("bookwords")
            || next.equals("portrait")
            || next.equals("quotes")
            || next.equals("lab") || next.equals("memory") || next.equals("appearance") || next.equals("keepsakes")
            || next.equals("credits") || next.equals("assistant") || next.equals("reminder_settings")
            || next.equals("updates")
            || next.startsWith("ai_");
    String name =
        next.equals("home")
            ? "今日斩词"
            : next.equals("shelf")
                ? "词库"
                : next.equals("search")
                    ? "搜词"
                    : next.equals("atlas")
                        ? "我的"
                        : next.equals("settings")
                            ? "设置"
                            : next.equals("bookwords") ? lex.book(listBook).title : "词条";
    if (next.equals("portrait")) name = "我的粒子像";
    if (next.equals("quotes")) name = "俄语明信片";
    if (next.equals("credits")) name = "摄影与书目";
    if (next.equals("lab")) name = "专项练习";
    if (next.equals("assistant")) name = "学习助手";
    if (next.equals("reminder_settings")) name="学习提醒";
    if (next.equals("ai_settings")) name="AI 与翻译";
    if (next.equals("ai_translate")) name="AI 译句";
    if (next.equals("role_chat")) name="俄语对话";
    if (next.equals("updates")) name="应用更新";
    if (next.equals("memory")) name = "记忆曲线";
    if (next.equals("appearance")) name = "风景与动效";
    if (next.equals("keepsakes")) name = "收藏的明信片";
    photoFooter = null;
    answerPanel = answerDock = null; wordFace = null;
    layout(name, sub, this::onBackPressed, next.equals("search") ? this::photoMenu : null);
    if (next.equals("atlas"))
      bar.addView(iconButton(R.drawable.s_gear, "设置", () -> show("settings")), dp(48), dp(48));
    if (next.equals("home")) {
      TextView daily = txt(data.goal==0?"只复习":data.goal+" 词/日",14,blue,true);
      daily.setGravity(Gravity.CENTER);
      daily.setContentDescription("调整每日新词");
      click(daily,paper,9,0,this::openGoal);
      pad(daily,9,0,9,0);
      bar.addView(daily,-2,dp(48));
      TextView change = txt("换词库", 14, blue, true);
      change.setGravity(Gravity.CENTER);
      click(change, paper, 9, 0, () -> show("shelf"));
      pad(change, 10, 0, 10, 0);
      bar.addView(change, -2, dp(48));
    }
    switch (next) {
      case "updates": updates.render(); break;
      case "lab":
        if (lab == null) lab = new StudyLab(this);
        lab.render(); break;
      case "memory":
        if (memory == null) memory = new MemoryPage(this);
        memory.render(); break;
      case "appearance": notebook.appearance(); break;
      case "assistant": assistant.render(); break;
      case "reminder_settings": assistant.settings(); break;
      case "ai_settings": ai.settings(); break;
      case "ai_translate": ai.translation(); break;
      case "role_chat": roleChat.render(); break;
      case "keepsakes": notebook.collection(); break;
      case "shelf":
        shelf();
        break;
      case "search":
        search();
        break;
      case "portrait":
        portrait();
        break;
      case "quotes":
        postcards();
        break;
      case "credits":
        credits();
        break;
      case "atlas":
        atlas();
        break;
      case "detail":
        detail();
        break;
      case "settings":
        settings();
        break;
      case "bookwords":
        bookWords();
        break;
      default:
        home();
    }
    if (!sub) tabs(next, this::navigateTab);
    finishTheme(next);
    if (oldRiver != null) riverMotion.move(oldRiver, riverPhoto);
    else if (hadPage && !from.equals(next)) arrive(scroll,160);

  }

  String listBook = "mine";
  int listLimit = 30;

  void home() {
    try {
      if(historyOffset>=0) {
        StudyStore.PreviousReview previous=data.previous(historyOffset);
        if(previous!=null) {
          reviewHistory(previous);
          return;
        }
        historyOffset=-1;
      }
      if (!data.resumable()
          && !(StudyStore.today().equals(data.session.day)
              && !data.session.active()
              && data.due() == 0
              && data.todayNew() >= data.goal)) data.start();
      if (data.session.active()) quiz(false);
      else done();
    } catch (Exception e) {
      fail(e);
    }
  }

  private View previousButton() {
    View button=iconButton(R.drawable.s_back,"上一词",this::previousWord);
    button.setEnabled(data.previous(historyOffset+1)!=null);
    button.setAlpha(button.isEnabled()?1f:.36f);
    return button;
  }

  private void previousWord() {
    int next=historyOffset+1;
    if(data.previous(next)==null) {
      tip("今天还没有更早的词");
      return;
    }
    historyOffset=next;
    show("home");
  }

  private void backToCurrent() {
    historyOffset=-1;
    show("home");
  }

  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
      Rect frame=new Rect();
      maySwipeHistory=screen.equals("home") && scroll!=null && scroll.getGlobalVisibleRect(frame)
          && frame.contains((int)event.getX(),(int)event.getY()) && event.getX()>dp(24);
      historyX=event.getX();
      historyY=event.getY();
    } else if(event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN
        || event.getActionMasked()==MotionEvent.ACTION_CANCEL) maySwipeHistory=false;
    else if(event.getActionMasked()==MotionEvent.ACTION_UP && maySwipeHistory && screen.equals("home")) {
      maySwipeHistory=false;
      float dx=event.getX()-historyX,dy=event.getY()-historyY;
      if(Math.abs(dx)>dp(66) && Math.abs(dx)>1.65f*Math.abs(dy)
          && (dx>0 || historyOffset>=0)) {
        MotionEvent cancel=MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        if(dx>0) previousWord(); else backToCurrent();
        return true;
      }
    }
    return super.dispatchTouchEvent(event);
  }

  private void reviewHistory(StudyStore.PreviousReview review) {
    LinearLayout top=box(false);
    top.addView(previousButton(),dp(48),dp(48));
    gap(top,10);
    wide(top,txt("刚才练过的词",16,muted,true));
    add(body,top,-2);
    gap(body,13);
    JSONObject hint=lex.usage.optJSONObject(review.word.k);
    add(body,serif(hint==null?review.word.ru:hint.optString("stress",review.word.ru),34,ink),-2);
    phonetics(body,review.word.k,muted);
    gap(body,10);
    add(body,txt(review.word.zh,22,ink,true),-2);
    gap(body,10);
    String[] grades={"忘记","模糊","我认识"};
    if(review.grade>=0 && review.grade<grades.length)
      add(body,txt("这次作答 · "+grades[review.grade],15,muted,false),-2);
    gap(body,14);
    add(body,action("听发音",R.drawable.s_audio,false,()->voice.play(review.word.ru)),-2);
    usage(body,review.word,hint);
    gap(body,14);
    add(body,action("返回当前词",R.drawable.s_arrow,true,this::backToCurrent),-2);
  }

  void settings() {
    gap(body, 20);
    LinearLayout brand = box(false);
    landscape(brand, 0, FieldArt.LIGHT, 14);
    pad(brand, 15, 14, 12, 14);
    ImageView logo = new ImageView(this) {
      private final Path contour=new Path();
      @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        super.onSizeChanged(w,h,oldW,oldH);
        SoftCorners.trace(contour,0,0,w,h,dp(18));
      }
      @Override protected void onDraw(Canvas canvas) {
        int save=canvas.save();
        canvas.clipPath(contour);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
      }
    };
    logo.setImageResource(R.drawable.slovo_icon);
    logo.setContentDescription("词汐应用图标");
    logo.setBackground(surface(ink, 18, 0));
    brand.addView(logo, dp(76), dp(76));
    gap(brand, 16);
    LinearLayout text = box(true);
    add(text, txt("词汐", 25, ink, true), -2);
    gap(text, 4);
    add(text, txt("俄语背词  ·  0.9.0", 14, muted, false), -2);
    wide(brand, text);
    add(body, brand, -2);
    section("学习", "");
    row(
        body,
        "每日新词",
        DailyGoal.label(data.goal) + " · " + lex.book(data.plan).title,
        R.drawable.s_options,
        this::openGoal);
    row(body,"学习提醒",assistant.sourceLabel(),R.drawable.s_orb,()->show("reminder_settings"));
    row(body, "风景与动效", "随页面和词卡换景，或固定一处实景", R.drawable.s_image, notebook::openAppearance);
    section("画质", "");
    settingSwitch("风景柔光","在淡入淡出上加一层短暂的柔光",SceneryPane.CROSSFADE);
    settingSwitch("暂停对话学习记录","只在本机保留每轮练习的词和任务，用来调整荐词和粒子像","pauseChatLearning");
    row(body,"清空对话学习记录","保留原有背词进度",R.drawable.s_book,()->new FeedbackDialog(this).setTitle("清空对话学习记录？")
      .setNegativeButton("取消",null).setPositiveButton("清空",(d,i)->{io.execute(()->{data.clearChat();ui.post(()->tip("已清空"));});}).show());
    settingSwitch("粒子高画质","提高个人粒子像的密度、清晰度和帧率",ParticlePortrait.HIGH_QUALITY);
    section("扩展", "");
    row(body,"AI 与翻译","手机模型与自带密钥",R.drawable.s_gear,()->openAiSettings("settings"));
    row(
        body,
        "课堂听读",
        "录音、译文和讲义",
        R.drawable.s_book,
        () -> startActivity(new Intent(this, ReaderActivity.class)));
    section("关于", "");
    row(body,"检查更新","国内加速下载 · GitHub 发布",R.drawable.s_arrow,()->show("updates"));
    add(body,txt("开发者",15,blue,true),-2);
    gap(body,5);
    add(body,txt("小陈同学 · 小余同学",16,ink,false),-2);
    gap(body,10);
    row(body,"开源许可","语音、词典、模型与素材来源",R.drawable.s_book,this::licenses);
    row(body, "摄影与书目", "风景地点、拍摄者与参考书", R.drawable.s_image, () -> show("credits"));
    row(
        body,
        "词典与发音",
        "",
        R.drawable.s_book,
        () ->
            new FeedbackDialog(this)
                .setTitle("词典与发音")
                .setMessage(
                    "每册由主题词和分级拓展词组成。中文释义来自中文维基词典贡献者与 Kaikki（CC BY-SA 4.0）。"
                        + "重音参考 OpenRussian，常用度参考 wordfreq。词形参考"
                        + " OpenCorpora。重音与词形取自 OpenRussian（CC BY-SA 4.0）。所有俄语词形与整句可用内置 Denis 离线语音，语音引擎为 sherpa-onnx。详细变化表来自 OpenRussian，双语例句来自 Tatoeba。")
                .setPositiveButton("关闭", null)
                .show());
    row(
        body,
        "复习间隔",
        "已校准 " + data.adjustments + " 次",
        R.drawable.s_orb,
        () ->
            new FeedbackDialog(this)
                .setTitle("复习间隔")
                .setMessage("容易忘的词会早点回来。隔一段时间再答一次，软件会用这次结果调整你的复习间隔。")
                .setPositiveButton("关闭", null)
                .show());
  }

  private void licenses() {
    try {
      String[] files=getAssets().list("notices");
      new FeedbackDialog(this).setTitle("许可与来源").setItems(files,(dialog,index)->io.execute(()->{
        try(InputStream input=getAssets().open("notices/"+files[index]);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
          byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);
          String text=new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
          ui.post(()->{if(!isDestroyed())new FeedbackDialog(this).setTitle(files[index]).setMessage(text).setPositiveButton("关闭",null).show();});
        } catch(IOException error) { ui.post(()->tip("没有打开这份说明")); }
      })).setNegativeButton("返回",null).show();
    } catch(IOException error) { tip("没有打开许可列表"); }
  }

  String dictionaryContext(String message) {
    if(!message.matches("[А-Яа-яЁё́-]{3,40}") && !message.matches("(?s).*(意思|用法|怎么|教我|解释).*"))return "";
    java.util.regex.Matcher token=java.util.regex.Pattern.compile("[А-Яа-яЁё́-]{3,40}").matcher(message);
    if(!token.find())return "";
    try {
      JSONObject result=details.resolve(token.group());
      if(result==null)return "";
      JSONObject entry=result.getJSONObject("entry");
      String context="\n词典参考（围绕这些已收录资料教学，不要添加无依据的词义）："+entry.optString("word")+"，"+entry.optString("aspect")+"。";
      JSONArray senses=entry.optJSONArray("senses");
      if(senses!=null)for(int i=0;i<Math.min(3,senses.length());i++)context+=senses.optString(i)+"；";
      String notes=entry.optString("notes");
      if(!notes.isEmpty())context+=notes;
      return context.length()>600?context.substring(0,600):context;
    } catch(Exception unavailable) { return ""; }
  }

  void learnFromChat(String task,String message,String reply) {
    if(getPreferences(0).getBoolean("pauseChatLearning",false))return;
    java.util.Set<String> keys=new LinkedHashSet<>();
    // Fold inflections on the dictionary worker. Prefer the learner’s own vocabulary.
    io.execute(()->{
      for(String token:new PhotoText(message+" "+reply).russianWords) {
        if(keys.size()>=24)break;
        String key=Lexicon.key(token);
        try {
          JSONObject found=details.resolve(key);
          if(found==null)continue;
          key=Lexicon.key(found.getJSONObject("entry").optString("word",key));
          if(key.length()>2)keys.add(key);
        } catch(Exception ignored) {}
      }
      if(!isDestroyed() && !getPreferences(0).getBoolean("pauseChatLearning",false))data.recordChat(task,keys);
    });
  }

  void openTutor(String word) { roleChat.fromWord(word); }

  void openAiSettings(String from) { ai.open(from); }

  void settingSwitch(String name,String detail,String key) {
    LinearLayout row=box(false), text=box(true);
    pad(row,12,9,12,9);
    add(text,txt(name,16,ink,true),-2);
    gap(text,3);
    add(text,txt(detail,13,muted,false),-2);
    wide(row,text);
    Switch toggle=new Switch(this);
    toggle.setContentDescription(name);
    toggle.setMinHeight(dp(48));
    toggle.setChecked(getPreferences(0).getBoolean(key,false));
    toggle.setOnCheckedChangeListener((button, checked)->{
      getPreferences(0).edit().putBoolean(key,checked).apply();
      if(key.equals(LocalLanguageModel.ENABLED) && !checked) LocalLanguageModel.stop();
      button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    });
    row.addView(toggle,dp(53),dp(48));
    touchAction(row,toggle::toggle);
    add(body,row,-2);
    rule(body);
  }

  void bookWords() {
    Lexicon.Book book = lex.book(listBook);
    gap(body, 12);
    LinearLayout bookHeader = box(false);
    ImageView edition = new ImageView(this);
    edition.setScaleType(ImageView.ScaleType.FIT_CENTER);
    edition.setImageBitmap(FieldArt.bitmap(getResources(), coverResource(book)));
    bookHeader.addView(edition, dp(65), dp(89));
    gap(bookHeader, 14);
    wide(bookHeader, txt(book.sub + "　·　" + book.words.size() + " 词", 15, ink, true));
    add(body, bookHeader, -2);
    gap(body, 9);
    rule(body);
    if (book.words.isEmpty()) {
      add(body, txt("还没收词。拍照或搜词时可以添加。", 15, muted, false), -2);
      return;
    }
    EditText filter = new EditText(this);
    filter.setHint("在这本词库中找词"); filter.setSingleLine(); filter.setTextSize(16);
    filter.setText(bookQuery); filter.setSelection(bookQuery.length());
    add(body,filter,52); gap(body,7);
    LinearLayout controls = box(false), list = box(true);
    String[] filters = {"全部","待复习","未学"};
    for (int i=0; i<filters.length; i++) {
      final int index=i;
      if (i>0) gap(controls,4);
      View choice=action(filters[i],0,bookFilter==i,()->{
        bookFilter=index; listLimit=30; show("bookwords");
      });
      choice.setSelected(bookFilter==i); wide(controls,choice);
    }
    add(body,controls,-2); add(body,list,-2);
    if (book.themeCount > 0 && book.themeCount < book.words.size()) {
      View topics=action("只看本册主题词 · "+book.themeCount,0,bookFilter==3,()->{
        bookFilter=bookFilter==3?0:3; listLimit=30; show("bookwords");
      });
      topics.setSelected(bookFilter==3);
      int index=body.indexOfChild(list);
      body.addView(topics,index,new LinearLayout.LayoutParams(-1,-2));
    }
    drawWordList(book,list);
    filter.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s,int st,int n,int a) {}
      public void afterTextChanged(Editable e) {}
      public void onTextChanged(CharSequence s,int st,int b,int c) {
        bookQuery=s.toString(); listLimit=30; drawWordList(book,list);
      }
    });
  }

  private void drawWordList(Lexicon.Book book, LinearLayout list) {
    list.removeAllViews();
    String q = Lexicon.key(bookQuery); int count=0;
    long now=System.currentTimeMillis();
    int end = bookFilter==3 ? Math.min(book.themeCount,book.words.size()) : book.words.size();
    for (int i=0; i<end; i++) {
      String key=book.words.get(i);
      Lexicon.Word w=data.word(key);
      if (w==null || (!Lexicon.key(w.ru).contains(q) && !w.zh.contains(q))) continue;
      StudyStore.Card c=data.cards.get(key);
      if (bookFilter==1 && (c==null || c.seen==0 || c.due>now)) continue;
      if (bookFilter==2 && c!=null && c.seen>0) continue;
      count++;
      if(count<=listLimit) row(list,w.ru,brief(w.zh),R.drawable.s_arrow,()->open(w,"bookwords"));
    }
    if (count==0) add(list,txt("这本里没有符合条件的词。",15,muted,false),52);
    if (count>listLimit) {
      add(list,action("再看 "+Math.min(30,count-listLimit)+" 个词",0,false,()->{
        int y=scroll.getScrollY(); listLimit+=30; drawWordList(book,list);
        ScrollView view=scroll; view.post(()->view.scrollTo(0,y));
      }),-2);
    }
  }

  void metric(LinearLayout p, String label, int n) {
    LinearLayout v = box(true);
    v.setGravity(Gravity.CENTER);
    TextView number = txt("" + n, 23, blue, true), caption = txt(label, 13, muted, false);
    number.setGravity(Gravity.CENTER);
    caption.setGravity(Gravity.CENTER);
    v.addView(number);
    gap(v, 4);
    v.addView(caption);
    wide(p, v);
  }

  void shelf() {
    gap(body, 14);
    add(body, txt("今天", 15, muted, true), -2);
    gap(body, 7);
    LinearLayout counts = box(false);
    landscape(counts, place, FieldArt.LIGHT, 14);
    pad(counts, 5, 12, 5, 12);
    metric(counts, "待复习", data.due());
    metric(counts, "待学新词", data.newRemaining());
    metric(counts, "已练过", data.todayCount());
    add(body, counts, -2);
    section("我的词库", (lex.books.size()-1) + " 册 · 分级词表");
    String[] groups = {"", "travel", "university", "modern", "school", "four", "six", "mine"};
    String[] labels = {"全部系列", "走遍俄罗斯", "东方大学俄语", "新一代大学俄语", "中学与高考",
        "大学俄语四级", "大学俄语六级", "我的生词本"};
    Spinner picker = new Spinner(this);
    picker.setContentDescription("按系列找书");
    picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    picker.setSelection(shelfSeries);
    add(body, picker, 50);
    LinearLayout catalog = box(true);
    LinearLayout library=secondary();
    if(library!=body) {
      Lexicon.Book current=lex.book(data.plan);
      section("正在学","");
      add(body,serif(current.title,23,ink),-2);
      add(body,action("继续斩词",R.drawable.s_arrow,true,()->show("home")),-2);
    }
    add(library, catalog, -2);
    shelfBooks(catalog, groups[shelfSeries], groups, labels);
    picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
          int position, long id) {
        if (position == shelfSeries) return;
        shelfSeries = position;
        shelfBooks(catalog, groups[position], groups, labels);
      }
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
  }

  private int shelfSeries;

  private int coverResource(Lexicon.Book book) {
    int resource = getResources().getIdentifier(book.cover, "drawable", getPackageName());
    return resource == 0 ? R.drawable.book_mine : resource;
  }

  private void shelfBooks(LinearLayout catalog, String series, String[] groups, String[] labels) {
    catalog.removeAllViews();
    String previous = "";
    for (Lexicon.Book b : lex.books) {
      if (!series.isEmpty() && !b.series.equals(series)) continue;
      if (!b.series.equals(previous)) {
        int index = java.util.Arrays.asList(groups).indexOf(b.series);
        if (index > 0) section(catalog, labels[index], "");
        previous = b.series;
      }
      LinearLayout r = box(false);
      click(r, paper, 3, 0, () -> choose(b));
      pad(r, 11, 9, 11, 9);
      ImageView cover = new ImageView(this);
      cover.setImageBitmap(FieldArt.bitmap(getResources(), coverResource(b)));
      cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
      cover.setContentDescription(b.reference);
      r.addView(cover, dp(60), dp(82));
      gap(r, 13);
      LinearLayout t = box(true);
      add(t, txt(b.title, 18, ink, true), -2);
      gap(t, 4);
      add(t, txt(b.sub, 13, muted, false), -2);
      gap(t, 3);
      int learned = 0;
      for (String k : b.words) {
        StudyStore.Card c = data.cards.get(k);
        if (c != null && c.seen > 0) learned++;
      }
      add(
          t,
          txt(
              learned + " / " + b.words.size() + " 词" + (data.plan.equals(b.k) ? "   ·   在学" : ""),
              12,
              blue,
              true),
          -2);
      if (data.plan.equals(b.k) && !b.words.isEmpty()) {
        gap(t, 5);
        ProgressBar track = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        track.setMax(b.words.size());
        track.setProgress(learned);
        track.setProgressTintList(android.content.res.ColorStateList.valueOf(blue));
        add(t, track, 4);
      }
      wide(r, t);
      sym(r, R.drawable.s_arrow, muted, 15);
      add(catalog, r, -2);
      rule(catalog);
    }
  }

  void choose(Lexicon.Book b) {
    LinearLayout wrap = box(true);
    pad(wrap, 22, 8, 22, 5);
    if (!b.k.equals("mine")) {
      add(wrap, txt("封面参考书 · " + b.reference, 14, ink, false), -2);
      gap(wrap, 5);
    }
    add(wrap, txt(b.sub + "  ·  " + b.words.size() + " 词", 14, muted, false), -2);
    if (b.themeCount > 0 && b.themeCount < b.words.size()) {
      gap(wrap,6);
      add(wrap,txt("本册主题 "+b.themeCount+" 词 · 分级拓展 "+(b.words.size()-b.themeCount)+" 词",
          13,muted,false),-2);
    }
    gap(wrap, 12);
    List<String> sample = new ArrayList<>();
    for (int i = 0; i < Math.min(3, b.words.size()); i++) sample.add(data.word(b.words.get(i)).ru);
    add(wrap, serif(android.text.TextUtils.join("   ·   ", sample), 17, ink), -2);
    gap(wrap, 14);
    DailyGoal.show(this,b.title,wrap,data.goal,true,goal->applyGoal(b.k,goal));
  }

  private void openGoal() {
    LinearLayout wrap=box(true);
    pad(wrap,22,5,22,7);
    DailyGoal.show(this,"调整学习目标",wrap,data.goal,false,goal->applyGoal(data.plan,goal));
  }

  private void applyGoal(String book,int goal) {
    try {
      data.plan(book,goal);
      quizKey = "";
      answer = -1;
      show("home");
    } catch (android.database.SQLException error) {
      fail(error);
    }
  }

  void start() {
    try {
      data.start();
      answer = -1;
      quizKey = "";
      show(data.session.active() ? "quiz" : "done");
    } catch (Exception e) {
      fail(e);
    }
  }

  List<String> options(Lexicon.Word w) {
    ArrayList<String> result = new ArrayList<>();
    result.add(w.zh);
    ArrayList<Lexicon.Word> words = new ArrayList<>(lex.core.values());
    Random random =
        new Random(w.k.hashCode() * 31L + data.session.index + data.session.day.hashCode());
    Collections.shuffle(words, random);
    List<String[]> meanings = new ArrayList<>();
    meanings.add(StudyLabEngine.senses(w.zh));
    for (Lexicon.Word x : words) {
      String[] candidate=StudyLabEngine.senses(x.zh);
      if (meanings.stream().noneMatch(m -> StudyLabEngine.overlappingMeanings(m,candidate))) {
        result.add(x.zh);
        meanings.add(candidate);
      }
      if (result.size() == 3) break;
    }
    Collections.shuffle(result, random);
    return result;
  }

  private LinearLayout answerPanel, answerDock;
  private View wordFace;

  void quiz(boolean advance) {
    if (!data.session.active()) {
      show("done");
      return;
    }
    Lexicon.Word w = data.word(data.session.queue.get(data.session.index));
    boolean entering = !w.k.equals(quizKey);
    if (entering) {
      quizKey = w.k;
      choices = options(w);
      answer = w.k.equals(restoredQuiz) && restoredAnswer >= -2 && restoredAnswer <= 4 ? restoredAnswer : -1;
      restoredQuiz = "";
    }
    int index = data.session.index, total = data.session.queue.size();
    LinearLayout meta = box(false);
    meta.addView(previousButton(),dp(48),dp(48));
    gap(meta,8);
    wide(meta, txt(lex.book(data.plan).title, 14, muted, true));
    meta.addView(txt((index + 1) + " / " + total, 14, blue, true));
    gap(body, 12);
    add(body, meta, -2);
    gap(body, 10);
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(total);
    progress.setProgress(advance ? index : index + 1);
    if (advance) progress.setProgress(index + 1, android.animation.ValueAnimator.areAnimatorsEnabled());
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(blue));
    add(body, progress, 5);
    gap(body, 16);
    StudyStore.Card rec = data.cards.get(w.k);
    JSONObject hint = lex.usage.optJSONObject(w.k);
    String stressed = hint == null ? w.ru : hint.optString("stress", w.ru);
    LinearLayout card = box(true);
    scenicCard(card);
    pad(card, 18, 12, 16, 16);
    LinearLayout head = box(false);
    TextView locality = txt("", 13, 0xffffffff, true);
    locality.setSingleLine(true);
    locality.setEllipsize(android.text.TextUtils.TruncateAt.END);
    scenicCardLocation(locality);
    locality.setShadowLayer(dp(3), 0, 1, 0xcc142c29);
    wide(head, locality);
    ImageButton sound = new ImageButton(this);
    sound.setImageResource(R.drawable.s_audio);
    sound.setContentDescription("听发音");
    pad(sound, 12, 12, 12, 12);
    sound.setColorFilter(white);
    glass(sound, true, () -> voice.play(w.ru));
    head.addView(sound, dp(48), dp(48));
    add(card, head, -2);
    gap(card, tablet()?65:22);
    TextView word = serif(stressed, w.ru.length() > 14 ? 30 : w.ru.length() > 10 ? 36 : 46, white);
    float available=dp(getResources().getConfiguration().screenWidthDp-76);
    float measured=word.getPaint().measureText(stressed);
    if(available>0 && measured>available)
      word.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
          Math.max(dp(18),word.getTextSize()*available/measured));
    word.setShadowLayer(dp(3), 0, 1, 0xaa102720);
    word.setContentDescription(w.ru);
    add(card,word,-2);
    phonetics(card,w.k,white);
    if (!w.pos.isEmpty()) {
      gap(card, 10);
      add(
          card,
          txt(
              (rec == null || rec.seen == 0 ? "新词" : "复习") + "   ·   " + w.pos,
              14,
              0xffffffff,
              false),
          -2);
    }
    if(tablet())card.setMinimumHeight(dp(getResources().getConfiguration().screenWidthDp>=840?310:340));
    add(body, card, -2);
    if (advance) arrive(card, 160);
    answerPanel = box(true);
    add(secondary(), answerPanel, -2);
    answerDock = box(false);
    dock(answerDock);
    wordFace = card;
    touchAction(card, () -> { if (answer == -1) reveal(4); });
    renderAnswer(w, rec, hint);
  }

  private void renderAnswer(Lexicon.Word w, StudyStore.Card rec, JSONObject hint) {
    LinearLayout buttons = answerDock;
    wordFace.setClickable(answer == -1);
    if (answer == -1) {
      row(answerPanel,"学习助手","按可用时间安排今天的词",R.drawable.s_orb,()->openAssistant("home"));

      row(answerPanel, "专项练习", "听音、拼写与词形", R.drawable.s_book, () -> openLab("home"));
      wide(buttons, answerAction("看选项", false, () -> reveal(-2)));
      gap(buttons, 6);
      wide(buttons, answerAction("看释义", false, () -> reveal(4)));
      gap(buttons, 6);
      wide(buttons, answerAction("我认识", true, () -> {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDirectGrade < 420) return;
        lastDirectGrade = now;
        grade(w.k, 2);
      }));
    } else if (answer == -2) {
      section(answerPanel, "选词义", "");
      for (int i = 0; i < choices.size(); i++) {
        final int n = i;
        add(answerPanel, action(choices.get(i), 0, false, () -> reveal(n)), -2);
        gap(answerPanel, 6);
      }
      wide(buttons, answerAction("看释义", false, () -> reveal(4)));
    } else {
      boolean tried = answer >= 0 && answer < 3;
      boolean correct = tried && choices.get(answer).equals(w.zh);
      section(answerPanel, tried && correct ? "答对了" : "词义", "");
      add(answerPanel, txt(w.zh, 23, ink, true), -2);
      if (tried && !correct) {
        gap(answerPanel, 4);
        add(answerPanel, txt("刚才选了「" + choices.get(answer) + "」", 14, muted, false), -2);
      }
      usage(answerPanel, w, hint);
      if (rec != null && !rec.note.isEmpty()) {
        section(answerPanel, "我的联想", "");
        add(answerPanel, txt(rec.note, 15, ink, false), -2);
      }
      wide(buttons, rating("没记住", w.k, 0));
      gap(buttons, 5);
      wide(buttons, rating("有点模糊", w.k, 1));
      if (!tried || correct) {
        gap(buttons, 5);
        wide(buttons, rating("记住了", w.k, 2));
      }
    }
  }

  private View answerAction(String label, boolean primary, Runnable onTap) {
    View button = action(label, 0, primary, onTap);
    joinedGlass(button, primary);
    return button;
  }

  private View rating(String label, String key, int grade) {
    long now=System.currentTimeMillis();
    long minutes=Math.max(1,Math.round((data.estimate(key,grade,now).card.due-now)/60000d));
    String later=minutes<60 ? minutes+" 分钟后" : minutes<1440 ? "约 "+Math.round(minutes/60d)+" 小时后" : "约 "+Math.round(minutes/1440d)+" 天后";
    LinearLayout button=box(true); button.setGravity(Gravity.CENTER);
    button.setMinimumHeight(dp(56)); pad(button,3,6,3,6);
    glass(button,grade==2,()->grade(key,grade));
    joinedGlass(button,grade==2);
    button.setContentDescription(label); button.setTooltipText(later);
    if(Build.VERSION.SDK_INT>=30) button.setStateDescription(later);
    TextView name=txt(label,16,grade==2?white:ink,true), time=txt(later,12,grade==2?white:muted,false);
    name.setGravity(Gravity.CENTER); time.setGravity(Gravity.CENTER);
    add(button,name,-2); gap(button,2); add(button,time,-2);
    return button;
  }

  void dock(LinearLayout controls) {
    int edge=16;
    pad(controls, edge, 8, edge, 8);
    scenicDock(controls);
    shell.addView(controls, new LinearLayout.LayoutParams(-1, -2));
  }

  private void phonetics(LinearLayout host,String key,int color) {
    JSONObject entry=lex.reviewEvidence.optJSONObject(key);
    String reading=entry==null?"":entry.optString("ipa");
    boolean derived=entry!=null && entry.optBoolean("derived");
    if(reading.isEmpty() && "detail".equals(screen))return; // Full dictionary draws its own IPA.
    TextView ipa=txt(reading.isEmpty()?"":"IPA  "+reading+(derived?"  ·  按重音推导":""),16,color,false);
    if(color==white)ipa.setShadowLayer(dp(3),0,1,0xcc102720);
    pad(ipa,0,4,0,0);add(host,ipa,-2);
    if(!reading.isEmpty())return;
    ipa.setVisibility(View.GONE);
    io.execute(()->{
      try{
        JSONObject value=details.resolve(key);
        if(value==null)return;
        // A lemma's transcription cannot be used for the inflected word on the card.
        JSONObject exact=value.has("form")?value:value.optJSONObject("entry");
        String transcription=exact==null?"":exact.optString("ipa");
        if(!transcription.isEmpty())ui.post(()->{
          if(!ipa.isAttachedToWindow())return;
          ipa.setText("IPA  "+transcription);ipa.setVisibility(View.VISIBLE);
        });
      }catch(Exception error){android.util.Log.w("Slovo","Personal word pronunciation",error);}
    });
  }

  private void reviewSentence(LinearLayout host,String ru,String zh,boolean machine) {
    section(host,"例句","");
    LinearLayout row=box(false);
    TextView words=serif(ru,19,ink);
    selectableWords(words,screen);
    wide(row,words);
    row.addView(iconButton(R.drawable.s_audio,"朗读例句",()->voice.playExtracted(ru)),dp(48),dp(48));
    add(host,row,-2);
    if(!zh.isEmpty()){
      TextView translation=txt((machine?"参考机译 · ":"")+zh,15,muted,false);
      translation.setTag("review.translation");
      add(host,translation,-2);
    }else {
      TextView translation=txt("翻译例句",15,blue,true);
      translation.setTag("review.translation");
      translation.setMinimumHeight(dp(48));translation.setGravity(Gravity.CENTER_VERTICAL);
      touchAction(translation,()->{
        if(!getPreferences(0).getBoolean(LocalLanguageModel.ENABLED,false)){
          new AlertDialog.Builder(this).setMessage("开启手机 AI 后，可以离线翻译这条例句。")
              .setNegativeButton("取消",null).setPositiveButton("开启并翻译",(dialog,id)->{
                getPreferences(0).edit().putBoolean(LocalLanguageModel.ENABLED,true).apply();
                translateExample(ru,translation);
              }).show();
        }else translateExample(ru,translation);
      });
      add(host,translation,-2);
    }
  }

  private void translateExample(String ru,TextView target) {
    target.setEnabled(false);target.setText("正在翻译…");
    io.execute(()->{
      String result;
      try{
        String cached=translations.find(ru);
        result=cached.isEmpty()?"参考机译 · "+translations.infer(ru):cached;
      }catch(Exception exception){result="重试翻译";}
      String text=result;
      ui.post(()->{
        if(!target.isAttachedToWindow())return;
        target.setText(text);target.setEnabled(text.equals("重试翻译"));
      });
    });
  }

  void usage(LinearLayout host,Lexicon.Word word,JSONObject hint) {
    String ru=hint==null?"":hint.optString("example");
    String zh=hint==null?"":hint.optString("translation");
    JSONObject evidence=lex.reviewEvidence.optJSONObject(word.k);
    JSONArray pair=evidence==null?null:evidence.optJSONArray("line");
    if(pair!=null && (ru.isEmpty() || evidence.has("meaning") || "词汐".equals(pair.optString(4))))
      reviewSentence(host,pair.optString(0),pair.optString(1),evidence.optBoolean("machine"));
    else if(!ru.isEmpty())reviewSentence(host,ru,zh,false);
    else {
      LinearLayout example=box(true);add(host,example,-2);
      io.execute(()->{
        JSONArray line=null;
        try {
          JSONObject result=details.resolve(word.k);
          JSONObject entry=result==null?null:result.optJSONObject("entry");
          JSONArray examples=entry==null?null:entry.optJSONArray("examples");
          if(examples!=null)line=examples.optJSONArray(0);
        } catch(Exception error){android.util.Log.w("Slovo","Review sentence lookup",error);}
        JSONArray found=line;
        ui.post(()->{
          if(!example.isAttachedToWindow())return;
          if(found!=null && !found.optString(0).isEmpty())
            reviewSentence(example,found.optString(0),found.optString(1),false);
          else add(example,action("请俄语老师举例",R.drawable.s_orb,false,()->roleChat.fromWord(word.ru)),-2);
        });
      });
    }
    String forms = hint==null?"":hint.optString("forms");
    if (!forms.isEmpty()) {
      section(host, "常见词形", "");
      add(host, txt(forms, 15, ink, false), -2);
    }
  }

  void reveal(int n) {
    if (answer >= 0) return;
    answer = n;
    answerPanel.removeAllViews();
    answerDock.removeAllViews();
    Lexicon.Word w = data.word(quizKey);
    renderAnswer(w, data.cards.get(w.k), lex.usage.optJSONObject(w.k));
    finishTheme(screen);
    arrive(answerPanel,150);
    if(n>=0){
      TextView translation=answerPanel.findViewWithTag("review.translation");
      if(translation!=null)translation.post(()->{
        if(!translation.isAttachedToWindow())return;
        Rect bounds=new Rect(0,0,translation.getWidth(),translation.getHeight()+dp(8));
        translation.requestRectangleOnScreen(bounds,!android.animation.ValueAnimator.areAnimatorsEnabled());
      });
    }
  }

  void grade(String key, int grade) {
    try {
      if (!StudyStore.today().equals(data.session.day)) {
        quizKey = ""; answer = -1; show("home"); tip("新的一天，已更新今天的词卡"); return;
      }
      data.grade(key, grade, System.currentTimeMillis());
      quizKey = "";
      answer = -1;
      voice.stop();
      scroll.scrollTo(0, 0);
      body.removeAllViews();
      if(sideBody!=null){sideScroll.scrollTo(0,0);sideBody.removeAllViews();}
      shell.removeView(answerDock);
      if (data.session.active()) {
        nextStudyScenery();
        quiz(true);
      }
      else done();
      finishTheme(screen);
    } catch (Exception e) {
      fail(e);
    }
  }

  void done() {
    LinearLayout landscape=box(false);
    scenicCard(landscape);
    add(body,landscape,100);
    gap(body, 18);
    add(body,previousButton(),48);
    boolean emptyBook=lex.book(data.plan).words.isEmpty();
    int remainingNew=data.newRemaining(), due=data.due();
    boolean more = due>0 || remainingNew>0;
    String heading=more ? "这一轮练完了" : emptyBook ? "这本还没有词"
        : data.goal==0 ? "到期复习已完成" : "今天的新词练完了";
    add(body, txt(heading, 29, ink, true), -2);
    gap(body, 14);
    add(body, txt("今天练过 " + data.todayCount() + " 个词", 16, muted, false), -2);
    gap(body, 22);
    if (more) {
      add(body,txt("待复习 "+due+" 词 · 待学 "+remainingNew+" 词",16,muted,false),-2);
      gap(body,9);
      add(body,action("继续下一轮",R.drawable.s_arrow,true,this::start),-2);
      gap(body,8);
    }
    int learned=0;
    for (StudyStore.Card c:data.cards.values()) if(c.seen>0) learned++;
    boolean canReview=learned>0;
    add(
        body,
        action(
            canReview ? "再练 "+Math.min(5,learned)+" 个词" : "去搜词",
            R.drawable.s_arrow,
            !more,
            () -> {
              if (!canReview) {
                show("search");
                return;
              }
              try {
                data.extraRound();
                quizKey = "";
                answer = -1;
                show("home");
              } catch (Exception e) {
                fail(e);
              }
            }),
        -2);
    gap(body, 9);
    add(body, action("换一本词库", R.drawable.s_shelf, false, () -> show("shelf")), -2);
    row(body,"学习助手","按可用时间安排今天的词",R.drawable.s_orb,()->openAssistant("home"));
    row(body, "专项练习", "自己挑一项练习", R.drawable.s_book, () -> openLab("home"));
  }

  void search() {
    gap(body, 12);
    searchTitleCount = body.getChildCount();
    LinearLayout field = box(false);
    field.setBackground(surface(white, 12, line));
    pad(field, 13, 0, 7, 0);
    sym(field, R.drawable.s_search, muted, 21);
    gap(field, 7);
    searchBox = new EditText(this);
    searchBox.setTextSize(18);
    searchBox.setTextColor(ink);
    searchBox.setHintTextColor(muted);
    searchBox.setHint("输入单词或中文");
    searchBox.setBackgroundColor(0);
    searchBox.setSingleLine();
    searchBox.setMinimumHeight(dp(53));
    searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    searchBox.setText(query);
    searchBox.setSelection(query.length());
    wide(field, searchBox);
    field.addView(
        iconButton(R.drawable.s_close, "清空搜索", () -> searchBox.setText("")), dp(48), dp(48));
    add(body, field, -2);
    gap(body, 10);
    photoTools = box(false);
    wide(photoTools, action("拍照取词", R.drawable.s_camera, false, () -> takePhoto()));
    gap(photoTools, 8);
    wide(photoTools, action("从相册选", R.drawable.s_image, false, () -> pick(PHOTO)));
    add(body, photoTools, -2);
    matches = box(true);
    add(secondary(), matches, -2);
    searchBox.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int n, int a) {}

          public void onTextChanged(CharSequence s, int st, int b, int c) {
            query = s.toString();
            lookup();
          }

          public void afterTextChanged(Editable e) {}
        });
    searchBox.setOnEditorActionListener(
        (v, id, event) -> {
          hideKeys();
          lookup();
          return true;
        });
    observeSearchKeyboard();
    lookup();
  }

  private void observeSearchKeyboard() {
    final LinearLayout page = shell;
    Rect frame = new Rect();
    keyboardListener = () -> {
      page.getWindowVisibleDisplayFrame(frame);
      boolean ime = page.getRootView().getHeight() - frame.bottom > dp(140);
      showTabs(!ime);
    };
    page.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
  }

  void lookup() {
    int seq = ++searchGeneration;
    String q = query.trim();
    matches.removeAllViews();
    if (photoFooter != null) {
      shell.removeView(photoFooter);
      photoFooter = null;
    }
    for (int i = 0; i < searchTitleCount; i++)
      body.getChildAt(i).setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
    photoTools.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
    if (q.isEmpty()) {
      if (scanning) { subhead(matches,"正在识别…"); return; }
      if (!photoText.text.isEmpty()) {
        renderPhotoResult();
      } else {
        if (!photoNotice.isEmpty()) {
          TextView notice = txt(photoNotice,15,muted,false);
          pad(notice,0,8,0,4);
          add(matches,notice,-2);
        }
        notebook.recent(matches);
        subhead(matches, "词库里的词");
        Lexicon.Book b = lex.book(data.plan);
        for (int i = 0; i < Math.min(3, b.words.size()); i++) {
          Lexicon.Word v = data.word(b.words.get(i));
          row(matches, v.ru, v.zh, R.drawable.s_book, () -> open(v, "search"));
        }
      }
      return;
    }
    add(matches, txt("正在查词…", 14, muted, false), 48);
    ui.postDelayed(
        () -> {
          if (seq != searchGeneration || !screen.equals("search")) return;
          io.execute(
              () -> {
                try {
                  List<Lexicon.Word> found = lex.search(q, false);
                  ui.post(
                      () -> {
                        if (seq != searchGeneration || !screen.equals("search") || isDestroyed())
                          return;
                        StudyStore.Card personal = data.cards.get(Lexicon.key(q));
                        if (personal != null
                            && found.stream().noneMatch(x -> x.k.equals(personal.k)))
                          found.add(0, personal.word());
                        String normalized = Lexicon.key(q);
                        HashSet<String> keys = new HashSet<>();
                        for (Lexicon.Word result : found) keys.add(result.k);
                        for (StudyStore.Card saved : data.cards.values()) {
                          if (found.size() >= 36) break;
                          if ((saved.k.startsWith(normalized) || saved.zh.contains(q))
                              && keys.add(saved.k)) found.add(saved.word());
                        }
                        matches.removeAllViews();
                        subhead(matches, found.isEmpty() ? "没有查到释义" : "搜索结果");
                        if (found.isEmpty()) {
                          List<Lexicon.Word> nearby = lex.closeSpellings(q);
                          if (!nearby.isEmpty()) {
                            subhead(matches,"你是不是想查");
                            for (Lexicon.Word near : nearby)
                              row(matches,near.ru,brief(near.zh),R.drawable.s_search,()->open(near,"search"));
                          }
                        }
                        if (found.isEmpty() && q.matches("[А-Яа-яЁё -]{1,48}")) {
                          row(matches, q, "听发音 · 补释义", R.drawable.s_plus,
                              () -> saveWords(Collections.emptyList(), Collections.singletonList(q)));
                        }
                        if (found.isEmpty() && !q.matches("[А-Яа-яЁё -]{1,48}"))
                          add(matches, txt("试试更短的关键词。", 14, muted, false), 48);
                        resultPage(found, 0);
                      });
                } catch (Exception e) {
                  ui.post(
                      () -> {
                        if (seq == searchGeneration) fail(e);
                      });
                }
              });
        },
        140);
  }

  private void photoTranscript() {
    subhead(matches, "识别原文");
    LinkedHashMap<String,List<TextView>> translations = new LinkedHashMap<>();
    TextView status = txt("", 13, blue, true);
    status.setVisibility(View.GONE);
    status.setMinHeight(dp(48));
    status.setGravity(Gravity.CENTER_VERTICAL);
    touchAction(status, this::photoTranslationOptions);
    add(matches,status,-2);
    for (String sentence : photoText.sentences) {
      TextView line = txt(sentence, 17, ink, false);
      selectableWords(line,"search");
      add(matches,line,-2);
      String russian = PhotoText.russianSource(sentence);
      if (!russian.isEmpty()) {
        TextView translation = txt("译文准备中…",15,muted,false);
        pad(translation, 0, 3, 0, 0);
        translation.setTextIsSelectable(true);
        add(matches,translation,-2);
        List<TextView> occurrences = translations.get(russian);
        if (occurrences == null) {
          occurrences = new ArrayList<>();
          translations.put(russian, occurrences);
        }
        occurrences.add(translation);
      }
      gap(matches,9);
    }
    photoTranslator.observe(translations.keySet(), (source,value)->{
      List<TextView> views = translations.get(source);
      if (views != null) for (TextView view : views) view.setText(value);
    }, message->{
      status.setText(message);
      status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
      status.setClickable(PhotoTranslator.OPTIONS.equals(message));
    });
    LinearLayout controls = box(false);
    wide(controls, flatPhotoAction("复制原文", () -> {
      android.content.ClipboardManager clipboard =
          (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText("拍照识别原文",photoText.text));
      if (Build.VERSION.SDK_INT < 33) tip("原文已复制");
    }));
    wide(controls,flatPhotoAction("校对原文",this::editPhotoText));
    wide(controls,flatPhotoAction("清除结果",()->{
      checked.clear();
      setPhotoText(new PhotoText(""));
      photoTranslator.observe(Collections.emptyList(),(source,value)->{},value->{});
      show("search");
    }));
    add(matches,controls,-2);
  }

  private void renderPhotoResult() {
    photoTranscript();
    if (!scanned.isEmpty()) photoRows();
    if (photoText.englishPhrases.isEmpty() && photoText.englishWords.isEmpty()) return;
    gap(matches,10);
    rule(matches);
    if (!photoText.englishPhrases.isEmpty()) {
      subhead(matches,"英文短语 · "+photoText.englishPhrases.size());
      TextView phrases = txt(String.join("\n",photoText.englishPhrases),16,ink,false);
      selectableWords(phrases,"search");
      add(matches,phrases,-2);
    }
    if (!photoText.englishWords.isEmpty()) {
      subhead(matches,"英文单词 · "+photoText.englishWords.size());
      TextView words = txt(String.join("  ·  ",photoText.englishWords),16,ink,false);
      selectableWords(words,"search");
      add(matches,words,-2);
    }
  }

  void selectableWords(TextView line,String from) {
    line.setTextIsSelectable(true);
    line.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
      @Override public boolean onCreateActionMode(ActionMode mode,Menu menu) {
        menu.add(0,1,0,"听读音").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0,2,1,"查词").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
      }
      @Override public boolean onPrepareActionMode(ActionMode mode,Menu menu) { return false; }
      @Override public void onDestroyActionMode(ActionMode mode) {}
      @Override public boolean onActionItemClicked(ActionMode mode,MenuItem item) {
        if(item.getItemId()!=1 && item.getItemId()!=2)return false;
        int start=Math.min(line.getSelectionStart(),line.getSelectionEnd());
        int end=Math.max(line.getSelectionStart(),line.getSelectionEnd());
        if(start<0 || end<=start || end>line.length())return false;
        String selected=line.getText().subSequence(start,Math.min(end,start+220)).toString().trim();
        mode.finish();
        if(item.getItemId()==1) speakSelection(selected);
        else lookupSelection(selected,from);
        return true;
      }
    });
  }

  private void speakSelection(String text) {
    String russian=PhotoText.russianSource(text);
    if(!russian.isEmpty() || text.matches("[А-Яа-яЁё]")) {
      voice.playExtracted(russian.isEmpty()?text:russian);
    } else if(text.matches("(?s).*\\p{IsHan}.*")) {
      voice.playInLanguage(text,Locale.SIMPLIFIED_CHINESE);
    } else {
      voice.playInLanguage(text,Locale.US);
    }
  }

  private void lookupSelection(String text,String from) {
    if(text.isEmpty())return;
    String term=text.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$","");
    if(term.isEmpty()) {
      tip("选中文字后再查");
      return;
    }
    io.execute(()->{
      try {
        List<Lexicon.Word> found=lex.search(term,true);
        if(found.isEmpty() && term.matches("(?s).*\\p{IsHan}.*"))found=lex.search(term,false);
        List<Lexicon.Word> results=found;
        ui.post(()->{
          if(isDestroyed() || !screen.equals(from))return;
          if(results.size()==1)open(results.get(0),from);
          else if(results.isEmpty()) {
            new FeedbackDialog(this).setTitle(term).setMessage("本机俄汉词典没有找到这个词条。")
                .setNegativeButton("返回",null)
                .setPositiveButton("听读音",(dialog,which)->speakSelection(term)).show();
          } else {
            String[] labels=results.stream().map(w->w.ru+"   "+brief(w.zh)).toArray(String[]::new);
            new FeedbackDialog(this).setTitle(term).setItems(labels,(dialog,position)->open(results.get(position),from))
                .setNegativeButton("返回",null).show();
          }
        });
      } catch(IOException error) {
        ui.post(()->{ if(!isDestroyed() && screen.equals(from))fail(error); });
      }
    });
  }

  private void photoTranslationOptions() {
    if (!photoTranslator.canTranslate()) return;
    boolean phone=LocalLanguageModel.enabled(getPreferences(0));
    new FeedbackDialog(this)
        .setTitle("翻译俄语句子")
        .setMessage("每次处理最多三句。手机翻译在本机完成。联网翻译会把俄语句子发给 MyMemory，图片仍留在手机。")
        .setNegativeButton("返回",null)
        .setNeutralButton(phone?"手机翻译":"开启手机 AI",(dialog,which)->{
          if(phone)photoTranslator.translateOnPhone();
          else openAiSettings("search");
        })
        .setPositiveButton("联网翻译",(dialog,which)->photoTranslator.translateOnline()).show();
  }

  private TextView flatPhotoAction(String label, Runnable action) {
    TextView view = txt(label,14,blue,true);
    view.setMinHeight(dp(48));
    view.setGravity(Gravity.CENTER);
    touchAction(view, action);
    return view;
  }

  private void setPhotoText(PhotoText value) {
    photoText = value;
    scanned = value.russianWords;
    photoWordLimit = 32;
  }

  private void editPhotoText() {
    EditText editor = new EditText(this);
    editor.setContentDescription("识别原文编辑框");
    editor.setGravity(Gravity.TOP);
    editor.setTextSize(16);
    editor.setText(photoText.text);
    editor.setMinLines(4);
    editor.setMaxLines(7);
    AlertDialog dialog = new FeedbackDialog(this).setTitle("校对原文").setView(editor)
        .setNegativeButton("取消", null).setPositiveButton("更新", (d,which)->{
          setPhotoText(new PhotoText(editor.getText().toString()));
          checked.retainAll(scanned);
          lookup();
        }).create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
  }

  void photoRows() {
    LinearLayout heading=box(false);
    wide(heading,txt("俄语取词 · "+scanned.size(),16,ink,true));
    boolean every = checked.size() == scanned.size();
    TextView selection = flatPhotoAction(every ? "取消全选" : "全选俄语词",()->{
      if (checked.size()==scanned.size()) checked.clear(); else checked.addAll(scanned);
      int y=scroll.getScrollY();
      lookup();
      scroll.post(()->scroll.scrollTo(0,y));
    });
    pad(selection,8,0,8,0);
    heading.addView(selection,-2,dp(48));
    gap(matches,5);
    rule(matches);
    add(matches,heading,-2);
    LinearLayout tools = box(false);
    TextView save = txt("", 15, white, true);
    save.setGravity(Gravity.CENTER);
    save.setMinimumHeight(dp(50));
    pad(save, 8, 6, 8, 6);
    glass(save, true, this::resolveSelected);
    wide(tools, save);
    gap(tools, 7);
    wide(
        tools,
        action(
            "取消选择",
            0,
            false,
            () -> {
              int y=scroll.getScrollY();
              checked.clear();
              lookup();
              scroll.post(()->scroll.scrollTo(0,y));
            }));
    photoFooter = tools;
    dock(tools);
    Runnable refresh =
        () -> {
          selection.setText(checked.size()==scanned.size()?"取消全选":"全选俄语词");
          boolean enabled = !checked.isEmpty();
          save.setText(enabled ? "加入词库（" + checked.size() + "）" : "加入词库");
          tools.setVisibility(enabled ? View.VISIBLE : View.GONE);
        };
    refresh.run();
    for (int i=0; i<Math.min(photoWordLimit,scanned.size()); i++) {
      String token = scanned.get(i);
      LinearLayout r = box(false);
      r.setGravity(Gravity.CENTER_VERTICAL);
      CheckBox cb = new CheckBox(this);
      cb.setContentDescription("选择 " + token);
      cb.setChecked(checked.contains(token));
      r.addView(cb, dp(48), dp(48));
      cb.setOnCheckedChangeListener(
          (v, on) -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (on) checked.add(token);
            else checked.remove(token);
            refresh.run();
            if(on)r.post(()->r.requestRectangleOnScreen(new Rect(0,0,r.getWidth(),r.getHeight()),false));
          });
      TextView word = serif(token, 18, ink);
      word.setMinHeight(dp(48));
      word.setGravity(Gravity.CENTER);
      word.setMaxLines(2);
      word.setEllipsize(android.text.TextUtils.TruncateAt.END);
      touchAction(word, () -> lookupSelection(token,"search"));
      wide(r, word);
      ImageView read = icon(R.drawable.s_audio, blue);
      pad(read,13,13,13,13);
      read.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      read.setContentDescription("朗读 " + token);
      touchAction(read, () -> voice.playExtracted(token));
      r.addView(read, dp(48), dp(48));
      add(matches, r, -2);
      rule(matches);
    }
    if (photoWordLimit<scanned.size())
      add(matches,flatPhotoAction("展开更多俄语词",()->{
        photoWordLimit+=32;
        lookup();
      }),-2);
  }

  void resolveSelected() {
    List<String> selected = new ArrayList<>(checked);
    if (selected.isEmpty()) return;
    int view = searchGeneration;
    if (resolvingGeneration == view) return;
    resolvingGeneration = view;
    io.execute(
        () -> {
          try {
            LinkedHashMap<String, Lexicon.Word> found = new LinkedHashMap<>();
            ArrayList<String> unknown = new ArrayList<>();
            for (String token : selected) {
              List<Lexicon.Word> options = lex.search(token, true);
              if (options.isEmpty()) unknown.add(token);
              else {
                Lexicon.Word w = options.get(0);
                found.put(w.k, w);
              }
            }
            ui.post(
                () -> {
                  if (!isDestroyed() && screen.equals("search") && view == searchGeneration)
                    saveWords(found.values(), unknown);
                  if (resolvingGeneration == view) resolvingGeneration = -1;
                });
          } catch (Exception e) {
            ui.post(() -> {
              if (resolvingGeneration == view) resolvingGeneration = -1;
              if (!isDestroyed() && screen.equals("search") && view == searchGeneration) fail(e);
            });
          }
        });
  }

  void saveWords(Collection<Lexicon.Word> found, List<String> unknown) {
    LinkedHashMap<String, Lexicon.Word> picked = new LinkedHashMap<>();
    for (Lexicon.Word w : found) picked.put(w.k, w);
    LinearLayout pane = box(true);
    pad(pane, 20, 6, 20, 10);
    add(pane, txt("存到", 14, muted, false), -2);
    List<Lexicon.Book> books = new ArrayList<>(lex.books);
    Collections.rotate(books, 1);
    ArrayList<String> labels = new ArrayList<>();
    for (Lexicon.Book b : books) labels.add(b.title);
    Spinner dest = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
    dest.setAdapter(adapter);
    dest.setContentDescription("目标词库");
    add(pane, dest, 52);
    LinearLayout forms = box(true);
    boolean singleUnknown = picked.isEmpty() && unknown.size() == 1;
    Map<String, EditText> fields = new LinkedHashMap<>();
    if (!picked.isEmpty()) {
      StringBuilder list = new StringBuilder();
      for (Lexicon.Word w : picked.values()) {
        if (list.length() > 0) list.append(" · ");
        list.append(w.ru);
      }
      add(forms, txt(list.toString(), 14, muted, false), -2);
    }
    for (String word : unknown) {
      gap(forms, 7);
      add(forms, serif(word, 18, ink), -2);
      if (singleUnknown)
        add(forms, action("听发音", R.drawable.s_audio, false, () -> voice.play(word)), -2);
      EditText e = new EditText(this);
      e.setSingleLine();
      e.setHint(singleUnknown ? "填写中文释义" : "补个释义，留空跳过");
      e.setContentDescription("中文释义：" + word);
      e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(180)});
      fields.put(word, e);
      add(forms, e, 50);
    }
    ScrollView contents = new ScrollView(this);
    contents.addView(forms);
    add(pane, contents, unknown.size() > 2 || picked.size() > 12 ? 159 : -2);
    AlertDialog dialog =
        new FeedbackDialog(this)
            .setTitle("加入词库")
            .setView(pane)
            .setNegativeButton("取消", null)
            .setPositiveButton("加入", null)
            .create();
    if (!fields.isEmpty()) dialog.setCanceledOnTouchOutside(false);
    dialog.setOnShowListener(
        x ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      LinkedHashMap<String, Lexicon.Word> all = new LinkedHashMap<>(picked);
                      for (Map.Entry<String, EditText> pair : fields.entrySet()) {
                        String z = pair.getValue().getText().toString().trim();
                        if (!z.isEmpty()) {
                          String k = Lexicon.key(pair.getKey());
                          all.put(k, new Lexicon.Word(k, pair.getKey(), z, "自录词"));
                        }
                      }
                      if (all.isEmpty()) {
                        if (!fields.isEmpty()) fields.values().iterator().next().setError("先填一个释义");
                        return;
                      }
                      Lexicon.Book b = books.get(dest.getSelectedItemPosition());
                      try {
                        int added = data.addToBook(b.k, all.values());
                        dialog.dismiss();
                        checked.clear();
                        show(screen);
                        tip(added == 0 ? "这些词已在词库里" : "已加入「" + b.title + "」");
                      } catch (Exception e) {
                        fail(e);
                      }
                    }));
    dialog.show();
  }

  void resultPage(List<Lexicon.Word> found, int start) {
    int end = Math.min(start + 9, found.size());
    for (int i = start; i < end; i++) {
      Lexicon.Word v = found.get(i);
      row(matches, v.ru, brief(v.zh), R.drawable.s_arrow,
          () -> open(v, "search"));
    }
    if (end < found.size()) {
      final View[] more = new View[1];
      more[0] =
          action(
              "更多词条",
              0,
              false,
              () -> {
                matches.removeView(more[0]);
                resultPage(found, end);
              });
      add(matches, more[0], -2);
    }
  }

  String brief(String v) {
    return v.length() > 54 ? v.substring(0, 54) + "…" : v;
  }

  void subhead(LinearLayout p, String name) {
    gap(p, 17);
    add(p, txt(name, 17, ink, true), -2);
    gap(p, 8);
  }

  void open(Lexicon.Word w, String from) {
    detailScroll = scroll == null ? 0 : scroll.getScrollY();
    if ("search".equals(from)) notebook.remember(w);
    shown = w;
    detailFrom = from;
    show("detail");
  }

  void photoStrip(LinearLayout host, int location, int height) {
    TextView label = txt(FieldArt.PLACES[location], 13, white, true);
    label.setGravity(Gravity.BOTTOM | Gravity.LEFT);
    label.setShadowLayer(dp(3), 0, 1, ink);
    landscape(label, location, FieldArt.PHOTO, 12);
    pad(label, 13, 4, 13, 8);
    gap(host, 9);
    add(host, label, height);
  }

  void photoStrip(int location, int height) {
    photoStrip(body, location, height);
  }

  void openQuotes(String from) {
    quoteFrom = from;
    show("quotes");
  }

  void openLab(String from) {
    labFrom = from;
    lab = new StudyLab(this);
    show("lab");
  }

  int quoteIndex() {
    return Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        + getPreferences(0).getInt("postcardVariation", 0);
  }

  void quoteTeaser(LinearLayout host) {
    boolean personal = getPreferences(0).getBoolean("postcardPersonal", false);
    String[] q = Postcards.entry(personal, quoteIndex(), data);
    LinearLayout note = box(true);
    landscape(note, getPreferences(0).getInt("quotePlace", 4), FieldArt.LIGHT, 14);
    touchAction(note, () -> openQuotes("atlas"));
    pad(note, 14, 13, 14, 10);
    LinearLayout top = box(false);
    wide(top, txt("俄语明信片", 14, blue, true));
    sym(top, R.drawable.s_arrow, blue, 16);
    add(note, top, -2);
    gap(note, 6);
    TextView excerpt = serif(getPreferences(0).getString("quoteText",q[0]), 19, ink);
    excerpt.setMaxLines(2);
    excerpt.setEllipsize(TextUtils.TruncateAt.END);
    add(note, excerpt, -2);
    add(host, note, -2);
  }

  void postcards() {
    boolean personal = getPreferences(0).getBoolean("postcardPersonal", false);
    int index = quoteIndex();
    int location =
        personal
            ? Math.floorMod(index, FieldArt.LAND.length)
            : Postcards.VERSE_PLACE[Math.floorMod(index, Postcards.VERSE.length)];
    place = location;
    gap(body, 12);
    LinearLayout modes = box(false);
    for (String title : new String[] {"读一句诗", "写给自己"}) {
      boolean selectPersonal = title.equals("写给自己");
      View choice =
          action(
              title,
              0,
              selectPersonal == personal,
              () -> {
                getPreferences(0).edit().putBoolean("postcardPersonal", selectPersonal).apply();
                show("quotes");
              });
      choice.setSelected(selectPersonal == personal);
      wide(modes, choice);
      if (!selectPersonal) gap(modes, 8);
    }
    add(body, modes, -2);
    LinearLayout panel = box(true);
    add(body, panel, -2);
    fillPostcard(panel, personal, index, location);
    LinearLayout buttons = box(false);
    wide(
        buttons,
        action(
            "换一句",
            0,
            true,
            () -> {
              getPreferences(0)
                  .edit()
                  .remove("quoteText").putInt("postcardVariation", getPreferences(0).getInt("postcardVariation", 0) + 1)
                  .apply();
              int next = quoteIndex();
              int land = personal ? Math.floorMod(next, FieldArt.LAND.length)
                  : Postcards.VERSE_PLACE[Math.floorMod(next, Postcards.VERSE.length)];
              fillPostcard(panel, personal, next, land);
              arrive(panel, 160);
            }));
    add(body,action("编辑文字",0,false,()->{
      EditText field=new EditText(this);
      field.setMaxLines(4);
      field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(240)});
      field.setText(getPreferences(0).getString("quoteText",Postcards.entry(personal,quoteIndex(),data)[0]));
      new FeedbackDialog(this).setTitle("我的明信片").setView(field).setNegativeButton("取消",null)
        .setPositiveButton("保存",(d,v)->{
          String text=field.getText().toString().trim();
          if(text.isEmpty())getPreferences(0).edit().remove("quoteText").apply();
          else getPreferences(0).edit().putString("quoteText",text).apply();
          show("quotes");
        }).show();
    }),-2);
    add(body,action("更换风景",0,false,()->new FeedbackDialog(this).setTitle("明信片背景")
        .setItems(FieldArt.PLACES,(d,i)->{getPreferences(0).edit().putInt("quotePlace",i).apply(); show("quotes");}).show()),-2);
    dock(buttons);
  }

  private void fillPostcard(LinearLayout panel, boolean personal, int index, int location) {
    panel.removeAllViews();
    location=getPreferences(0).getInt("quotePlace",location);
    photoStrip(panel, location, 153);
    String[] entry = Postcards.entry(personal, index, data);
    if(getPreferences(0).contains("quoteText")) entry=new String[]{getPreferences(0).getString("quoteText",""),"","我的明信片"};
    gap(panel, 16);
    add(panel, serif(entry[0], 25, ink), -2);
    gap(panel, 9);
    add(panel, txt(entry[1], 17, ink, false), -2);
    gap(panel, 8);
    add(panel, txt(entry[2], 13, muted, false), -2);
    notebook.postcardActions(panel, entry, location);
  }

  void portrait() {
    android.content.SharedPreferences prefs = getPreferences(0);
    int identity = prefs.getInt("portraitIdentity", 0);
    if (identity == 0) {
      identity = new Random().nextInt(2000000000) + 1;
      prefs.edit().putInt("portraitIdentity", identity).apply();
    }
    String[] keys = {"explore", "immerse", "pace"}, names = {"探索", "沉浸", "节奏"};
    int[] initial = new int[3];
    for (int i = 0; i < 3; i++) initial[i] = prefs.getInt("portrait_" + keys[i], 50);
    ParticlePortrait portrait =
        new ParticlePortrait(this, data, initial, identity,
            getPreferences(0).getBoolean(ParticlePortrait.HIGH_QUALITY,false),c -> open(c.word(), "portrait"));
    LinearLayout landscape = box(true,17);
    riverPhoto = landscape;
    landscape(landscape, 4, FieldArt.NIGHT, 17);
    add(landscape, portrait, 291);
    TextView label = txt("小伊斯特拉河　/　Малая Истра", 12, 0xffdaeae2, false);
    pad(label, 15, 2, 8, 12);
    add(landscape, label, -2);
    gap(body, 10);
    add(body, landscape, -2);
    gap(body, 9);
    add(body, txt("滑动旋转，点亮点看单词。", 14, muted, false), -2);
    section("我的偏好", "");
    add(body, txt("学过的词、练过的对话类型、对话轮数和活跃天数，都会改变粒子的分布和流速。", 14, muted, false), -2);
    for (int i = 0; i < 3; i++) {
      final int axis = i;
      LinearLayout r = box(false);
      TextView axisLabel = txt(names[i], 14, ink, true);
      r.addView(axisLabel, dp(47), dp(48));
      SeekBar knob = new SeekBar(this);
      knob.setContentDescription(names[i]);
      knob.setMax(100);
      knob.setProgress(initial[i]);
      knob.setMinimumHeight(dp(48));
      wide(r, knob);
      add(body, r, -2);
      knob.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean user) {
              if (user) {
                portrait.setPreference(axis, v);
                prefs.edit().putInt("portrait_" + keys[axis], v).apply();
              }
            }

            public void onStartTrackingTouch(SeekBar b) {}

            public void onStopTrackingTouch(SeekBar b) {
              b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
          });
    }
  }

  void credits() {
    try (java.io.BufferedReader reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                getAssets().open("photography.json"), java.nio.charset.StandardCharsets.UTF_8))) {
      JSONObject meta = new JSONObject(reader.readLine());
      JSONArray works = meta.getJSONArray("scenes"), books = meta.getJSONArray("books");
      section("风景摄影", "裁切、缩放与模糊处理");
      for (int i = 0; i < works.length(); i++) {
        JSONObject item = works.getJSONObject(i);
        credit(
            item.getString("title"),
            item.getString("author") + " · " + item.getString("license"),
            item.getString("url"));
      }
      section("参考书封面", "词库由词汐自编");
      for (int i = 0; i < books.length(); i++) {
        JSONObject item = books.getJSONObject(i);
        credit(item.getString("title"), item.optString("author", "书目与封面来源"), item.getString("url"));
      }
      section("诗句", "");
      credit("普希金 · 诗选", "普希金博物馆藏诗集", "https://www.pushkinland.ru/2018/pushkin/poems.php");
      credit(
          "丘特切夫 · 诗选", "俄罗斯文化门户", "https://www.culture.ru/literature/poems/author-fedor-tyutchev");
    } catch (Exception e) {
      fail(e);
    }
  }

  void credit(String title, String author, String url) {
    row(
        body,
        title,
        author,
        R.drawable.s_book,
        () -> {
          try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
          } catch (ActivityNotFoundException e) {
            tip("此设备没有可用的浏览器");
          }
        });
  }

  void memoryChart() {
    memory = new MemoryPage(this);
    show("memory");
  }

  private void openAssistant(String from) {
    assistantFrom=from;
    show("assistant");
  }

  void atlas() {
    gap(body, 9);
    assistant.addReminder(()->openAssistant("atlas"));
    gap(body, 13);
    LinearLayout entry = box(true);
    riverPhoto = entry;
    landscape(entry, 4, FieldArt.PHOTO, 16);
    pad(entry, 19, 17, 19, 14);
    gap(entry, 31);
    TextView name = txt("我的粒子像", 24, white, true);
    name.setShadowLayer(dp(3), 0, 1, ink);
    add(entry, name, -2);
    gap(entry, 5);
    add(entry, txt("小伊斯特拉河　·　轻点进入  ›", 14, white, false), -2);
    entry.setContentDescription("打开我的粒子像");
    touchAction(entry, () -> show("portrait"));
    add(body, entry, -2);
    gap(body, 12);
    LinearLayout journal=secondary();
    quoteTeaser(journal);
    Map<String,Double> interests=data.interests();
    if(!interests.isEmpty()){
      gap(journal,14);
      add(journal,txt("最近聊到的词",18,blue,true),-2);
      List<String> ranked=new ArrayList<>(interests.keySet());
      ranked.sort(Comparator.<String>comparingDouble(interests::get).reversed().thenComparing(k->k));
      for(String key:ranked.subList(0,Math.min(5,ranked.size()))){
        row(journal,key,"查看词条",R.drawable.s_book,()->lookupSelection(key,"atlas"));
      }
    }
    row(body, "俄语对话", "用离线模型练真实场景", R.drawable.s_orb, () -> show("role_chat"));
    ArrayList<StudyStore.Card> list = new ArrayList<>(data.cards.values());
    list.sort((a, b) -> Long.compare(b.last, a.last));
    row(body, "记忆曲线", "预测、复习计划与练习记录", R.drawable.s_orb, this::memoryChart);
    row(body, "专项练习", "听音、拼写与词形", R.drawable.s_book, () -> openLab("atlas"));
    section("最近练过", list.size() + " 个词");
    if (list.isEmpty()) {
      add(body, txt("练过的词会收在这里。", 15, muted, false), -2);
      gap(body, 12);
      add(
          body,
          action(
              "去斩词",
              R.drawable.s_arrow,
              true,
              () -> {
                if (data.plan.isEmpty()) show("shelf");
                else start();
              }),
          -2);
    }
    for (int i = 0; i < Math.min(limit, list.size()); i++) {
      StudyStore.Card c = list.get(i);
      LinearLayout r = box(false);
      click(r, paper, 5, 0, () -> open(c.word(), "atlas"));
      pad(r, 9, 7, 12, 7);
      LinearLayout words = box(true);
      add(words, serif(c.ru, 18, ink), -2);
      gap(words, 3);
      add(words, txt(c.note.isEmpty() ? brief(c.zh) : c.note, 13, muted, false), -2);
      wide(r, words);
      sym(r, R.drawable.s_arrow, muted, 16);
      add(body, r, -2);
      gap(body, 6);
    }
    if (list.size() > limit)
      add(
          body,
          action(
              "查看更多",
              0,
              false,
              () -> {
                limit += 24;
                show("atlas");
              }),
          -2);
  }

  void detail() {
    if (shown == null) {
      show("search");
      return;
    }
    Lexicon.Word w = shown;
    StudyStore.Card c = data.cards.get(w.k);
    gap(body, 13);
    add(body, txt(w.pos.isEmpty() ? "俄语词条" : w.pos, 13, blue, true), -2);
    gap(body, 7);
    JSONObject usage = lex.usage.optJSONObject(w.k);
    add(
        body,
        serif(
            usage == null ? w.ru : usage.optString("stress", w.ru),
            w.ru.length() > 14 ? 30 : 37,
            ink),
        -2);
    phonetics(body,w.k,muted);
    gap(body, 14);
    TextView definition = txt(w.zh, 19, ink, false);
    definition.setTextIsSelectable(true);
    landscape(definition, place, FieldArt.LIGHT, 12);
    pad(definition, 14, 13, 14, 13);
    add(body, definition, -2);
    add(body,txt("谐音助记 · "+SoundHint.forWord(w.ru)+"（近似音，以录音为准）",14,muted,false),-2);
    gap(body, 18);
    LinearLayout listening = box(false);
    wide(listening, action("听发音", R.drawable.s_audio, true, () -> voice.play(w.ru)));
    gap(listening,8);
    wide(listening, action("慢速读音", 0, false, () -> voice.playSlow(w.ru)));
    add(body,listening,-2);
    gap(body, 8);
    if (c == null) {
      add(
          body,
          action(
              "加入词库",
              R.drawable.s_plus,
              true,
              () -> {
                saveWords(Collections.singletonList(w), Collections.emptyList());
              }),
          -2);
    } else {
      section("我的联想", "");
      Bitmap bitmap = c.image.isEmpty() ? null :
          BitmapFactory.decodeFile(new File(getFilesDir(), c.image).getPath());
      if (bitmap != null) {
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setContentDescription("这个词的个人联想图片");
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        add(body, image, 157);
        gap(body, 8);
      }
      if (!c.note.isEmpty()) {
        add(body, txt(c.note, 17, ink, false), -2);
        gap(body, 9);
      }
      LinearLayout r = box(false);
      wide(r, action("写联想", 0, false, () -> memo(c)));
      gap(r, 8);
      wide(
          r,
          action(
              "配图片",
              R.drawable.s_image,
              false,
              () -> {
                attachKey = c.k;
                pick(MEMO);
              }));
      add(body, r, -2);
      section("复习记录", "");
      String when =
          c.seen == 0
              ? "已加入下次新词计划"
              : new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(c.due));
      add(body, txt("已练 " + c.seen + " 次  ·  斩下 " + c.hits + " 次", 15, ink, false), -2);
      gap(body, 5);
      add(body, txt(c.seen == 0 ? when : "下次复习  " + when, 14, muted, false), -2);
      if(c.seen>0) {
        gap(body,6);
        add(body,txt("记住 "+c.hits+" 次　模糊 "+Math.max(0,c.seen-c.hits-c.miss)+" 次　忘记 "+c.miss+" 次",14,muted,false),-2);
      }
    }
    if (c != null) {
      gap(body, 12);
      add(
          body,
          action(
              "存到其他词库",
              R.drawable.s_shelf,
              false,
              () -> saveWords(Collections.singletonList(w), Collections.emptyList())),
          -2);
    }
    details.render(w);
    if (!lex.core.containsKey(w.k) && !w.pos.equals("自录词")) {
      gap(body, 22);
      add(body, txt("释义来源：中文维基词典贡献者，经 Kaikki 整理。CC BY-SA 4.0。", 12, muted, false), -2);
    }
  }

  void memo(StudyStore.Card c) {
    EditText ed = new EditText(this);
    ed.setText(c.note);
    ed.setMaxLines(4);
    ed.setFilters(new InputFilter[] {new InputFilter.LengthFilter(240)});
    ed.setHint("这个词让你想到什么？");
    ed.setContentDescription("个人联想");
    AlertDialog dialog = new FeedbackDialog(this)
        .setTitle("我的联想")
        .setView(ed)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              try {
                StudyStore.Card updated = data.cards.get(c.k).copy();
                updated.note = ed.getText().toString().trim();
                data.save(updated);
                show("detail");
              } catch (android.database.SQLException e) {
                fail(e);
              }
            })
        .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
  }

  void photoMenu() {
    new FeedbackDialog(this)
        .setTitle("识别照片文字")
        .setItems(
            new String[] {"拍照识别", "从相册选图"},
            (d, w) -> {
              if (w == 0) takePhoto();
              else pick(PHOTO);
            })
        .show();
  }

  void pick(int code) {
    if (code == PHOTO && scanning) return;
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("image/*"),
          code);
    } catch (ActivityNotFoundException e) {
      tip("当前设备没有可用的图片选择器");
    }
  }

  void takePhoto() {
    if (scanning) return;
    try {
      Uri u = CameraFile.uri(this);
      Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u);
      i.setClipData(ClipData.newRawUri("拍照识字", u));
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      startActivityForResult(i, CAMERA);
    } catch (ActivityNotFoundException | SecurityException e) {
      tip("当前无法打开相机，可以从相册选图");
    }
  }

  @Override
  protected void onActivityResult(int req, int res, Intent intent) {
    super.onActivityResult(req, res, intent);
    if (res != RESULT_OK) {
      if (req == CAMERA) {
        CameraFile.file(this).delete();
        revokeUriPermission(CameraFile.uri(this), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      }
      return;
    }
    Uri uri = req == CAMERA ? CameraFile.uri(this) : intent == null ? null : intent.getData();
    if (uri == null) return;
    if (data == null) {
      ui.postDelayed(
          () -> {
            if (!isDestroyed()) onActivityResult(req, res, intent);
          },
          150);
      return;
    }
    if (req == MEMO) {
      String key = attachKey;
      io.execute(
          () -> {
            String name = "memopics/" + UUID.randomUUID() + ".jpg";
            File image = new File(getFilesDir(), name);
            try {
              Bitmap b = PhotoWords.bitmap(this, uri, 720);
              File dir = new File(getFilesDir(), "memopics");
              dir.mkdirs();
              try (OutputStream out = new FileOutputStream(image)) {
                if (!b.compress(Bitmap.CompressFormat.JPEG, 83, out))
                  throw new IOException("图片保存失败");
              } finally {
                b.recycle();
              }
              ui.post(
                  () -> {
                    StudyStore.Card previous = isDestroyed() ? null : data.cards.get(key);
                    if (previous == null) {
                      image.delete();
                      return;
                    }
                    StudyStore.Card updated = previous.copy();
                    updated.image = name;
                    try {
                      data.save(updated);
                    } catch (android.database.SQLException e) {
                      image.delete();
                      fail(e);
                      return;
                    }
                    if (!previous.image.isEmpty()) new File(getFilesDir(), previous.image).delete();
                    if (screen.equals("detail") && shown != null && key.equals(shown.k)) show("detail");
                  });
            } catch (Exception e) {
              image.delete();
              ui.post(() -> fail(e));
            }
          });
      return;
    }
    if (req != PHOTO && req != CAMERA || scanning) return;
    scanning = true;
    photoNotice = "";
    query = "";
    setPhotoText(new PhotoText(""));
    checked.clear();
    show("search");
    io.execute(
        () -> {
          try {
            PhotoText result = PhotoWords.scan(this, uri);
            ui.post(
                () -> {
                  if (isDestroyed()) return;
                  scanning = false;
                  setPhotoText(result);
                  photoNotice = result.text.isEmpty() ? "没有读到文字。对焦后再拍一张。" : "";
                  if (screen.equals("search")) show("search");
                });
          } catch (Throwable e) {
            ui.post(
                () -> {
                  scanning = false;
                  if (!isDestroyed()) {
                    photoNotice = "没能读完这张照片。可以重拍或从相册选一张。";
                    android.util.Log.w("Slovo","Photo recognition failed",e);
                    if (screen.equals("search")) show("search");
                  }
                });
          } finally {
            if (req == CAMERA) {
              CameraFile.file(this).delete();
              revokeUriPermission(
                  uri,
                  Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
          }
        });
  }

  @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if(data==null)return;
    int position=scroll==null?0:scroll.getScrollY();
    if(screen.equals("role_chat"))roleChat.leave();
    if(searchBox!=null && screen.equals("search"))query=searchBox.getText().toString();
    onContentAction();
    show(screen);
    ScrollView destination=scroll;
    destination.post(()->destination.scrollTo(0,position));
  }

  @Override
  protected void onStop() {
    onContentAction();
    super.onStop();
    if (voice != null) voice.stop();
  }

  @Override
  protected void onDestroy() {
    updates.close();
    roleChat.close();
    ai.close();
    assistant.close();
    LocalLanguageModel.stop();
    photoTranslator.close();
    if (riverMotion != null) riverMotion.cancel();
    if (voice != null) voice.close();
    io.execute(
        () -> {
          details.close();
          if (lex != null) lex.close();
          if (data != null) data.close();
        });
    io.shutdown();
    super.onDestroy();
  }
}

