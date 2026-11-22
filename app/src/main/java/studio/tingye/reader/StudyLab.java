package studio.tingye.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.*;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import org.json.*;

import static studio.tingye.reader.StudyLabEngine.*;

/** Three-question offline workshops, with separate, durable skill and retry records. */
final class StudyLab {
  private final WordActivity a;
  private final SharedPreferences prefs;
  private final StudyLabEngine engine;
  private enum Page { HOME, ROUND, MISTAKES, STATS }
  private Page page=Page.HOME;
  private int group;
  private Round round;
  private Question question;
  private LinearLayout work, controls;
  private EditText edit;
  private TextView assembly, writingError;
  private boolean keyboard;
  private int keyboardPage;
  private final ArrayList<View> tiles=new ArrayList<>();
  private static final String[] GROUPS={"听与写","词与形","句与用"};

  static final class Ref {
    final Mode mode;
    final String id;
    final Long fixedSeed;
    Ref(Mode mode,String id) {this(mode,id,null);}
    Ref(Mode mode,String id,Long fixedSeed) {this.mode=mode;this.id=id;this.fixedSeed=fixedSeed;}
    String key(){return mode.name()+"/"+id;}
    long seed(long fallback){return fixedSeed==null?fallback:fixedSeed;}
    String encode(){return fixedSeed==null?key():key()+"|"+fixedSeed;}
    static Ref parse(String value) {
      int cut=value.indexOf('/');
      if(cut<1) throw new IllegalArgumentException("Bad exercise reference");
      int suffix=value.indexOf('|',cut);
      if(suffix<0)return new Ref(Mode.valueOf(value.substring(0,cut)),value.substring(cut+1));
      return new Ref(Mode.valueOf(value.substring(0,cut)),value.substring(cut+1,suffix),Long.valueOf(value.substring(suffix+1)));
    }
  }
  static final class Round {
    final ArrayList<Ref> refs=new ArrayList<>();
    final ArrayList<Integer> picked=new ArrayList<>();
    long seed;
    int index, correct;
    String draft="", submitted="";
    boolean marked, retry, peeked, showingOriginal;
    Ref current(){return refs.get(index);}
    boolean finished(){return index>=refs.size();}
    String encode() {
      try {
        JSONArray ids=new JSONArray();for(Ref ref:refs)ids.put(ref.encode());
        return new JSONObject().put("items",ids).put("picked",new JSONArray(picked))
            .put("seed",seed).put("i",index).put("correct",correct).put("draft",draft)
            .put("submitted",submitted).put("marked",marked).put("retry",retry)
            .put("peeked",peeked).put("showingOriginal",showingOriginal).toString();
      } catch(JSONException e) {throw new IllegalStateException(e);}
    }
    static Round restore(String json,StudyLabEngine engine) {
      if(json.isEmpty())return null;
      try {
        JSONObject state=new JSONObject(json);
        JSONArray items=state.getJSONArray("items");
        Round r=new Round();
        for(int i=0;i<items.length();i++) {
          Ref ref=Ref.parse(items.getString(i));
          if(!engine.pools.get(ref.mode).contains(ref.id))return null;
          r.refs.add(ref);
        }
        r.seed=state.getLong("seed");r.index=state.getInt("i");r.correct=state.getInt("correct");
        if(r.refs.isEmpty() || r.refs.size()>5 || r.index<0 || r.index>r.refs.size())return null;
        r.draft=state.optString("draft");r.submitted=state.optString("submitted");
        r.marked=state.optBoolean("marked");r.retry=state.optBoolean("retry");
        r.peeked=state.optBoolean("peeked");r.showingOriginal=state.optBoolean("showingOriginal");
        JSONArray picked=state.getJSONArray("picked");
        for(int i=0;i<picked.length();i++)r.picked.add(picked.getInt(i));
        return r;
      } catch(JSONException|IllegalArgumentException e){return null;}
    }
  }

