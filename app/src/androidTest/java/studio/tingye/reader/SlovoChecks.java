package studio.tingye.reader;

import android.app.Instrumentation;
import android.os.Bundle;
import com.k2fsa.sherpa.onnx.*;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;

/** Device-level regression checks, excluded from distributed APKs. */
public final class SlovoChecks extends Instrumentation {
  private Bundle arguments;
  private int checks;
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);this.arguments=arguments;start(); }
  private void require(boolean value,String label) {
    if(!value)throw new AssertionError(label);
    checks++;android.util.Log.i("SlovoChecks","PASS "+label);
  }
  @Override public void onStart() {
    Bundle output=new Bundle();
    try {
      String suite=arguments.getString("suite","data");
      if(suite.equals("speech"))speech();
      else if(suite.equals("installer"))installer();
      else if(suite.equals("interface")){new InterfaceAudit(this).run();require(true,"layout and role conversations");}
      else if(suite.equals("evidence"))evidence();
      else if(suite.equals("adapt"))adapt();
      else if(suite.equals("chat"))chat();
      else data();
      output.putString("stream","PASS "+suite+" · "+checks+" checks\n");finish(-1,output);
    } catch(Throwable error) {
      android.util.Log.e("SlovoChecks","FAIL",error);
      output.putString("stream","FAIL "+error+"\n");finish(0,output);
    }
  }
  private void evidence() throws Exception {
    Lexicon lex=new Lexicon(getTargetContext());
    try {
      Set<String> keys=new HashSet<>();
      for(Lexicon.Book book:lex.books)keys.addAll(book.words);
      for(String key:keys){
        JSONObject row=lex.reviewEvidence.getJSONObject(key);
        String ipa=row.getString("ipa");JSONArray pair=row.getJSONArray("line");
        if(!(ipa.startsWith("[") && ipa.endsWith("]") || ipa.startsWith("/") && ipa.endsWith("/"))
            || ipa.matches("(?s).*[А-Яа-яЁё].*") || !pair.getString(0).matches("(?s).*[А-Яа-яЁё].*")
            || !pair.getString(1).matches("(?s).*\\p{IsHan}.*"))
          throw new AssertionError("Incomplete card "+key);
      }
      require(keys.size()==7219,"all 7219 distinct study words include IPA and bilingual sentences");
      require(lex.publishedWord("вс").ru.equals("всё"),"legacy key displays corrected headword");
      require(lex.reviewEvidence.getJSONObject("город").getString("ipa").contains("ˈ"),"stress in city IPA");
    }finally{lex.close();}
  }

  private void adapt() throws Exception {
    for(String input:new String[]{"Я не работаю в пятницу.","我需要两张票。","Мы купили три книги."}) {
      String reply=LocalLanguageModel.translate(getTargetContext(),input);
      android.util.Log.i("SlovoChecks","TRANSLATION "+input+" => "+reply);
      if(input.contains("пятницу"))require((reply.contains("五") && (reply.contains("不") || reply.contains("没"))),"weekday and negation preserved");
      else if(input.contains("票"))require(reply.contains("два билета"),"number and gender agreement");
      else require(reply.contains("三") && (reply.contains("书") || reply.contains("書")),"book count preserved");
    }
    Lexicon lex=new Lexicon(getTargetContext());
    StudyStore store=new StudyStore(getTargetContext(),lex);
    android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
    db.beginTransaction();
    try {
      store.clearChat();
      Lexicon.Book book=lex.books.get(0);
      String word=book.words.get(book.words.size()-1);
      store.recordChat("POLISH",Collections.singleton(word));
      store.recordChat("TRANSLATE",Collections.singleton(word));
      require(Arrays.equals(store.chatHabits(),new int[]{2,2,1}),"portrait reflects multi-turn practice");
      require(store.interests().get(word)>1.9,"repeat exposure increases recommendation weight");
      store.plan=book.k;store.goal=100;store.session=new StudyStore.Session();store.start();
      require(store.session.queue.contains(word),"conversation vocabulary enters new-word queue");
      require(Collections.frequency(store.session.queue,word)==1,"no duplicated recommendation");
      store.clearChat();require(store.interests().isEmpty(),"forget conversation dataset");
    } finally { db.endTransaction();store.close();lex.close(); }
  }

  private void speech() throws Exception {
    OfflineSpeech wrapper=new OfflineSpeech(getTargetContext());
    Method prepare=OfflineSpeech.class.getDeclaredMethod("prepare");prepare.setAccessible(true);
    OfflineTts engine=(OfflineTts)prepare.invoke(wrapper);
    for(String text:new String[]{"занимает","занимающимися","электромагнитный","Здравствуйте! Я учусь в университете.",
        "Этот стол занимает много места.","Вчера мы гуляли по набережной."}) {
      long start=android.os.SystemClock.elapsedRealtime();
      GeneratedAudio audio=engine.generate(text,0,1f);
      float peak=0;double energy=0;
      for(float value:audio.getSamples()) { peak=Math.max(peak,Math.abs(value));energy+=value*value; }
      require(audio.getSampleRate()==22050,"sample rate "+text);
      require(audio.getSamples().length>4000 && peak>.01 && energy>1,"non-silent PCM "+text);
      android.util.Log.i("SlovoChecks","SPEECH "+text+" samples="+audio.getSamples().length+" ms="+(android.os.SystemClock.elapsedRealtime()-start));
    }
    GeneratedAudio sample=engine.generate("Этот стол занимает много места.",0,1f);
    Method play=OfflineSpeech.class.getDeclaredMethod("play",float[].class,int.class,int.class,float.class);play.setAccessible(true);
    long start=android.os.SystemClock.elapsedRealtime();
    play.invoke(wrapper,sample.getSamples(),sample.getSampleRate(),0,1f);
    long normal=android.os.SystemClock.elapsedRealtime()-start;
    start=android.os.SystemClock.elapsedRealtime();
    play.invoke(wrapper,sample.getSamples(),sample.getSampleRate(),0,.72f);
    long slow=android.os.SystemClock.elapsedRealtime()-start;
    android.util.Log.i("SlovoChecks","PLAYBACK normalMs="+normal+" slowMs="+slow);
    require(slow>normal*1.12,"slow playback is audibly longer using the same PCM");
    Method assets=OfflineSpeech.class.getDeclaredMethod("prepareAssets");assets.setAccessible(true);
    java.io.File folder=(java.io.File)assets.invoke(wrapper);
    for(String[] input:new String[][]{{"Hello world. Learn Russian every day.","en-us"},{"你好，我正在学俄语。","cmn"}}) {
      float[] pcm=ClassicSpeech.synthesize(folder,input[0],input[1]);
      float peak=0;double energy=0;
      for(float value:pcm){peak=Math.max(peak,Math.abs(value));energy+=value*value;}
      require(pcm.length>8000 && peak>.01 && energy>1,"bundled auxiliary voice "+input[1]);
      play.invoke(wrapper,pcm,22050,0,1f);
    }
    wrapper.close();
  }
  private void data() throws Exception {
    // The repository needs an attached Activity context; construct via its owning activity.
    final WordActivity[] owner=new WordActivity[1];
    android.content.Intent launch=new android.content.Intent(getTargetContext(),WordActivity.class)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    owner[0]=(WordActivity)startActivitySync(launch);
    DictionaryDetails details=new DictionaryDetails(owner[0]);
    JSONObject form=details.resolve("занимает");JSONObject root=form.getJSONObject("entry");
    require(root.getString("word").equals("занимать"),"inflection resolves to exact lemma");
    require(form.getString("form").equals("занима́ет"),"stress on inflected form");
    require(root.getJSONArray("senses").length()>=5,"complete meanings");
    require(root.getJSONArray("government").length()>=5,"case government");
    require(root.getJSONArray("forms").length()>=12,"verb conjugation");
    require(root.getJSONArray("examples").length()>=8,"bilingual examples");
    for(String word:new String[]{"книга","красивый","читать","учиться"}) {
      JSONObject entry=details.resolve(word).getJSONObject("entry");
      require(entry.getJSONArray("forms").length()>=6,"paradigm "+word);
    }
    details.close();
    require(TutorGuide.answer("CAFE","我想要一杯不加糖的咖啡").contains("кофе без сахара"),"reviewed cafe expression");
    require(TutorGuide.answer("STATION","我想明天去莫斯科").contains("до Москвы на завтра"),"reviewed station expression");
    require(!TutorGuide.answer("STATION","去莫斯科").contains("завтра"),"no invented travel date");
    require(TutorGuide.answer("TUTOR","Я студент и я учиться русский язык.").contains("я учу русский язык"),"reviewed beginner grammar correction");
    JSONObject valid=new JSONObject().put("url","https://github.com/ChenLaoshiYF/Slovo/releases/download/v0.9.0/Slovo-ARM64.apk")
        .put("sha256",String.join("",Collections.nCopies(64,"a"))).put("size",555000000);
    AppUpdates.validateAsset(valid);require(true,"official release asset accepted");
    for(String bad:new String[]{"http://github.com/ChenLaoshiYF/Slovo/releases/download/v1/a.apk",
        "https://evil.example/a.apk","https://github.com/Other/repo/releases/download/v1/a.apk"}) {
      valid.put("url",bad);boolean rejected=false;
      try {AppUpdates.validateAsset(valid);}catch(Exception expected){rejected=true;}
      require(rejected,"invalid update host rejected");
    }
  }
  private void chat() throws Exception {
    Class<?> roleClass=Class.forName("studio.tingye.reader.RoleChat$Role");
    Method instruction=roleClass.getDeclaredMethod("instruction");instruction.setAccessible(true);
    for(Object role:roleClass.getEnumConstants()) {
      String prompt=(String)instruction.invoke(role);
      String user=role.toString().equals("CAFE")?"我想要一杯不加糖的咖啡":
          role.toString().equals("STATION")?"我想明天去莫斯科":role.toString().equals("STUDENT")?"我学电子科学，我不太会俄语":"我不知道怎么用俄语介绍自己";
      switch(role.toString()){
        case "POLISH":user="Я учиться в университете.";break;
        case "COMPOSE":user="给老师写一封俄文邮件，请问周五能否见面。";break;
        case "EXPAND":user="Осенью мы поехали на Байкал.";break;
        case "TRANSLATE":user="我想去莫斯科。";break;
      }
      String reply=WritingPractice.answer(getTargetContext(),role.toString(),prompt,Collections.emptyList(),user);
      android.util.Log.i("SlovoChecks","CHAT "+role+" USER "+user+" REPLY "+reply);
      if(role.toString().equals("COMPOSE"))require(reply.contains("пятницу") && reply.contains("уважением"),"email addresses the requested day and register");
      if(role.toString().equals("EXPAND"))require(reply.length()>user.length()+12,"expansion adds material");
      require(!reply.isEmpty(),"reply "+role);
      require(reply.matches("(?s).*[А-Яа-яЁё]{2,}.*"),"Russian teaching response "+role);
    }
    Object tutor=Enum.valueOf((Class)roleClass,"TUTOR");String prompt=(String)instruction.invoke(tutor);
    List<String> history=new ArrayList<>();
    for(String user:new String[]{"我不会俄语","Я студент и я учиться русский язык.","先别教俄语了，陪我聊聊天气", "那明天呢？"}) {
      String reply=LocalLanguageModel.chat(getTargetContext(),prompt,history,user);
      android.util.Log.i("SlovoChecks","MULTI USER "+user+" REPLY "+reply);
      require(reply.matches("(?s).*[А-Яа-яЁё]{2,}.*"),"Russian maintained in multi-turn");
      history.add(user);history.add(reply);
    }
    LocalLanguageModel.stop();
  }

  /** Run on a versionCode 10 QA build after copying the published APK into its cache. */
  private void installer() throws Exception {
    android.content.Context context=getTargetContext();
    java.io.File file=UpdateFile.file(context);
    java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
    try(java.io.InputStream in=new java.io.FileInputStream(file)) {
      byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);
    }
    StringBuilder sha=new StringBuilder();
    for(byte b:digest.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
    require(sha.toString().equals("4492bbc7ad53bdc78158c24f32161b1550377b798dd70b45d65cf6e6b0affeb0"),"published complete APK checksum");
    AppUpdates.verify(context,file,11);require(true,"published APK package, version and installed certificate");
    require(context.getPackageManager().canRequestPackageInstalls(),"installer permission granted in Android settings");
    WordActivity owner=(WordActivity)startActivitySync(new android.content.Intent(context,WordActivity.class)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    java.lang.reflect.Field field=WordActivity.class.getDeclaredField("updates");field.setAccessible(true);
    AppUpdates updates=(AppUpdates)field.get(owner);
    java.lang.reflect.Field release=AppUpdates.class.getDeclaredField("release");release.setAccessible(true);
    release.set(updates,new JSONObject().put("versionCode",11));
    Method install=AppUpdates.class.getDeclaredMethod("install");install.setAccessible(true);
    runOnMainSync(()->{try {install.invoke(updates);}catch(Exception e){throw new RuntimeException(e);}});
    require(true,"app-owned content URI passed to the Android installer");
    // Keep the test's launch task alive while the tester confirms installation.
    // Replacing the target package will end instrumentation, as expected.
    Thread.sleep(120000);
  }
}
