package studio.tingye.reader;

import android.content.Context;
import android.media.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** A lazy, app-owned voice. Neither Android TTS nor a network service is needed. */
final class OfflineSpeech {
  private final Context context;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final AtomicInteger generation=new AtomicInteger();
  private OfflineTts engine;
  private volatile AudioTrack track;
  private volatile boolean closed;
  OfflineSpeech(Context c) { context=c.getApplicationContext(); }

  void stop() {
    generation.incrementAndGet();
    AudioTrack playing=track;
    if(playing!=null)try { playing.stop(); playing.flush(); } catch(IllegalStateException ignored) {}
  }

  void speak(String text,float speed,Runnable error) {
    speak(text,speed,"ru",error);
  }

  void speak(String text,float speed,String language,Runnable error) {
    stop();
    final int request=generation.get();
    worker.execute(()->{
      if(closed || request!=generation.get())return;
      try {
        File folder=prepareAssets();
        OfflineTts voice=language.equals("ru")?prepare():null;
        // Bound one request without silently changing an inflected word into its lemma.
        String clean=text.trim().replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        if(clean.length()>1200)clean=clean.substring(0,1200);
        for(String sentence:clean.split("(?<=[.!?。！？])\\s+|\\n+")) {
          if(sentence.trim().isEmpty())continue;
          if(closed || request!=generation.get())return;
          long started=android.os.SystemClock.elapsedRealtime();
          GeneratedAudio audio=voice==null?null:voice.generate(sentence,0,1f);
          if(closed || request!=generation.get())return;
          float[] pcm=audio==null?ClassicSpeech.synthesize(folder,sentence,language.equals("zh")?"cmn":"en-us"):audio.getSamples();
          int sampleRate=audio==null?22050:audio.getSampleRate();
          float peak=0; for(float value:pcm)peak=Math.max(peak,Math.abs(value));
          if(pcm.length==0 || peak<.00001f)throw new IOException("Empty speech");
          android.util.Log.i("SlovoSpeech","PCM language="+language+" samples="+pcm.length+" rate="+sampleRate
              +" peak="+peak+" synthesisMs="+(android.os.SystemClock.elapsedRealtime()-started));
          play(pcm,sampleRate,request,speed);
        }
      } catch(Exception | LinkageError problem) {
        android.util.Log.e("SlovoSpeech","Offline speech failed",problem);
        if(!closed && request==generation.get())new android.os.Handler(android.os.Looper.getMainLooper()).post(error);
      }
    });
  }

  private void play(float[] pcm,int rate,int request,float speed) {
    int minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);
    AudioTrack out=new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
        .setBufferSizeInBytes(Math.max(minimum,rate/5*4)).setTransferMode(AudioTrack.MODE_STREAM).build();
    track=out;
    try {
      if(request!=generation.get())return;
      out.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));
      out.play();
      int written=0;
      while(written<pcm.length && request==generation.get() && !closed) {
        int n=out.write(pcm,written,Math.min(2048,pcm.length-written),AudioTrack.WRITE_BLOCKING);
        if(n<0)throw new IllegalStateException("AudioTrack write "+n);
        written+=n;
      }
      long deadline=android.os.SystemClock.elapsedRealtime()+2000L+(long)(pcm.length*1000.0/rate/speed);
      while(request==generation.get() && !closed && out.getPlaybackHeadPosition()<written
          && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(15);
    } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
    finally { if(track==out)track=null; out.release(); }
  }

  private File prepareAssets() throws IOException {
    File folder=new File(context.getNoBackupFilesDir(),"speech-denis-v1");
    File marker=new File(folder,"ready");
    if(!marker.isFile()) {
      if(!folder.isDirectory() && !folder.mkdirs())throw new IOException("Speech storage");
      try(ZipInputStream zip=new ZipInputStream(context.getAssets().open("speech-denis.bin"))) {
        for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
          File destination=new File(folder,entry.getName());
          if(!destination.getCanonicalPath().startsWith(folder.getCanonicalPath()+File.separator))
            throw new IOException("Invalid speech asset");
          if(entry.isDirectory()) { destination.mkdirs(); continue; }
          File parent=destination.getParentFile();
          if(!parent.isDirectory() && !parent.mkdirs())throw new IOException("Speech directory");
          try(OutputStream output=new FileOutputStream(destination)) {
            byte[] buffer=new byte[65536]; for(int n;(n=zip.read(buffer))!=-1;)output.write(buffer,0,n);
          }
        }
      }
      if(!marker.createNewFile())throw new IOException("Speech marker");
    }
    return folder;
  }

  private OfflineTts prepare() throws IOException {
    if(engine!=null)return engine;
    File folder=prepareAssets();
    OfflineTtsVitsModelConfig vits=new OfflineTtsVitsModelConfig();
    vits.setModel(new File(folder,"ru_RU-denis-medium.onnx").getPath());
    vits.setTokens(new File(folder,"tokens.txt").getPath());
    vits.setDataDir(new File(folder,"espeak-ng-data").getPath());
    OfflineTtsModelConfig model=new OfflineTtsModelConfig();
    model.setVits(vits); model.setNumThreads(Math.max(1,Math.min(2,Runtime.getRuntime().availableProcessors())));
    OfflineTtsConfig config=new OfflineTtsConfig(); config.setModel(model); config.setMaxNumSentences(1);
    engine=new OfflineTts(null,config);
    return engine;
  }

  void close() {
    closed=true; stop();
    worker.execute(()->{ if(engine!=null) { engine.release(); engine=null; } });
    worker.shutdown();
  }
}