  StudyLab(WordActivity activity) {
    a=activity;
    prefs=a.getSharedPreferences("slovo.study.lab.1",Context.MODE_PRIVATE);
    List<Entry> entries=new ArrayList<>();
    for (Lexicon.Word w:a.lex.core.values()) {
      JSONObject hints=a.lex.usage.optJSONObject(w.k);
      entries.add(new Entry(w.k,w.ru,w.zh,w.pos,value(hints,"stress",w.ru),
          value(hints,"forms",""),value(hints,"example",""),value(hints,"translation","")));
    }
    engine=new StudyLabEngine(entries);
    round=Round.restore(prefs.getString("round",""),engine);
    group=Math.max(0,Math.min(2,prefs.getInt("group",0)));
  }
  private static String value(JSONObject j,String key,String fallback) {
    return j==null ? fallback : j.optString(key,fallback);
  }
  void render() {
    switch(page) {
      case ROUND: drawRound(false);break;
      case MISTAKES: mistakes();break;
      case STATS: stats();break;
      default: home();
    }
  }
  boolean back() {
    if(page==Page.HOME)return false;
    if(a.voice!=null)a.voice.stop();
    page=Page.HOME;home();
    return true;
  }
  private void clear(Page next) {
    hideKeyboard();
    page=next;
    a.body.removeAllViews();
    work=a.body; edit=null; assembly=null;writingError=null;tiles.clear();
    a.scroll.scrollTo(0,0);
  }
  private void hideKeyboard(){
    if(edit!=null && edit.hasFocus()) {
      InputMethodManager ime=(InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE);
      ime.hideSoftInputFromWindow(edit.getWindowToken(),0);edit.clearFocus();
    }
  }
  private void finish(){a.finishTheme("lab");}
  private void text(LinearLayout host,String s,int size,int color,boolean bold){
    a.add(host,a.txt(s,size,color,bold),-2);
  }
  private void button(LinearLayout host,String s,boolean primary,Runnable open){
    a.gap(host,7);a.add(host,a.action(s,0,primary,open),-2);
  }
  private LinearLayout panel() {
    LinearLayout card=a.box(true);
    card.setBackground(a.surface(0xf4f9f6ee,18,0x90919e96));
    a.pad(card,16,15,16,15);a.add(a.body,card,-2);
    return card;
  }
  private void home() {
    clear(Page.HOME);
    a.gap(work,12);
    Mode suggest=recommendation();
    LinearLayout lead=panel();
    int total=prefs.getInt("total",0);
    boolean continuing=round!=null && !round.finished();
    text(lead,continuing?"上次练到这里":total==0?"从哪儿练起":"今天先练",14,a.muted,true);
    a.gap(lead,5);text(lead,continuing?round.current().mode.label:suggest.label,23,a.ink,true);
    a.gap(lead,5);
    String why=prefs.getInt("count/"+suggest.name(),0)>0 ?
        "按你各项练习的正确率和练过的次数，先补这项。" : "这项还没做过。先试三题，看看哪儿需要再练。";
    if(continuing) why="第 "+(round.index+1)+" / "+round.refs.size()+" 题"+(round.marked?"，已经核对过答案。":"，接着上次继续。");
    text(lead,why,14,a.muted,false);
    Runnable open=continuing?()->{keyboard=false;drawRound(true);}:()->begin(suggest);
    a.touchAction(lead,open);
    button(lead,continuing?"继续上次练习":"练这三题",true,open);
    a.section("挑一项练习","");
    LinearLayout groups=a.box(false);
    for(int i=0;i<GROUPS.length;i++) {
      final int g=i;
      View v=a.action(GROUPS[i],0,i==group,()->{
        group=g;prefs.edit().putInt("group",group).apply();home();
      });
      a.wide(groups,v);v.setSelected(i==group);if(i<2)a.gap(groups,5);
    }
    a.add(work,groups,-2);
    for(Mode m:Mode.values()) if(m.group==group)
      a.row(work,m.label,m.description,R.drawable.s_book,()->begin(m));
    a.section("练习手记","");
    int count=mistakeKeys().size();
    a.row(work,"错题回练",count==0?"还没有待重练的题":count+" 题等你再试",R.drawable.s_check,this::mistakes);
    a.row(work,"练习记录",total==0?"完成第一题后开始记录":"已经答过 "+total+" 题",R.drawable.s_shelf,this::stats);
    finish();
  }
  private Mode recommendation() {
    // Beta(2, 1) correct-rate prior balances a new skill against repeated errors.
    Mode result=Mode.LISTEN;double lowest=Double.POSITIVE_INFINITY;
    for(Mode m:Mode.values()) {
      int n=prefs.getInt("count/"+m.name(),0), bad=prefs.getInt("bad/"+m.name(),0);
      double confidence=(n-bad+2.0)/(n+3.0)+.06*Math.min(1,n/8.0);
      if(confidence<lowest){lowest=confidence;result=m;}
    }
    return result;
  }
  private void begin(Mode mode) {
    Round next=new Round();next.seed=new Random().nextLong();
    Random rnd=new Random(next.seed);
    long now=System.currentTimeMillis();
    Set<String> book=new HashSet<>(a.lex.book(a.data.plan).words);
    List<String> ids=new ArrayList<>(engine.pools.get(mode));
    Map<String,Double> scores=new HashMap<>();
    for(String id:ids) {
      String key=mode.name()+"/"+id;
      int n=prefs.getInt("n/"+key,0), wrong=prefs.getInt("err/"+key,0);
      long last=prefs.getLong("last/"+key,0);
      StudyStore.Card card=a.data.cards.get(id);
      double recall=card==null || card.seen==0 ? .75 :
          MemoryModel.recall((now-card.last)/MemoryModel.DAY,card.half);
      scores.put(id,priority(n,wrong,prefs.getBoolean("fix/"+key,false),now-last,recall,book.contains(id),rnd.nextDouble()));
    }
    ids.sort((x,y)->Double.compare(scores.get(y),scores.get(x)));
    for(String id:ids.subList(0,Math.min(3,ids.size())))next.refs.add(new Ref(mode,id));
    startRound(next);
  }
  private void startRound(Round next) {
    if(next.refs.isEmpty())return;
    round=next;keyboard=false;save();drawRound(true);
  }
  private void save(){if(round!=null)prefs.edit().putString("round",round.encode()).apply();}

