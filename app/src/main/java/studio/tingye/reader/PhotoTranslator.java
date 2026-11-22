package studio.tingye.reader;

import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Resolves local matches first. The user can request full translations for the remaining lines. */
final class PhotoTranslator {
  static final String OPTIONS="翻译选项";
  private final WordActivity a;
  private final ExecutorService offline=Executors.newSingleThreadExecutor();
  private final ExecutorService network=Executors.newFixedThreadPool(3);
  private final LruCache<String,String> translated=new LruCache<>(128);
  private final LinkedHashSet<String> active=new LinkedHashSet<>();
  private final LinkedHashMap<String,String> remaining=new LinkedHashMap<>();
  private volatile int generation;
  private boolean assessed, busy, closed;
  private int requests;
  private BiConsumer<String,String> show=(source,value)->{};
  private Consumer<String> status=value->{};

  PhotoTranslator(WordActivity activity) { a=activity; }

  void observe(Collection<String> lines,BiConsumer<String,String> receiver,Consumer<String> displayStatus) {
    show=receiver;
    status=displayStatus;
    LinkedHashSet<String> next=new LinkedHashSet<>(lines);
    if(active.equals(next)) {
      if(assessed) publish();
      if(busy) status.accept("正在翻译…");
      return;
    }
    active.clear();
    active.addAll(next);
    remaining.clear();
    assessed=false;
    busy=false;
    int job=++generation;
    if(next.isEmpty()) {
      status.accept("");
      return;
    }
    offline.execute(()->{
      Map<String,String> hints=new LinkedHashMap<>();
      for(String line:next) {
        if(job!=generation)return;
        String exact=translated.get(line);
        if(exact==null) {
          try { exact=a.translations.find(line); }
          catch(Exception ignored) { exact=""; }
        }
        if(!exact.isEmpty()) {
          translated.put(line,exact);
          continue;
        }
        String gloss="";
        try { gloss=a.translations.hints(line); }
        catch(Exception ignored) {}
        hints.put(line,gloss.isEmpty()?"这句可用模型翻译":"词义参考 "+gloss);
      }
      a.ui.post(()->{
        if(closed||job!=generation)return;
        remaining.putAll(hints);
        assessed=true;
        publish();
      });
    });
  }

  private void publish() {
    for(String line:active) {
      String exact=translated.get(line);
      if(exact!=null)show.accept(line,exact);
      else if(remaining.containsKey(line))show.accept(line,remaining.get(line));
    }
    if(!busy)status.accept(remaining.isEmpty()?"":OPTIONS);
  }

  boolean canTranslate() { return assessed&&!busy&&!remaining.isEmpty(); }

  void translateOnPhone() {
    if(!canTranslate())return;
    int job=++generation;
    ArrayList<String> lines=new ArrayList<>(remaining.keySet());
    int count=Math.min(lines.size(),3);
    busy=true;
    status.accept("正在用手机翻译 "+count+" 句…");
    offline.execute(()->{
      for(int i=0;i<count && job==generation;i++) {
        String source=lines.get(i),value="";
        try { value=a.translations.infer(source); }
        catch(Exception ignored) {}
        String result=value;
        a.ui.post(()->completeLine(job,source,result,"手机 AI"));
      }
      a.ui.post(()->finish(job));
    });
  }

  void translateOnline() {
    if(!canTranslate())return;
    ArrayList<String> lines=new ArrayList<>(remaining.keySet());
    int job=++generation;
    busy=true;
    requests=Math.min(lines.size(),3);
    status.accept("正在联网翻译 "+requests+" 句…");
    for(int i=0;i<requests;i++) {
      String source=lines.get(i);
      network.execute(()->{
        if(job!=generation)return;
        String value=online(source);
        a.ui.post(()->{
          if(closed||job!=generation)return;
          completeLine(job,source,value,"MyMemory");
          if(--requests==0)finish(job);
        });
      });
    }
  }

  private void completeLine(int job,String source,String result,String provider) {
    if(closed||job!=generation||result.isEmpty())return;
    String value=result+"  ·  "+provider;
    translated.put(source,value);
    remaining.remove(source);
    show.accept(source,value);
  }

  private void finish(int job) {
    if(closed||job!=generation)return;
    busy=false;
    publish();
  }

  private static String online(String source) {
    if(source.getBytes(StandardCharsets.UTF_8).length>500)return "";
    HttpsURLConnection connection=null;
    try {
      String query=URLEncoder.encode(source,"UTF-8");
      connection=(HttpsURLConnection)new URL(
          "https://api.mymemory.translated.net/get?langpair=ru%7Czh-CN&q="+query).openConnection();
      connection.setConnectTimeout(2200);
      connection.setReadTimeout(3200);
      connection.setInstanceFollowRedirects(false);
      if(connection.getResponseCode()!=200)return "";
      try(InputStream stream=connection.getInputStream()) {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];
        for(int n;(n=stream.read(buffer))!=-1;) {
          if(bytes.size()+n>40_000)return "";
          bytes.write(buffer,0,n);
        }
        JSONObject response=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        if(response.optInt("responseStatus")!=200)return "";
        String result=response.getJSONObject("responseData").optString("translatedText").trim();
        return result.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)?result:"";
      }
    } catch(Exception ignored) { return ""; }
    finally { if(connection!=null)connection.disconnect(); }
  }

  void close() {
    closed=true;
    generation++;
    offline.shutdownNow();
    network.shutdownNow();
  }
}

