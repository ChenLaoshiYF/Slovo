package studio.tingye.reader;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The model ships with the APK. It is prepared and memory-mapped only on the first request. */
final class LocalLanguageModel {
  static final String ENABLED="onDeviceLanguageModel";
  private static final int CHUNKS=8;
  private static final long BYTES=396705472L;
  private static final ExecutorService RELEASE=Executors.newSingleThreadExecutor();
  private static volatile boolean loaded;
  private static boolean available=true;

  static boolean enabled(SharedPreferences prefs) { return prefs.getBoolean(ENABLED,false); }

  private static synchronized boolean loadLibrary() {
    if(!available) return false;
    if(!loaded) {
      try { System.loadLibrary("slovo_inference"); loaded=true; }
      catch(UnsatisfiedLinkError error) {
        available=false;
        android.util.Log.w("Slovo","Local inference library is unavailable",error);
      }
    }
    return loaded;
  }

  static String complete(Context context,String instruction,String text,int tokens) throws IOException {
    return respond(context,turn("system",instruction)+turn("user",text),tokens);
  }

  static final String TRANSLATION="中文译成俄语，俄文译成中文。忠实保留否定、人名、数字、时态和体。只输出译文。 /no_think";

  static String translate(Context context,String text) throws IOException {
    String travel=TranslationAgreement.travel(text);
    if(travel!=null)return travel;
    String result=respond(context,turn("system",TRANSLATION)+turn("user",text),192,true);
    return TranslationAgreement.correct(result);
  }

  static String chat(Context context,String instruction,List<String> history,String message)
      throws IOException {
    if(instruction.equals(TRANSLATION))return translate(context,message);
    StringBuilder prompt=new StringBuilder(turn("system",instruction));
    int first=Math.max(0,history.size()-6);
    for(int i=first;i<history.size();i++) {
      String text=history.get(i);
      int limit=text.offsetByCodePoints(0,Math.min(180,text.codePointCount(0,text.length())));
      prompt.append(turn(i%2==0?"user":"assistant",text.substring(0,limit)));
    }
    String current=turn("user",message);
    try {
      return respond(context,prompt.append(current).toString(),160);
    } catch(IOException error) {
      if(first==history.size() || Thread.currentThread().isInterrupted()
          || !"端侧模型暂未生成文字".equals(error.getMessage()))throw error;
      return respond(context,turn("system",instruction)+current,160);
    }
  }

  private static String turn(String role,String text) {
    return "<|im_start|>"+role+"\n"+text.replace("<|","‹|")+"<|im_end|>\n";
  }

  private static String respond(Context context,String prompt,int tokens) throws IOException {
    return respond(context,prompt,tokens,false);
  }

  private static synchronized File adapter(Context context) throws IOException {
    File target=new File(context.getNoBackupFilesDir(),"ru-zh-v2.gguf");
    if(target.isFile())return target;
    File tmp=new File(target.getPath()+".part");
    try(InputStream in=context.getAssets().open("ai/ru-zh-v2.gguf");
        FileOutputStream out=new FileOutputStream(tmp)) {
      byte[] buf=new byte[65536];int n;
      while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    if(!tmp.renameTo(target))throw new IOException("无法准备翻译模型");
    return target;
  }

  private static String respond(Context context,String prompt,int tokens,boolean translate) throws IOException {
    if(!loadLibrary()) throw new IOException("模型不支持此系统架构");
    File model=prepare(context);
    if(Thread.currentThread().isInterrupted()) throw new IOException("已取消");
    prompt += "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    int threads=Math.max(1,Math.min(3,Runtime.getRuntime().availableProcessors()-1));
    byte[] result=infer(model.getPath().getBytes(StandardCharsets.UTF_8),
        prompt.getBytes(StandardCharsets.UTF_8),threads,tokens,translate?adapter(context).getPath().getBytes(StandardCharsets.UTF_8):null);
    if(result==null) throw new IOException("端侧模型暂未生成文字");
    return new String(result,StandardCharsets.UTF_8).replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?i)\\s*/(?:no(?:_think)?|think)\\s*$","").trim();
  }

  private static synchronized File prepare(Context context) throws IOException {
    File model=new File(context.getNoBackupFilesDir(),"qwen3-0.6b-q4km-v1.gguf");
    if(model.isFile() && model.length()==BYTES)return model;
    File partial=new File(model.getPath()+".part");
    try(FileOutputStream output=new FileOutputStream(partial)) {
      byte[] buffer=new byte[256*1024];
      for(int part=0;part<CHUNKS;part++) {
        try(InputStream input=context.getAssets().open("ai/qwen-q4.part"+part)) {
          for(int n;(n=input.read(buffer))!=-1;) {
            if(Thread.currentThread().isInterrupted()) throw new IOException("已取消");
            output.write(buffer,0,n);
          }
        }
      }
    } catch(IOException error) {
      partial.delete();
      throw error;
    }
    if(partial.length()!=BYTES || !partial.renameTo(model)) {
      partial.delete();
      throw new IOException("无法准备端侧模型文件");
    }
    new File(context.getNoBackupFilesDir(),"qwen-0.5b-q4-v1.gguf").delete();
    return model;
  }

  static void cancel() {
    if(loaded) interrupt();
  }

  static void stop() {
    if(!loaded)return;
    interrupt();
    RELEASE.execute(LocalLanguageModel::release);
  }

  private static native byte[] infer(byte[] filename,byte[] prompt,int threads,int maxTokens,byte[] adapter);
  private static native void interrupt();
  private static native void release();
}
