package studio.tingye.reader;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Full licensed dictionary data, read on the I/O worker rather than the UI thread. */
final class DictionaryDetails {
  private final WordActivity a;
  private SQLiteDatabase db;
  DictionaryDetails(WordActivity activity) { a=activity; }

  private void prepare() throws IOException {
    if(db!=null)return;
    File file=new File(a.getFilesDir(),"dictionary-details-v2.sqlite");
    if(!file.isFile()) {
      File part=new File(file.getPath()+".part");
      try(InputStream input=new GZIPInputStream(a.getAssets().open("dictionary-details.bin"));
          OutputStream output=new FileOutputStream(part)) {
        byte[] buffer=new byte[65536];
        for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);
      }
      if(!part.renameTo(file))throw new IOException("无法准备详细词典");
    }
    db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
  }

  JSONObject entry(String key) throws Exception {
    prepare();
    try(Cursor c=db.rawQuery("SELECT body FROM entries WHERE k=?",new String[]{Lexicon.key(key)})) {
      return c.moveToFirst()?new JSONObject(c.getString(0)):null;
    }
  }

  synchronized JSONObject resolve(String key) throws Exception {
    JSONObject exact=entry(key);
    if(exact!=null && exact.has("lemma")) {
      JSONObject base=entry(exact.optString("lemma"));
      if(base!=null) return new JSONObject().put("entry",base).put("form",exact.optString("stress",key))
          .put("formLabel",exact.optString("formLabel")).put("ipa",exact.optString("ipa"));
    }
    if(exact==null || exact.optJSONArray("forms")==null || exact.optJSONArray("forms").length()==0) {
      try(Cursor c=db.rawQuery("SELECT k,label FROM forms WHERE f=? ORDER BY k LIMIT 4",new String[]{Lexicon.key(key)})) {
        while(c.moveToNext()) {
          JSONObject base=entry(c.getString(0));
          if(base!=null && base.optJSONArray("forms")!=null && base.optJSONArray("forms").length()>0)
            return new JSONObject().put("entry",base).put("form",exact==null?key:exact.optString("stress",key))
                .put("formLabel",c.getString(1)).put("ipa",exact==null?"":exact.optString("ipa"));
        }
      }
    }
    return exact==null?null:new JSONObject().put("entry",exact);
  }

  void render(Lexicon.Word word) {
    LinearLayout main=a.box(true);
    a.add(a.body,main,-2);
    TextView loading=a.txt("正在打开详细词条…",14,PaperActivity.muted,false);
    a.add(main,loading,-2);
    LinearLayout side=a.secondary();
    LinearLayout extra=side==a.body?main:a.box(true);
    if(extra!=main)a.add(side,extra,-2);
    a.io.execute(()->{
      JSONObject value=null; String failure="";
      try { value=resolve(word.k); }
      catch(Exception error) { failure="详细词条没有打开，点此重试"; android.util.Log.w("Slovo","Dictionary details",error); }
      JSONObject result=value; String problem=failure;
      a.ui.post(()->{
        if(a.isDestroyed() || !main.isAttachedToWindow())return;
        main.removeAllViews();
        if(!problem.isEmpty()) {
          a.add(main,a.action(problem,0,false,()->a.show("detail")),-2);return;
        }
        if(result==null) {
          a.add(main,a.txt("这条词暂时只有简明释义。可以查看在线词典。",14,PaperActivity.muted,false),-2);
          online(main,word.ru);return;
        }
        draw(main,extra,result,word);
        a.finishTheme("detail");
      });
    });
  }

  private void title(LinearLayout target,String text) {
    a.gap(target,20); a.add(target,a.txt(text,18,PaperActivity.blue,true),-2); a.gap(target,9);
  }
  private void text(LinearLayout target,String value,int size) {
    if(value.isEmpty())return;
    TextView label=a.txt(value,size,PaperActivity.ink,false);
    label.setLineSpacing(a.dp(4),1f); a.selectableWords(label,"detail"); a.add(target,label,-2);
  }
  private void draw(LinearLayout main,LinearLayout extra,JSONObject result,Lexicon.Word original) {
    JSONObject e=result.optJSONObject("entry");
    String lemma=e.optString("word",original.ru), stress=e.optString("stress",lemma);
    if(result.has("form")) {
      title(main,"词形来源");
      text(main,result.optString("form")+" ← "+stress,21);
      text(main,result.optString("formLabel"),15);
      if(!result.optString("ipa").isEmpty())text(main,result.optString("ipa"),16);
      a.gap(main,8);
      a.add(main,a.action("听原形 · "+stress,R.drawable.s_audio,false,()->a.voice.play(lemma)),-2);
    } else if(!e.optString("ipa").isEmpty())text(main,e.optString("ipa"),16);
    String grammar=String.join("  ·  ",new String[]{e.optString("pos"),e.optString("aspect"),e.optString("gender"),e.optString("animate")})
        .replaceAll("^(  ·  )+|(  ·  )+$","").replaceAll("(  ·  ){2,}","  ·  ");
    text(main,grammar,15);
    String partner=e.optString("partner");
    if(!partner.isEmpty()) {
      a.gap(main,7);
      a.add(main,a.action("体的对应词 · "+partner,0,false,()->lookup(partner)),-2);
    }
    JSONArray senses=e.optJSONArray("senses");
    if(senses!=null && senses.length()>0) {
      title(main,"详细释义");
      for(int i=0;i<senses.length();i++) {
        text(main,(senses.length()>1?(i+1)+". ":"")+senses.optString(i),17);a.gap(main,10);
      }
    }
    String english=e.optString("english");
    if(!english.isEmpty()) { title(main,"英释参考"); text(main,english,15); }
    JSONArray government=e.optJSONArray("government");
    if(government!=null) {
      title(main,"常用接格");
      for(int i=0;i<government.length();i++) {
        JSONArray line=government.optJSONArray(i);if(line==null)continue;
        text(main,line.optString(0),16);
        example(main,line.optString(1),line.optString(2));
      }
    }
    for(String[] pair:new String[][]{{"collocations","常用搭配"},{"synonyms","近义词"},{"antonyms","反义词"},{"related","相关词"},{"derived","派生词"}}) {
      JSONArray words=e.optJSONArray(pair[0]);if(words==null || words.length()==0)continue;
      title(main,pair[1]);
      for(int i=0;i<words.length();i++) {
        Object item=words.opt(i);
        if(item instanceof JSONArray) { JSONArray row=(JSONArray)item;example(main,row.optString(0),row.optString(1)); }
        else text(main,String.valueOf(item),16);
      }
    }
    if(!e.optString("notes").isEmpty()) { title(main,"用法提醒"); text(main,e.optString("notes"),16); }
    JSONArray forms=e.optJSONArray("forms");
    if(forms!=null && forms.length()>0) {
      title(extra,"词形变化 · "+stress);
      text(extra,"点一行听读音，长按俄文可查词。",13);
      a.gap(extra,9);
      for(int i=0;i<forms.length();i++) {
        JSONArray pair=forms.optJSONArray(i); if(pair==null)continue;
        LinearLayout row=a.box(false);a.pad(row,8,7,7,7);
        if(i%2==0)row.setBackground(a.surface(0x559ac3b2,9,0));
        TextView label=a.txt(pair.optString(0),14,PaperActivity.muted,false);
        row.addView(label,new LinearLayout.LayoutParams(a.dp(a.tablet()?135:110),-2));
        TextView form=a.txt(pair.optString(1),17,PaperActivity.ink,false);
        a.wide(row,form);
        a.selectableWords(form,"detail");
        row.addView(a.iconButton(R.drawable.s_audio,"朗读 "+pair.optString(1),()->a.voice.playExtracted(pair.optString(1))),a.dp(48),a.dp(48));
        a.add(extra,row,-2);
      }
    }
    JSONArray examples=e.optJSONArray("examples");
    if(examples!=null && examples.length()>0) {
      title(extra,"双语例句");
      for(int i=0;i<examples.length();i++) {
        JSONArray line=examples.optJSONArray(i);if(line==null)continue;
        example(extra,line.optString(0),line.optString(1));
      }
    }
    online(extra,lemma);
    a.gap(extra,14);
    text(extra,"释义 · 中文维基词典 / Kaikki，CC BY-SA 4.0\n变化表 · OpenRussian，CC BY-SA 4.0\n例句 · Tatoeba，CC BY 2.0 FR，及词汐编写。\n词条资料覆盖程度不同，未收录内容不会自动编造。",12);
  }

  private void example(LinearLayout target,String ru,String zh) {
    LinearLayout row=a.box(false);a.pad(row,0,4,0,4);
    LinearLayout words=a.box(true);text(words,ru,17);text(words,zh,14);a.wide(row,words);
    row.addView(a.iconButton(R.drawable.s_audio,"朗读 "+ru,()->a.voice.playExtracted(ru)),a.dp(48),a.dp(48));
    a.add(target,row,-2);a.gap(target,7);
  }
  private void online(LinearLayout target,String lemma) {
    title(target,"继续学习");
    a.add(target,a.action("问俄语老师",R.drawable.s_orb,false,()->a.openTutor(lemma)),-2);
    a.gap(target,10);
    a.add(target,a.action("维基词典 · 查看完整词条",0,false,()->browse("https://ru.wiktionary.org/wiki/"+Uri.encode(lemma))),-2);
    a.gap(target,7);
    a.add(target,a.action("OpenRussian · 更多用法",0,false,()->browse("https://en.openrussian.org/ru/"+Uri.encode(lemma))),-2);
  }
  private void browse(String url) {
    try { a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
    catch(android.content.ActivityNotFoundException missing) { a.tip("当前设备没有浏览器"); }
  }
  private void lookup(String term) {
    String clean=term.split("[,; ]")[0].trim();
    a.io.execute(()->{
      try {
        java.util.List<Lexicon.Word> matches=a.lex.search(clean,true);
        Lexicon.Word word=matches.isEmpty()?new Lexicon.Word(Lexicon.key(clean),clean,"","俄语词条"):matches.get(0);
        a.ui.post(()->{ if(!a.isDestroyed())a.open(word,"search"); });
      } catch(IOException problem) { a.ui.post(()->a.tip("这次没有查到")); }
    });
  }
  void close() { if(db!=null) { db.close();db=null; } }
}
