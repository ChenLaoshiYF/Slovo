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
      else if(suite.equals("chat"))chat();
      else data();
      output.putString("stream","PASS "+suite+" · "+checks+" checks\n");finish(-1,output);
    } catch(Throwable error) {
      android.util.Log.e("SlovoChecks","FAIL",error);
      output.putString("stream","FAIL "+error+"\n");finish(0,output);
    }
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
      String reply=LocalLanguageModel.chat(getTargetContext(),prompt,Collections.emptyList(),user);
      android.util.Log.i("SlovoChecks","CHAT "+role+" USER "+user+" REPLY "+reply);
      require(!reply.isEmpty(),"reply "+role);
      require(reply.matches("(?s).*[А-Яа-яЁё]{2,}.*"),"Russian teaching response "+role);
    }
    Object tutor=roleClass.getEnumConstants()[3];String prompt=(String)instruction.invoke(tutor);
    List<String> history=new ArrayList<>();
    for(String user:new String[]{"我不会俄语","Я студент и я учиться русский язык.","先别教俄语了，陪我聊聊天气", "那明天呢？"}) {
      String reply=LocalLanguageModel.chat(getTargetContext(),prompt,history,user);
      android.util.Log.i("SlovoChecks","MULTI USER "+user+" REPLY "+reply);
      require(reply.matches("(?s).*[А-Яа-яЁё]{2,}.*"),"Russian maintained in multi-turn");
      history.add(user);history.add(reply);
    }
    LocalLanguageModel.stop();
  }
}
