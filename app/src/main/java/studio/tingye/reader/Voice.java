package studio.tingye.reader;

import android.app.*;
import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import java.util.*;
import java.util.concurrent.Executor;

final class Voice {
  final Activity a;
  final Lexicon lex;
  final Executor lookup;
  MediaPlayer mp;
  TextToSpeech system;
  boolean ready, alternate;
  int generation;
  private int playback;
  private float rate = 1f;
  private static final Locale RUSSIAN=new Locale("ru","RU");
  private Locale language=RUSSIAN;
  String waiting;

  Voice(Activity a, Lexicon lex,Executor lookup) {
    this.a = a;
    this.lex = lex;
    this.lookup=lookup;
  }

  void stop() {
    playback++;
    waiting = null;
    if (mp != null) {
      mp.release();
      mp = null;
    }
    if (system != null && ready) system.stop();
  }

  void play(String word) {
    play(word,1f);
  }

  void playSlow(String word) { play(word,.72f); }

  private void play(String word,float speed) {
    stop();
    rate = speed;
    language=RUSSIAN;
    if(!playRecording(word,speed))synthesize(word);
  }

  private boolean hasRecording(String key) {
    return lex.supplementalAudio.containsKey(key) || lex.core.containsKey(key);
  }

  void playExtracted(String reported) {
    stop();
    rate=1f;
    language=RUSSIAN;
    String text=reported.trim();
    if(text.isEmpty() || playRecording(text,1f))return;
    if(!text.matches("[А-Яа-яЁё́-]+")) {
      synthesize(text);
      return;
    }
    int request=playback;
    lookup.execute(()->{
      String base="";
      try {
        for (Lexicon.Word word:lex.search(text,true)) {
          if(hasRecording(word.k)) { base=word.ru; break; }
        }
      } catch(java.io.IOException error) {
        android.util.Log.w("Slovo","Pronunciation lookup failed",error);
      }
      String recorded=base;
      a.runOnUiThread(()->{
        if (request!=playback || a.isDestroyed())return;
        if(recorded.isEmpty() || !playRecording(recorded,1f))synthesize(text);
        else if(!Lexicon.key(text).equals(Lexicon.key(recorded)))
          android.widget.Toast.makeText(a,"正在播放原形 · "+recorded,android.widget.Toast.LENGTH_SHORT).show();
      });
    });
  }

  void playInLanguage(String text,Locale locale) {
    stop();
    rate=1f;
    language=locale;
    if(!text.trim().isEmpty())synthesize(text);
  }

  private boolean playRecording(String word,float speed) {
    String key = Lexicon.key(word);
    Lexicon.AudioRange range = lex.supplementalAudio.get(key);
    if (range != null || lex.core.containsKey(key)) {
      String file = range == null ? "voices/" + key + ".mp3" : "voices-supplements.bin";
      try (AssetFileDescriptor fd = a.getAssets().openFd(file)) {
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        long start = fd.getStartOffset() + (range == null ? 0 : range.start);
        long bytes = range == null ? fd.getLength() : range.bytes;
        mp.setDataSource(fd.getFileDescriptor(), start, bytes);
        mp.setOnPreparedListener(p -> {
          if (p != mp) return;
          if (speed != 1f) {
            try { p.setPlaybackParams(p.getPlaybackParams().setSpeed(speed).setPitch(1f)); }
            catch (IllegalArgumentException | IllegalStateException ignored) {}
          }
          p.start();
        });
        mp.setOnCompletionListener(
            p -> {
              if (mp == p) {
                mp = null;
                p.release();
              }
            });
        mp.setOnErrorListener((p,what,extra)->{
          if(mp==p) {
            mp=null;
            p.release();
            synthesize(word);
          }
          return true;
        });
        mp.prepareAsync();
        return true;
      } catch (Exception ignored) {
        stop();
      }
    }
    return false;
  }

  private void synthesize(String word) {
    waiting = word;
    if (system != null && ready) {
      speak();
      return;
    }
    if (system == null) init(false);
    else if (!ready)
      android.widget.Toast.makeText(a, "正在准备系统俄语声音", android.widget.Toast.LENGTH_SHORT).show();
  }

  void init(boolean useAlternate) {
    alternate = useAlternate;
    ready = false;
    final int request = ++generation;
    TextToSpeech.OnInitListener listener =
        status ->
            a.getWindow()
                .getDecorView()
                .post(
                    () -> {
                      if (a.isDestroyed() || request != generation) return;
                      ready = status == TextToSpeech.SUCCESS;
                      if (ready) speak();
                      else fallback();
                    });
    system =
        useAlternate
            ? new TextToSpeech(a, listener, "com.reecedunn.espeak")
            : new TextToSpeech(a, listener);
  }

  void fallback() {
    if (!alternate) {
      Intent intent =
          new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
              .setPackage("com.reecedunn.espeak");
      if (!a.getPackageManager().queryIntentServices(intent, 0).isEmpty()) {
        if (system != null) system.shutdown();
        init(true);
        return;
      }
    }
    if (system != null) {
      system.shutdown();
      system = null;
    }
    ready = false;
    missing();
  }

  void speak() {
    if (waiting == null) return;
    if (system.setLanguage(language) < 0) {
      fallback();
      return;
    }
    system.setSpeechRate(.87f * rate);
    if (system.speak(waiting, TextToSpeech.QUEUE_FLUSH, null, "slovo-word") == TextToSpeech.ERROR) {
      fallback();
      return;
    }
    waiting = null;
  }

  void missing() {
    if (waiting == null) return;
    waiting = null;
    if (a.isFinishing()) return;
    String label=language.equals(RUSSIAN)?"俄语":language.equals(Locale.US)?"英语":"中文";
    new FeedbackDialog(a)
        .setTitle("缺少"+label+"语音")
        .setMessage("在安卓文字转语音设置中安装"+label+"声音，再回来试听。")
        .setNegativeButton("返回", null)
        .setPositiveButton(
            "语音设置",
            (d, w) -> {
              try {
                Intent install =
                    new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        .setPackage("com.reecedunn.espeak");
                if (install.resolveActivity(a.getPackageManager()) != null) {
                  a.startActivity(install);
                  return;
                }
                a.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
              } catch (Exception e) {
                android.widget.Toast.makeText(
                        a, "请在系统设置中搜索“文字转语音”", android.widget.Toast.LENGTH_LONG)
                    .show();
              }
            })
        .show();
  }

  void close() {
    generation++;
    stop();
    if (system != null) system.shutdown();
  }
}
