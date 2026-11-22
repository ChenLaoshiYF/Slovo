package studio.tingye.reader;

import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Local reading history, bilingual keepsakes and scenery choices. */
final class PocketNotebook {
  private final WordActivity a;
  private static final int PHOTOS_PER_PAGE=12;
  private int scenicPage;
  PocketNotebook(WordActivity activity) { a = activity; }

  private SharedPreferences prefs() { return a.getPreferences(0); }

  private JSONArray list(String key) {
    try { return new JSONArray(prefs().getString(key, "[]")); }
    catch (JSONException invalid) { return new JSONArray(); }
  }

  private boolean contains(String key, String ru) {
    JSONArray all = list(key);
    for (int i=0; i<all.length(); i++) if (all.optJSONArray(i).optString(0).equals(ru)) return true;
    return false;
  }

  private void update(String key, JSONArray item, boolean add, int limit) {
    JSONArray previous = list(key), result = new JSONArray();
    if (add) result.put(item);
    for (int i=0; i<previous.length() && result.length()<limit; i++) {
      JSONArray row = previous.optJSONArray(i);
      if (row != null && !row.optString(0).equals(item.optString(0))) result.put(row);
    }
    prefs().edit().putString(key, result.toString()).apply();
  }

  void remember(Lexicon.Word w) {
    update("recentWords", new JSONArray().put(w.ru).put(w.zh).put(w.k).put(w.pos), true, 8);
  }

  void recent(LinearLayout host) {
    JSONArray all = list("recentWords");
    if (all.length()==0) return;
    a.subhead(host, "最近查过");
    for (int i=0; i<all.length(); i++) {
      JSONArray r = all.optJSONArray(i);
      Lexicon.Word w = new Lexicon.Word(r.optString(2), r.optString(0), r.optString(1), r.optString(3));
      a.row(host,w.ru,a.brief(w.zh),R.drawable.s_search,()->a.open(w,"search"));
    }
    a.add(host, a.action("清空查词记录",0,false,()->new FeedbackDialog(a)
        .setTitle("清空最近查过的词？").setMessage("已加入词库的词会保留。")
        .setNegativeButton("保留",null).setPositiveButton("清空",(d,i)->{
          prefs().edit().remove("recentWords").apply(); a.lookup();
        }).show()),-2);
  }

  void postcardActions(LinearLayout panel, String[] entry, int location) {
    JSONArray item = new JSONArray().put(entry[0]).put(entry[1]).put(entry[2]).put(location);
    a.gap(panel,14);
    LinearLayout controls = a.box(false);
    a.add(panel,controls,-2);
    drawPostcardActions(controls,item);
    a.row(panel,"收藏的明信片","",R.drawable.s_book,()->a.show("keepsakes"));
  }

  private void drawPostcardActions(LinearLayout controls, JSONArray item) {
    controls.removeAllViews();
    String ru = item.optString(0);
    boolean saved = contains("postcards",ru);
    a.wide(controls,a.action("听俄语原句",R.drawable.s_audio,false,()->a.voice.play(ru)));
    a.gap(controls,8);
    a.wide(controls,a.action(saved ? "取消收藏" : "收藏这句", saved ? R.drawable.s_check : R.drawable.s_plus,false,()->{
      update("postcards",item,!saved,Integer.MAX_VALUE);
      drawPostcardActions(controls,item);
    }));
  }