  private List<Ref> mistakeKeys() {
    List<Ref> out=new ArrayList<>();
    for(Map.Entry<String,?> item:prefs.getAll().entrySet()) {
      String key=item.getKey();
      if(key.startsWith("fix/") && Boolean.TRUE.equals(item.getValue())) {
        try {
          Ref ref=Ref.parse(key.substring(4));
          if(engine.pools.get(ref.mode).contains(ref.id))out.add(ref);
        } catch(IllegalArgumentException ignored) {}
      }
    }
    out.sort((x,y)->Long.compare(prefs.getLong("last/"+y.key(),0),prefs.getLong("last/"+x.key(),0)));
    return out;
  }
  private void mistakes() {
    clear(Page.MISTAKES);List<Ref> missed=mistakeKeys();
    a.title("错题回练",missed.isEmpty()?"这里会收下专项练习里答错的题。":"独立答对一次，就会从这里移走。");
    if(missed.isEmpty())button(work,"挑一项练习",true,this::home);
    else {
      button(work,"重练最近的错题",true,()->retry(missed.subList(0,Math.min(5,missed.size()))));
      a.gap(work,8);
      for(Ref ref:missed.subList(0,Math.min(25,missed.size()))) {
        Entry word=engine.words.get(ref.id);
        String material=word==null ? engine.question(ref.mode,ref.id,0).material : word.ru;
        a.row(work,material,ref.mode.label,R.drawable.s_book,()->retry(Collections.singletonList(ref)));
      }
      if(missed.size()>25)text(work,"先显示最近 25 题，练完后会往前翻。",14,a.muted,false);
    }
    finish();
  }
  private void retry(List<Ref> refs) {
    Round r=new Round();r.retry=true;r.seed=new Random().nextLong();
    for(Ref ref:refs)r.refs.add(new Ref(ref.mode,ref.id,prefs.getLong("fixSeed/"+ref.key(),r.seed)));
    startRound(r);
  }
  private void stats() {
    clear(Page.STATS);int n=prefs.getInt("total",0),good=prefs.getInt("correct",0);
    a.title("练习记录",n==0?"还没开始。挑一项做完第一题，再回来看看。":"共答 "+n+" 题，答对 "+good+" 题。");
    for(Mode m:Mode.values()) {
      int all=prefs.getInt("count/"+m.name(),0), misses=prefs.getInt("bad/"+m.name(),0);
      a.row(work,m.label,all==0?"还没练过":(all-misses)+" / "+all+" 次答对",R.drawable.s_book,()->begin(m));
    }
    finish();
  }

