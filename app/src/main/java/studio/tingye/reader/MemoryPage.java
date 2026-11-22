package studio.tingye.reader;

import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.IntConsumer;

/** The learner's forecast, review plan and observed practice history. */
final class MemoryPage {
  private final WordActivity a;
  private final Deque<String> path = new ArrayDeque<>();
  private final Deque<Integer> offsets = new ArrayDeque<>();
  private final Deque<Integer> listPages = new ArrayDeque<>();
  private String section = "预测", screen = "", book = "", wordKey = "", calendarDay = "";
  private int horizon = 7, cursorDay = 1, riskBelow = 80, planDay = 0, page = 0, budget;
  private int activitySpan, strengthSpan, strengthBand;
  private boolean showNewAndReview;
  private boolean compare;
  private long now;
  private final SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);
  private final SimpleDateFormat shortDate = new SimpleDateFormat("M月d日",Locale.SIMPLIFIED_CHINESE);
  private final SimpleDateFormat hour = new SimpleDateFormat("HH:mm",Locale.ROOT);

  MemoryPage(WordActivity activity) {
    a = activity;
    budget = a.getPreferences(0).getInt("memoryReviewBudget", 10);
  }

  void reset() {
    path.clear(); offsets.clear(); listPages.clear(); screen=""; section="预测"; page=0;
  }

  private void go(String dest) {
    path.push(screen); offsets.push(a.scroll.getScrollY()); listPages.push(page);
    screen = dest; page=0; redraw(0);
  }

  boolean back() {
    if (path.isEmpty()) return false;
    screen = path.pop(); page=listPages.pop(); redraw(offsets.pop()); return true;
  }

  private void redraw(int scroll) {
    render();
    a.scroll.post(() -> { if ("memory".equals(a.screen)) a.scroll.scrollTo(0,scroll); });
  }

  void render() {
    now = System.currentTimeMillis();
    a.body.removeAllViews();
    if (screen.isEmpty()) {
      a.gap(a.body, 12);
      String[] sections = {"遗忘曲线", "学习情况", "记忆持久度"};
      if (section.equals("预测")) section=sections[0];
      options(sections, Arrays.asList(sections).indexOf(section), i -> {
        section = sections[i]; page=0; redraw(0);
      });
      if (section.equals(sections[0])) forecast();
      else if (section.equals(sections[1])) learning();
      else strength();
    } else switch (screen) {
      case "scope": selectBook(); break;
      case "plan": plan(); break;
      case "strength": strengthWords(); break;
      case "risk": risks(); break;
      case "due": dueList(); break;
      case "budget": budget(); break;
      case "calendar": calendar(); break;
      case "day": day(); break;
      case "grades": grades(); break;
      case "fit": calibration(); break;
      case "clock": clock(); break;
      case "pos": wordClasses(); break;
      case "word": word(); break;
      default: throw new IllegalStateException(screen);
    }
    a.gap(a.body, 10);
    a.finishTheme("memory");
  }

  private List<StudyStore.Card> cohort() {
    Set<String> keys = book.isEmpty() ? null : new HashSet<>(a.lex.book(book).words);
    List<StudyStore.Card> out = new ArrayList<>();
    for (StudyStore.Card c : a.data.cards.values())
      if (c.seen > 0 && (keys == null || keys.contains(c.k))) out.add(c);
    return out;
  }

  private String scope() { return book.isEmpty() ? "全部词库" : a.lex.book(book).title; }
  private String pct(double ratio) { return MemoryForecast.percent(ratio); }
  private String dayTitle(int n) { return n==0 ? "今天" : n==1 ? "明天" : n+"天后"; }
  private String date(long at) { return shortDate.format(new Date(at)); }
  private String time(long at) { return date(at)+" "+hour.format(new Date(at)); }

  private LinearLayout panel() {
    LinearLayout card = a.box(true);
    card.setBackground(a.surface(0xf4fafbf5,18,0x88596d61));
    a.pad(card,14,15,14,14); a.add(a.body,card,-2); return card;
  }

  private void text(LinearLayout host, String s, int size, int color, boolean strong) {
    a.add(host,a.txt(s,size,color,strong),-2);
  }
  private void note(LinearLayout host, String s) {
    TextView t=a.txt(s,14,PaperActivity.muted,false);
    t.setLineSpacing(a.dp(2),1f); a.add(host,t,-2);
  }
  private void heading(String name, String detail) { a.title(name,detail); }
  private void link(String name, String detail, Runnable run) {
    a.row(a.body,name,detail,R.drawable.s_arrow,run);
  }
  private void button(String name, boolean dark, Runnable run) {
    a.add(a.body,a.action(name,0,dark,run),-2);
  }
  private void options(String[] labels, int selected, IntConsumer pick) {
    LinearLayout row=a.box(false);
    for (int i=0;i<labels.length;i++) {
      if (i>0) a.gap(row,5);
      final int which=i;
      TextView option=a.txt(labels[i],14,i==selected?PaperActivity.white:PaperActivity.ink,i==selected);
      option.setGravity(Gravity.CENTER);
      option.setSingleLine(true);
      option.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
      a.glass(option,i==selected,()->pick.accept(which));
      a.pad(option,3,5,3,5); option.setSelected(i==selected);
      row.addView(option,new LinearLayout.LayoutParams(0,a.dp(48),1));
    }
    a.add(a.body,row,-2);
  }

  private void meter(LinearLayout container, String title, String caption, double amount, int color) {
    text(container,title,16,PaperActivity.ink,true);
    a.gap(container,3); note(container,caption); a.gap(container,6);
    LinearLayout track=a.box(false);
    track.setBackground(a.surface(0xffe2e7df,3,0));
    track.setWeightSum(1f);
    View bar=new View(a);
    bar.setBackground(a.surface(color,3,0));
    track.addView(bar,new LinearLayout.LayoutParams(0,-1,(float)Math.max(0,Math.min(1,amount))));
    a.add(container,track,4); a.gap(container,13);
  }

  private static final int[] STUDY_COLORS = {0xff358369,0xffbd873d,0xffb65e45,0xff909992};
  private static final int[] TREND_COLORS = {0xff778a91,0xff387d6d,0xff9a703c,0xff80607f};
  private static final String[] WINDOWS = {"按日", "按周", "按月"};
  private static final int[] DURATIONS = {1,7,30,90};

  private final class Periods {
    final long[] boundary = new long[9];
    final String[] labels = new String[8];

    Periods(int span) {
      Calendar calendar=Calendar.getInstance();
      calendar.setTimeInMillis(MemoryForecast.midnight(now,0));
      if (span==1) {
        int weekday=(calendar.get(Calendar.DAY_OF_WEEK)-Calendar.MONDAY+7)%7;
        calendar.add(Calendar.DATE,-weekday);
      } else if (span==2) calendar.set(Calendar.DAY_OF_MONTH,1);
      int unit=span==2?Calendar.MONTH:Calendar.DATE;
      int step=span==1?7:1;
      calendar.add(unit,-7*step);
      SimpleDateFormat format=new SimpleDateFormat(span==2?"M月":"M/d",Locale.ROOT);
      for (int i=0;i<boundary.length;i++) {
        boundary[i]=calendar.getTimeInMillis();
        if(i<8) labels[i]=i==7 && span==0?"今天":format.format(calendar.getTime());
        calendar.add(unit,step);
      }
    }

    int bucket(long time) {
      for (int i=0;i<labels.length;i++) if (time<boundary[i+1]) return i;
      return -1;
    }
  }

  private void learning() {
    Periods periods=new Periods(activitySpan);
    int[][] grades=new int[8][4], types=new int[8][4];
    String from=dayKey.format(new Date(periods.boundary[0]));
    try(Cursor cursor=a.data.getReadableDatabase().rawQuery(
        "SELECT day,SUM(n) FROM activity WHERE day>=? GROUP BY day",new String[]{from})) {
      while(cursor.moveToNext()) {
        try {
          int bin=periods.bucket(dayKey.parse(cursor.getString(0)).getTime());
          if(bin>=0) grades[bin][3]+=cursor.getInt(1);
        } catch(java.text.ParseException malformed) {
          android.util.Log.w("Slovo","Skipped malformed activity date");
        }
      }
    }
    try(Cursor cursor=a.data.getReadableDatabase().rawQuery(
        "SELECT at,grade,prior FROM review_log WHERE at>=? AND at<=?",
        new String[]{Long.toString(periods.boundary[0]),Long.toString(now)})) {
      while(cursor.moveToNext()) {
        int bin=periods.bucket(cursor.getLong(0)), grade=cursor.getInt(1);
        if(bin<0 || grade<0 || grade>2) continue;
        grades[bin][2-grade]++;
        types[bin][cursor.isNull(2)?0:1]++;
      }
    }
    for (int i=0;i<8;i++) {
      int unknown=Math.max(0,grades[i][3]-grades[i][0]-grades[i][1]-grades[i][2]);
      grades[i][3]=unknown;
      types[i][3]=unknown;
    }
    a.gap(a.body,8); options(WINDOWS,activitySpan,i->{activitySpan=i;redraw(0);});
    a.gap(a.body,6); options(new String[]{"按回忆结果","新词与复习"},showNewAndReview?1:0,i->{
      showNewAndReview=i==1; redraw(0);
    });
    a.section("实际练习","全部词库 · 作答次数");
    LinearLayout card=panel();
    TextView reading=a.txt("",16,PaperActivity.ink,true);
    TextView breakdown=a.txt("",14,PaperActivity.muted,false);
    a.add(card,reading,-2); a.gap(card,4); a.add(card,breakdown,-2);
    int[][] chart=showNewAndReview?types:grades;
    IntConsumer pick=i->{
      int sum=Arrays.stream(chart[i]).sum();
      reading.setText(periods.labels[i]+"  ·  "+sum+" 次");
      breakdown.setText(showNewAndReview ? "新词 "+types[i][0]+"　复习 "+types[i][1]
          : "记住 "+grades[i][0]+"　模糊 "+grades[i][1]+"　忘记 "+grades[i][2]);
      if (grades[i][3]>0) breakdown.append("　旧记录 "+grades[i][3]);
    };
    pick.accept(7);
    a.gap(card,7);
    a.add(card,new MemorySeriesChart(a,periods.labels,chart,STUDY_COLORS,true,7,pick),
        plotHeight(395,215,345));
    text(card,showNewAndReview?"绿  新词   ·   琥珀  复习   ·   灰  旧记录"
        :"绿  记住   ·   琥珀  模糊   ·   赭  忘记   ·   灰  旧记录",12,PaperActivity.muted,false);
    link("复习计划","未来七天待复习的词",()->go("plan"));
    records();
  }

  private int durationGroup(double days) {
    int group=0;
    while(group<DURATIONS.length && days>=DURATIONS[group]) group++;
    return group;
  }

  private String durationTitle(int group) {
    return group==0?"不到 1 天":group==4?"90 天及以上":
        DURATIONS[group-1]+" 至 "+(DURATIONS[group]-1)+" 天";
  }

  private void strength() {
    List<StudyStore.Card> seen=cohort();
    a.gap(a.body,8);options(WINDOWS,strengthSpan,i->{strengthSpan=i;redraw(0);});
    a.section("持久度怎样变化",scope()+" · "+seen.size()+" 词");
    Periods periods=new Periods(strengthSpan);
    Set<String> eligible=book.isEmpty()?null:new HashSet<>(a.lex.book(book).words);
    int[][] trend=new int[8][4];
    Map<String,Double> latest=new HashMap<>();
    try(Cursor cursor=a.data.getReadableDatabase().rawQuery(
        "SELECT k,half,MAX(at) FROM review_log WHERE at<? GROUP BY k",
        new String[]{Long.toString(periods.boundary[0])})) {
      while(cursor.moveToNext()) {
        String k=cursor.getString(0);
        if(eligible==null || eligible.contains(k)) latest.put(k,cursor.getDouble(1));
      }
    }
    try(Cursor cursor=a.data.getReadableDatabase().rawQuery(
        "SELECT k,at,half FROM review_log WHERE at>=? AND at<=? ORDER BY at,id",
        new String[]{Long.toString(periods.boundary[0]),Long.toString(now)})) {
      boolean move=cursor.moveToNext();
      for (int i=0;i<8;i++) {
        while(move && cursor.getLong(1)<periods.boundary[i+1]) {
          String k=cursor.getString(0);
          if(eligible==null || eligible.contains(k)) latest.put(k,cursor.getDouble(2));
          move=cursor.moveToNext();
        }
        trend[i][0]=latest.size();
        for (double half:latest.values()) {
          if(half>=7) trend[i][1]++;
          if(half>=30) trend[i][2]++;
          if(half>=90) trend[i][3]++;
        }
      }
    }
    LinearLayout history=panel();
    TextView selected=a.txt("",15,PaperActivity.ink,true);
    a.add(history,selected,-2);
    IntConsumer inspect=i->selected.setText(periods.labels[i]+" · 共 "+trend[i][0]
        +" 词\n≥7天 "+trend[i][1]+"　≥30天 "+trend[i][2]+"　≥90天 "+trend[i][3]);
    inspect.accept(7);
    a.add(history,new MemorySeriesChart(a,periods.labels,trend,TREND_COLORS,false,7,inspect),
        plotHeight(350,230,355));
    text(history,"灰  累计词数  ·  绿  ≥7天  ·  棕  ≥30天  ·  紫  ≥90天",12,PaperActivity.muted,false);
    if (trend[7][0]==0) note(history,"完成一次评分后，这里就会出现第一条记录。");
    a.section("当前半衰期","点开查看这一组的单词");
    LinearLayout card=panel();
    note(card,"半衰期指复习后，模型估计记住概率降至一半的时间。");
    int[] grouped=new int[5];
    for (StudyStore.Card word:seen) grouped[durationGroup(word.half)]++;
    for (int i=0;i<grouped.length;i++) {
      int band=i;
      a.row(card,durationTitle(i),grouped[i]+" 词 · "+pct(grouped[i]/(double)Math.max(1,seen.size())),
          R.drawable.s_arrow,()->{strengthBand=band; go("strength");});
    }
    link("筛选词库",scope(),()->go("scope"));
  }

  private void strengthWords() {
    heading(durationTitle(strengthBand),"当前复习模型的半衰期");
    List<StudyStore.Card> words=cohort();
    words.removeIf(card->durationGroup(card.half)!=strengthBand);
    words.sort(Comparator.comparingDouble((StudyStore.Card card)->card.half).reversed());
    if(words.isEmpty()) note(a.body,"这一组暂时没有词。");
    cards(words,card->String.format(Locale.ROOT,"半衰期 %.1f 天 · %s",card.half,card.zh));
  }

  private int plotHeight(int roomForControls,int min,int max) {
    int height=a.getResources().getConfiguration().screenHeightDp-roomForControls;
    return Math.max(min,Math.min(max,height));
  }

  private void forecast() {
    List<StudyStore.Card> words=cohort();
    MemoryForecast value=new MemoryForecast(words,now,horizon,5);
    a.gap(a.body,6);
    int[] spans={7,14,30}; String[] labels={"7 天","14 天","30 天"};
    options(labels,horizon==7?0:horizon==14?1:2,i->{
      horizon=spans[i];cursorDay=Math.min(cursorDay,horizon);redraw(0);
    });
    a.section("还能记住多少",scope()+" · "+words.size()+"词");
    LinearLayout card=panel();
    TextView number=a.serif("",36,PaperActivity.blue);
    TextView selected=a.txt("",14,PaperActivity.muted,false);
    TextView legend=a.txt("",13,0xff84462b,true);
    a.add(card,number,-2); a.add(card,selected,-2);
    Runnable update=()->{
      number.setText(words.isEmpty()?"—":pct(value.unassisted[cursorDay]));
      selected.setText(words.isEmpty()?"还没有练习记录":dayTitle(cursorDay)+" · 新词基准 "
          +pct(value.reference[cursorDay]));
      if (compare && !words.isEmpty()) legend.setText("虚线  ·  先复习 "+value.rehearsedCount+
          " 词，"+dayTitle(cursorDay)+"预计 "+pct(value.rehearsed[cursorDay]));
    };
    update.run();
    MemoryChartView chart=new MemoryChartView(a,value,cursorDay,compare,day->{cursorDay=day;update.run();});
    int height=plotHeight(345,225,365);
    a.gap(card,4); a.add(card,chart,height);
    if (!words.isEmpty()) {
      text(card,"━  你的预测",13,0xff206556,true);
      text(card,"┄  新词基准",13,0xff526c82,true);
    }
    if (compare && !words.isEmpty()) {
      a.add(card,legend,-2);
      a.gap(card,4);
      note(card,"挑目前最容易忘的词，假设它们刚复习完，半衰期保持原值。");
    } else if (!words.isEmpty()) note(card,"按不再复习估算。基准使用新词默认半衰期。");
    a.gap(a.body,8);
    if (words.isEmpty()) {
      button("去练一个词",true,()->a.show("home"));
    } else {
      button(compare?"收起模拟曲线":"模拟今天先复习",false,()->{compare=!compare;redraw(0);});
      link("容易忘的词","按选中日期的预计记住率排列",()->go("risk"));
    }
    link("筛选词库",scope(),()->go("scope"));
    link("复习计划","看未来七天到期的词",()->go("plan"));
  }

  private void selectBook() {
    heading("筛选词库","按当前收录词计算预测和复习计划。同一个词在全部词库中只计一次。");
    link("全部词库",book.isEmpty()?"正在查看":"所有已练词",()->{book="";back();});
    for (Lexicon.Book b:a.lex.books)
      link(b.title,b.k.equals(book)?"正在查看":b.words.size()+" 个收录词",()->{book=b.k;back();});
  }

  private void risks() {
    heading("容易忘的词",scope()+" · "+dayTitle(cursorDay)+"的预计记住率");
    int[] thresholds={60,80,90};
    options(new String[]{"低于 60%","低于 80%","低于 90%"},riskBelow==60?0:riskBelow==80?1:2,
        i->{riskBelow=thresholds[i];page=0;redraw(0);});
    long future=now+(long)(cursorDay*MemoryModel.DAY);
    List<StudyStore.Card> words=cohort();
    words.removeIf(c->MemoryForecast.probability(c,future)>=riskBelow/100d);
    words.sort(Comparator.comparingDouble(c->MemoryForecast.probability(c,future)));
    a.section("需要留意",words.size()+" 词");
    if(words.isEmpty()) note(a.body,"这一天没有低于所选比例的词。");
    cards(words,c->pct(MemoryForecast.probability(c,future))+"  ·  "+c.zh);
  }

  private void plan() {
    List<StudyStore.Card> words=cohort(); int[] days=MemoryForecast.dueDays(words,now);
    int all=0; for(int n:days) all+=n;
    a.section("未来七天",scope());
    LinearLayout card=panel();
    text(card,all+" 词",29,PaperActivity.blue,true);
    note(card,"按目前的到期日统计。已过期的词算在今天，每个词只出现一次。");
    a.gap(a.body,8);
    button("试排每天的复习量",true,()->go("budget"));
    link("筛选词库",scope(),()->go("scope"));
    a.section("到期日历","点日期看单词");
    for(int i=0;i<7;i++) {
      final int n=i;
      String title=i<2?dayTitle(i):date(MemoryForecast.midnight(now,i));
      link(title,days[i]+" 词到期",()->{planDay=n;go("due");});
    }
  }

  private void dueList() {
    heading(dayTitle(planDay)+"的到期词",date(MemoryForecast.midnight(now,planDay))+" · "+scope());
    long start=planDay==0?Long.MIN_VALUE:MemoryForecast.midnight(now,planDay);
    long end=MemoryForecast.midnight(now,planDay+1);
    List<StudyStore.Card> words=cohort();
    words.removeIf(c->c.due<start||c.due>=end);
    words.sort(Comparator.comparingLong(c->c.due));
    if(words.isEmpty()) note(a.body,"这一天没有到期词。");
    cards(words,c->time(c.due)+" 到期  ·  "+c.zh);
  }

  private void budget() {
    heading("一周怎么排",scope()+" · 选择每天能复习的词数");
    int[] capacity={5,10,20};
    options(new String[]{"5 词","10 词","20 词"},budget==5?0:budget==10?1:2,i->{
      budget=capacity[i];a.getPreferences(0).edit().putInt("memoryReviewBudget",budget).apply();redraw(0);
    });
    int[] due=MemoryForecast.dueDays(cohort(),now);
    int[][] slots=MemoryForecast.budget(due,budget);
    a.gap(a.body,12); LinearLayout card=panel();
    int carry=slots[6][1];
    text(card,carry==0?"本周能排下":"本周还有 "+carry+" 词排不下",22,PaperActivity.ink,true);
    note(card,"当天排不下的顺延。这是按现有到期词做的试算，斩词仍用原复习日期。");
    a.gap(card,15);
    for(int d=0;d<7;d++) meter(card,d<2?dayTitle(d):date(MemoryForecast.midnight(now,d)),
        "可练 "+slots[d][0]+" 词  ·  当天到期 "+due[d]+" 词  ·  顺延 "+slots[d][1]+" 词",
        slots[d][0]/(double)budget,PaperActivity.blue);
  }

  private interface Subtitle { String value(StudyStore.Card c); }

  private void cards(List<StudyStore.Card> values,Subtitle sub) {
    int start=Math.min(page*12,values.size()),end=Math.min(start+12,values.size());
    for(int i=start;i<end;i++) {
      StudyStore.Card c=values.get(i);
      link(c.ru,sub.value(c),()->{wordKey=c.k;go("word");});
    }
    if(end<values.size()||start>0) {
      a.gap(a.body,9);
      if(start>0) button("上一页",false,()->{page--;redraw(0);});
      if(end<values.size()) button("下一页",false,()->{page++;redraw(0);});
    }
  }

  private void records() {
    SQLiteStats days=new SQLiteStats();
    a.section("最近两周","全部词库");
    LinearLayout card=panel();
    text(card,"练了 "+days.active+" 天",26,PaperActivity.ink,true);
    note(card,days.hasToday?"今天已练 "+days.today+" 个不同的词。":"今天的练习会记在这里。");
    link("练习日历","按日查看练过的词和次数",()->go("calendar"));
    link("评分流水","每次斩词给过的反馈",()->go("grades"));
    a.section("从记录里找线索","");
    link("预测准不准","把评分前的预测与实际自评放在一起",()->go("fit"));
    link("一天中何时记得牢","按练习时段对照自评结果",()->go("clock"));
    link("哪类词更容易忘","按词性查看当前预计记住率",()->go("pos"));
  }

  private final class SQLiteStats {
    final Map<String,int[]> days=new HashMap<>();
    int active,today;
    boolean hasToday;
    SQLiteStats() {
      String from=dayKey.format(new Date(MemoryForecast.midnight(now,-13)));
      String to=dayKey.format(new Date(now));
      try(Cursor c=a.data.getReadableDatabase().rawQuery(
          "SELECT day,COUNT(*),SUM(n) FROM activity WHERE day BETWEEN ? AND ? GROUP BY day",
          new String[]{from,to})) {
        while(c.moveToNext()) days.put(c.getString(0),new int[]{c.getInt(1),c.getInt(2)});
      }
      active=days.size(); hasToday=days.containsKey(to); today=hasToday?days.get(to)[0]:0;
    }
  }

  private void calendar() {
    heading("练习日历","最近十四天 · 每天的词数与回忆次数");
    SQLiteStats stats=new SQLiteStats();
    for(int i=0;i<14;i++) {
      long stamp=MemoryForecast.midnight(now,-i);
      String key=dayKey.format(new Date(stamp)); int[] n=stats.days.get(key);
      link(i==0?"今天":date(stamp), n==null?"休息日":n[0]+" 个词  ·  "+n[1]+" 次练习",
          ()->{calendarDay=key;go("day");});
    }
  }

  private void day() {
    heading(calendarDay,"当天练过的词");
    List<StudyStore.Card> words=new ArrayList<>(); Map<String,Integer> totals=new HashMap<>();
    try(Cursor cur=a.data.getReadableDatabase().rawQuery(
        "SELECT k,n FROM activity WHERE day=? ORDER BY n DESC,k",new String[]{calendarDay})) {
      while(cur.moveToNext()) {
        String key=cur.getString(0); StudyStore.Card c=a.data.cards.get(key);
        if(c!=null) {words.add(c); totals.put(key,cur.getInt(1));}
      }
    }
    if(words.isEmpty()) note(a.body,"这一天没有练习记录。");
    cards(words,c->totals.get(c.k)+" 次练习  ·  "+c.zh);
  }

  private String grade(int v) { return v==2?"记住了":v==1?"有点模糊":"没记住"; }
  private String logSince() { return Long.toString(MemoryForecast.midnight(now,-29)); }

  private void grades() {
    heading("评分流水","最近三十天 · 最近的反馈在前");
    int n=0;
    try(Cursor c=a.data.getReadableDatabase().rawQuery(
        "SELECT k,at,grade,next_due FROM review_log WHERE at>=? ORDER BY at DESC,id DESC LIMIT 13 OFFSET ?",
        new String[]{logSince(),Integer.toString(page*12)})) {
      while(c.moveToNext()) {
        if(++n>12) break;
        String key=c.getString(0);
        StudyStore.Card card=a.data.cards.get(key);
        String title=(card==null?key:card.ru)+"  ·  "+grade(c.getInt(2));
        link(title,time(c.getLong(1))+"  ·  排到 "+time(c.getLong(3)),()->{wordKey=key;go("word");});
      }
    }
    if(n==0) note(a.body,"从这版开始，每次评分都会记下时间和下次复习日。先去斩一个词吧。");
    if(page>0) button("上一页",false,()->{page--;redraw(0);});
    if(n>12) button("下一页",false,()->{page++;redraw(0);});
  }

  private void calibration() {
    heading("预测准不准","最近三十天 · 有上次记录的复习");
    long total=0; double predicted=0,observed=0; int[] n=new int[3];
    double[] p=new double[3],o=new double[3];
    try(Cursor c=a.data.getReadableDatabase().rawQuery(
        "SELECT CASE WHEN prior < 0.6 THEN 0 WHEN prior < 0.8 THEN 1 ELSE 2 END, COUNT(*),SUM(prior),SUM(grade)*0.5 "
        +"FROM review_log WHERE at>=? AND prior IS NOT NULL GROUP BY 1",new String[]{logSince()})) {
      while(c.moveToNext()) {
        int b=c.getInt(0);n[b]=c.getInt(1);p[b]=c.getDouble(2);o[b]=c.getDouble(3);
        total+=n[b];predicted+=p[b];observed+=o[b];
      }
    }
    LinearLayout card=panel();
    if(total==0) {text(card,"还缺一次复习",22,PaperActivity.ink,true);note(card,"首次见词没有提前预测。再遇到它并评分，才能比较。");return;}
    text(card,total+" 次复习",24,PaperActivity.ink,true);
    note(card,"平均预计 "+pct(predicted/total)+"  ·  实际自评折算 "+pct(observed/total));
    a.gap(card,8); a.add(card,new MemoryCalibrationPlot(a,n,p,o),174);
    note(card,"点越大，这一组的复习次数越多。斜线上表示预计和自评相同。");
    a.gap(card,12);
    String[] bands={"预计低于 60%","预计 60% 至 80%","预计至少 80%"};
    for(int b=0;b<3;b++) if(n[b]>0)
      meter(card,bands[b]+" · "+n[b]+" 次","预计 "+pct(p[b]/n[b])+"  ·  自评 "+pct(o[b]/n[b]),o[b]/n[b],0xffa75f3b);
    note(card,"记住、模糊、忘记分别折算为 1、0.5、0。预测取自评分之前。");
  }

  private void clock() {
    heading("一天中何时记得牢","最近三十天 · 按手机本地时间归组");
    int[] n=new int[4],hits=new int[4];int best=-1;
    try(Cursor c=a.data.getReadableDatabase().rawQuery(
        "SELECT CAST(strftime('%H',at/1000,'unixepoch','localtime') AS INTEGER)/6,COUNT(*),SUM(CASE WHEN grade=2 THEN 1 ELSE 0 END) "
        +"FROM review_log WHERE at>=? AND prior IS NOT NULL GROUP BY 1",new String[]{logSince()})) {
      while(c.moveToNext()){int k=c.getInt(0);n[k]=c.getInt(1);hits[k]=c.getInt(2);}
    }
    LinearLayout card=panel();String[] names={"凌晨 0–6 时","早晨 6–12 时","午后 12–18 时","夜间 18–24 时"};
    int eligible=0;
    for(int i=0;i<n.length;i++) {
      if (n[i]>=3) {eligible++;if(best<0||hits[i]/(double)n[i]>hits[best]/(double)n[best]) best=i;}
      String result=n[i]==0?"没有复习记录":hits[i]+" / "+n[i]+" 次选了「记住了」";
      meter(card,names[i],result,n[i]==0?0:hits[i]/(double)n[i],PaperActivity.blue);
    }
    note(card,eligible<2?"至少两个时段各有三次复习后，再比较各时段的表现。":
        "在各有至少三次复习的时段中，"+names[best]+"的「记住了」占比最高。词的难度也会影响结果。");
  }

  private void wordClasses() {
    heading("哪类词更容易忘","全部已练词 · 按当前预计记住率从低到高排列");
    Map<String,double[]> groups=new TreeMap<>();
    for(StudyStore.Card c:a.data.cards.values()) if(c.seen>0) {
      String pos=c.pos==null||c.pos.isEmpty()?"未标词性":c.pos;
      double[] g=groups.computeIfAbsent(pos,k->new double[2]);
      g[0]++;g[1]+=MemoryForecast.probability(c,now);
    }
    if(groups.isEmpty()){note(a.body,"练过带词性的词后，这里会按词性归组。");return;}
    List<String> names=new ArrayList<>(groups.keySet());
    names.sort(Comparator.comparingDouble(k->groups.get(k)[1]/groups.get(k)[0]));
    LinearLayout card=panel();
    for(String name:names) {
      double[] g=groups.get(name);
      meter(card,name,"已练 "+(int)g[0]+" 词  ·  预计记住 "+pct(g[1]/g[0]),g[1]/g[0],PaperActivity.blue);
    }
  }

  private void word() {
    StudyStore.Card c=a.data.cards.get(wordKey);
    if(c==null){heading("单词履历","这条词卡已移出本机。");return;}
    heading(c.ru,c.zh);
    LinearLayout card=panel();
    text(card,"预计记住 "+pct(MemoryForecast.probability(c,now)),22,PaperActivity.ink,true);
    note(card,"模型半衰期 "+String.format(Locale.ROOT,"%.1f",c.half)+" 天  ·  已练 "+c.seen+" 次");
    a.gap(card,8);note(card,"上次练习 "+time(c.last)+"\n下次复习 "+time(c.due));
    a.gap(a.body,10);button("打开词条",true,()->a.open(c.word(),"memory"));
    a.section("最近的反馈","最多 8 次");
    int count=0;
    try(Cursor cur=a.data.getReadableDatabase().rawQuery(
        "SELECT at,grade,prior,half FROM review_log WHERE k=? ORDER BY at DESC,id DESC LIMIT 8",
        new String[]{c.k})) {
      while(cur.moveToNext()) {
        count++;
        text(a.body,time(cur.getLong(0))+"  ·  "+grade(cur.getInt(1)),16,PaperActivity.ink,true);
        note(a.body,(cur.isNull(2)?"初次见词":"评分前预计 "+pct(cur.getDouble(2)))
            +"  ·  更新后半衰期 "+String.format(Locale.ROOT,"%.1f",cur.getDouble(3))+" 天");
        a.gap(a.body,8);
      }
    }
    if(count==0) note(a.body,"旧版只保存累计次数。之后的逐次评分会列在这里。");
  }
}