  void collection() {
    JSONArray saved = list("postcards");
    a.section("我的摘句", saved.length()+" 句");
    if (saved.length()==0) {
      a.add(a.body,a.txt("遇到想留下的俄语，点明信片上的「收藏这句」。",16,a.ink,false),-2);
      a.gap(a.body,12);
      a.add(a.body,a.action("去读明信片",R.drawable.s_book,true,()->a.show("quotes")),-2);
    }
    for (int i=0;i<saved.length();i++) {
      JSONArray r=saved.optJSONArray(i);
      a.add(a.body,a.serif(r.optString(0),22,a.ink),-2);
      a.gap(a.body,6); a.add(a.body,a.txt(r.optString(1),16,a.ink,false),-2);
      a.gap(a.body,6); a.add(a.body,a.txt(r.optString(2),14,a.muted,false),-2);
      a.gap(a.body,10);
      LinearLayout controls=a.box(false);
      a.wide(controls,a.action("听这句",R.drawable.s_audio,false,()->a.voice.play(r.optString(0))));
      a.gap(controls,8);
      a.wide(controls,a.action("移出收藏",0,false,()->{
        update("postcards",r,false,Integer.MAX_VALUE); a.show("keepsakes");
      }));
      a.add(a.body,controls,-2); a.gap(a.body,16); a.rule(a.body); a.gap(a.body,12);
    }
  }

  private void applyAppearance() {
    int y=a.scroll.getScrollY();
    a.show("appearance");
    ScrollView view=a.scroll;
    view.post(()->view.scrollTo(0,y));
  }

  void appearance() {
    int selected=prefs().getInt(FieldArt.SCENE_PREF,-1);
    if(selected>=FieldArt.SCENES.length) selected=-1;
    a.section("背景", selected<0?"随页面变化":FieldArt.SCENE_LABELS[selected]);
    a.add(a.body,a.action("随页面换景", selected<0?R.drawable.s_check:R.drawable.s_image,selected<0,()->{
      prefs().edit().putInt(FieldArt.SCENE_PREF,-1).apply(); applyAppearance();
    }),-2);
    a.section("固定一处风景",FieldArt.SCENES.length+" 处俄罗斯实景");
    int pageCount=(FieldArt.SCENES.length+PHOTOS_PER_PAGE-1)/PHOTOS_PER_PAGE;
    LinearLayout paging=a.box(false);
    View previous=a.action("上一组",0,false,()->turnScenery(-1));
    View next=a.action("下一组",0,false,()->turnScenery(1));
    previous.setEnabled(scenicPage>0);
    next.setEnabled(scenicPage+1<pageCount);
    TextView index=a.txt((scenicPage+1)+" / "+pageCount,14,a.muted,true);
    index.setGravity(Gravity.CENTER);
    a.wide(paging,previous);
    paging.addView(index,a.dp(72),a.dp(48));
    a.wide(paging,next);
    a.add(a.body,paging,-2);
    a.gap(a.body,9);
    int first=scenicPage*PHOTOS_PER_PAGE;
    int end=Math.min(first+PHOTOS_PER_PAGE,FieldArt.SCENES.length);
    for (int i=first;i<end;i+=2) {
      LinearLayout row=a.box(false);
      for(int j=i;j<Math.min(i+2,end);j++) {
        final int loc=j;
        LinearLayout tile=a.box(true);
        tile.setBackground(FieldArt.scenePreview(a.getResources(),loc));
        a.pad(tile,10,12,10,10);
        tile.setGravity(Gravity.BOTTOM); tile.setMinimumHeight(a.dp(95));
        String name=FieldArt.SCENE_LABELS[j];
        TextView t=a.txt(name+(j==selected?"　✓":""),14,a.white,true);
        t.setShadowLayer(a.dp(3),0,1,0xff172b28); a.add(tile,t,-2);
        tile.setContentDescription("选择风景 "+name); tile.setSelected(j==selected);
        a.touchAction(tile,()->{ prefs().edit().putInt(FieldArt.SCENE_PREF,loc).apply(); applyAppearance(); });
        tile.setFocusable(true);
        if(j>i)a.gap(row,8);
        a.wide(row,tile);
      }
      a.add(a.body,row,-2); a.gap(a.body,8);
    }
  }

  void openAppearance() {
    int selected=prefs().getInt(FieldArt.SCENE_PREF,-1);
    scenicPage=selected>=0 && selected<FieldArt.SCENES.length?selected/PHOTOS_PER_PAGE:0;
    a.show("appearance");
  }

  private void turnScenery(int offset) {
    scenicPage+=offset;
    a.show("appearance");
  }
}