  private void drawRound(boolean autoPlay) {
    if(round==null){home();return;}
    clear(Page.ROUND);
    if(round.finished()){summary();return;}
    Ref ref=round.current();
    question=engine.question(ref.mode,ref.id,ref.seed(round.seed));
    a.gap(work,13);
    LinearLayout meta=a.box(false);
    a.wide(meta,a.txt(ref.mode.label,16,a.ink,true));
    meta.addView(a.txt((round.index+1)+" / "+round.refs.size(),15,a.blue,true));a.add(work,meta,-2);
    a.gap(work,9);
    ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(round.refs.size());progress.setProgress(round.index+(round.marked?1:0));
    progress.setContentDescription("已完成 "+progress.getProgress()+" 题");
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(a.blue));a.add(work,progress,5);
    a.gap(work,12);
    LinearLayout card=panel();
    text(card,question.prompt,16,a.muted,true);
    if(!question.material.isEmpty()) {
      a.gap(card,9);TextView material=a.serif(question.material,question.material.length()>44?20:25,a.ink);
      material.setTextIsSelectable(false);a.add(card,material,-2);
    }
    if(!question.audio.isEmpty()) {
      button(card,"听录音",true,()->a.voice.play(question.audio));
      if(autoPlay && !round.marked)a.voice.play(question.audio);
    }
    if(round.marked) {feedback();finish();return;}
    if(question.input==Input.WORDS) {
      if(round.showingOriginal) {
        a.gap(card,8);a.add(card,a.serif(question.answer,19,a.ink),-2);
        button(card,"收起原句，开始排",true,()->{
          round.showingOriginal=false;save();drawRound(false);
        });
        finish();return;
      }
      if(!round.peeked)button(card,"先看一遍原句",false,()->{
        round.peeked=true;round.showingOriginal=true;save();drawRound(false);
      });
    }
    switch(question.input) {
      case CHOICE:
        for(String option:question.choices)button(work,option,false,()->mark(option));break;
      case WRITE: writing();break;
      case LETTERS:
      case WORDS: assembling();break;
    }
    finish();
  }
  private void writing() {
    a.gap(work,11);
    edit=new EditText(a);
    edit.setSingleLine(true);edit.setTextSize(20);edit.setTextColor(a.ink);edit.setHintTextColor(a.muted);
    edit.setHint("写下俄语答案");edit.setContentDescription("俄语答案");
    edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
    edit.setBackground(a.surface(0xfff9f9ef,14,a.blue));a.pad(edit,12,9,12,9);
    edit.setMinimumHeight(a.dp(52));edit.setText(round.draft);edit.setSelection(edit.length());
    a.add(work,edit,-2);
    LinearLayout row=a.box(false);a.gap(work,8);a.add(work,row,-2);
    a.wide(row,a.action("俄文字母",0,false,()->{keyboard=!keyboard;drawRound(false);}));
    a.gap(row,6);
    View check=a.action("核对答案",0,true,()->submitText());
    a.wide(row,check);
    writingError=a.txt("先写一个词，再核对答案。",14,a.hot,true);
    writingError.setVisibility(View.GONE);a.add(work,writingError,-2);
    edit.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){
        if(writingError!=null && s.length()>0)writingError.setVisibility(View.GONE);
        if(page==Page.ROUND && !round.marked){round.draft=s.toString();save();}
      }
      public void afterTextChanged(Editable e){}
    });
    edit.setOnEditorActionListener((view,action,event)->{
      if(action!=EditorInfo.IME_ACTION_DONE)return false;
      submitText();return true;
    });
    if(keyboard) {
      LinearLayout keys=a.box(true);a.add(work,keys,-2);drawKeyboard(keys);
      EditText current=edit;
      current.post(()->{if(current==edit && keyboard)a.scroll.scrollTo(0,current.getTop());});
    }
  }
  private void drawKeyboard(LinearLayout keys) {
    keys.removeAllViews();
    String[] sets={"абвгдеёжзий","клмнопрстуф","хцчшщъыьэюя"};
    String[] names={"А – Й","К – Ф","Х – Я"};
    LinearLayout pages=a.box(false);a.gap(keys,8);a.add(keys,pages,-2);
    for(int i=0;i<sets.length;i++) {
      final int pageIndex=i;
      if(i>0)a.gap(pages,5);
      a.wide(pages,a.action(names[i],0,i==keyboardPage,()->{
        keyboardPage=pageIndex;drawKeyboard(keys);
      }));
    }
    List<String> letters=new ArrayList<>();List<Runnable> tasks=new ArrayList<>();
    for(char letter:sets[keyboardPage].toCharArray())letters.add(String.valueOf(letter));
    letters.add("退格");
    for(String key:letters)tasks.add(()->{
        int lo=Math.max(0,edit.getSelectionStart()),hi=Math.max(0,edit.getSelectionEnd());
        if(key.equals("退格")) {
          if(lo==hi && lo>0)lo--;
          edit.getText().delete(Math.min(lo,hi),Math.max(lo,hi));
        } else edit.getText().replace(Math.min(lo,hi),Math.max(lo,hi),key);
      });
    grid(keys,letters,tasks,false);
  }
  private void submitText(){
    if(edit==null)return;
    String typed=edit.getText().toString().trim();
    if(typed.isEmpty()){writingError.setVisibility(View.VISIBLE);return;}
    mark(typed);
  }
  private void assembling() {
    a.gap(work,11);assembly=a.serif("",20,a.ink);
    assembly.setMinHeight(a.dp(48));a.pad(assembly,12,8,12,8);
    assembly.setBackground(a.surface(0xf4f9f6ee,12,a.line));a.add(work,assembly,-2);
    List<Runnable> events=new ArrayList<>();
    for(int i=0;i<question.choices.size();i++){
      final int at=i;
      events.add(()->{if(!round.picked.contains(at)){round.picked.add(at);updateTiles();save();}});
    }
    grid(work,question.choices,events,true);
    a.gap(work,8);
    controls=a.box(false);a.add(work,controls,-2);
    a.wide(controls,a.action("撤回一项",0,false,()->{
      if(!round.picked.isEmpty()){round.picked.remove(round.picked.size()-1);updateTiles();save();}
    }));a.gap(controls,6);
    a.wide(controls,a.action("核对答案",0,true,()->mark(assembled())));
    updateTiles();
  }
  private String assembled(){
    ArrayList<String> chosen=new ArrayList<>();
    for(int index:round.picked) if(index>=0 && index<question.choices.size())chosen.add(question.choices.get(index));
    return android.text.TextUtils.join(question.input==Input.WORDS?" ":"",chosen);
  }
  private void updateTiles(){
    String current=assembled();
    assembly.setText(current.isEmpty()?"…":current);
    for(int i=0;i<tiles.size();i++){boolean can=!round.picked.contains(i);tiles.get(i).setEnabled(can);tiles.get(i).setAlpha(can?1f:.42f);}
    View check=controls.getChildAt(2),undo=controls.getChildAt(0);
    check.setEnabled(round.picked.size()==tiles.size());check.setAlpha(check.isEnabled()?1f:.52f);
    undo.setEnabled(!round.picked.isEmpty());undo.setAlpha(undo.isEnabled()?1f:.52f);
  }
  private void grid(LinearLayout host,List<String> labels,List<Runnable> actions,boolean wordTiles){
    boolean longTiles=wordTiles && question.input==Input.WORDS;
    int columns=longTiles ? 2 : 4;
    for(int pos=0;pos<labels.size();pos+=columns){
      LinearLayout row=a.box(false);a.gap(host,5);a.add(host,row,-2);
      for(int i=0;i<columns;i++) {
        if(i>0)a.gap(row,5);
        int k=pos+i;
        if(k>=labels.size()){a.wide(row,new View(a));continue;}
        View v=a.action(labels.get(k),0,false,actions.get(k));a.wide(row,v);
        if(wordTiles)tiles.add(v);
      }
    }
  }
  private void mark(String response) {
    if(round==null || round.finished() || round.marked)return;
    Ref ref=round.current();boolean right=question.accepts(response);
    String k=ref.key();SharedPreferences.Editor e=prefs.edit();
    e.putInt("total",prefs.getInt("total",0)+1);
    e.putInt("count/"+ref.mode.name(),prefs.getInt("count/"+ref.mode.name(),0)+1);
    e.putInt("n/"+k,prefs.getInt("n/"+k,0)+1).putLong("last/"+k,System.currentTimeMillis());
    if(right) {
      round.correct++;
      if(!round.peeked)e.remove("fix/"+k).remove("fixSeed/"+k);
      e.putInt("correct",prefs.getInt("correct",0)+1);
    }else{
      e.putInt("err/"+k,prefs.getInt("err/"+k,0)+1);
      e.putInt("bad/"+ref.mode.name(),prefs.getInt("bad/"+ref.mode.name(),0)+1);
      e.putBoolean("fix/"+k,true).putLong("fixSeed/"+k,ref.seed(round.seed));
    }
    round.marked=true;round.submitted=response;
    e.putString("round",round.encode()).apply();
    keyboard=false;drawRound(false);
  }
  private void feedback() {
    boolean right=question.accepts(round.submitted);
    a.section(right?"答对了":"再看一眼","");
    if(!right){
      text(work,"你的答案　"+round.submitted,16,a.muted,false);a.gap(work,7);
      if(question.input==Input.WRITE)text(work,spellingHelp(round.submitted,question.answer),15,a.hot,true);
    }
    text(work,"参考答案　"+question.answer,19,a.ink,true);a.gap(work,7);
    text(work,question.reason,15,a.muted,false);
    if(right && round.peeked && prefs.getBoolean("fix/"+round.current().key(),false))
      text(work,"这次看过原句。独立排对一次，就会从错题里移走。",15,a.muted,false);
    if(!right && round.current().mode==Mode.NEAR) {
      for(Entry e:engine.words.values())if(e.ru.equals(round.submitted)) {
        text(work,e.ru+" 的意思是「"+e.zh+"」。",15,a.ink,false);break;
      }
    }
    Entry word=engine.words.get(round.current().id);
    if(word!=null && question.audio.isEmpty())button(work,"听单词发音",false,()->a.voice.play(word.ru));
    button(work,round.index+1==round.refs.size()?"看本轮结果":"下一题",true,()->{
      if(!round.marked)return;
      round.index++;round.marked=false;round.submitted="";round.draft="";round.picked.clear();
      round.peeked=false;round.showingOriginal=false;
      save();drawRound(true);
    });
  }
  private void summary() {
    a.title(round.retry?"这组错题练完了":"这组练完了",round.refs.size()+" 题里答对了 "+round.correct+" 题。");
    List<Ref> missed=new ArrayList<>();
    for(Ref ref:round.refs)if(prefs.getBoolean("fix/"+ref.key(),false))missed.add(ref);
    if(!missed.isEmpty())button(work,"重做本轮错题",true,()->retry(missed));
    button(work,"再练三题",missed.isEmpty(),()->begin(round.refs.get(0).mode));
    button(work,"回到专项练习",false,this::home);finish();
  }
}
