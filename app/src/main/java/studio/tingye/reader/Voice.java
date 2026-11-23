package studio.tingye.reader;

import android.app.*;
import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.util.*;
import java.util.concurrent.Executor;

final class Voice {
  final Activity a;
  final Lexicon lex;
  final Executor lookup;
  MediaPlayer mp;



  private float rate = 1f;
  private static final Locale RUSSIAN=new Locale("ru","RU");
  private Locale language=RUSSIAN;

  final OfflineSpeech offline;

  Voice(Activity a, Lexicon lex,Executor lookup) {
    this.a = a;
    this.lex = lex;
    this.lookup=lookup;
    this.offline=new OfflineSpeech(a);
  }

  void stop() {
    offline.stop();

    if (mp != null) {
      mp.release();
      mp = null;
    }

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

  void playExtracted(String reported) {
    stop();
    rate=1f;
    String text=reported.trim();
    language=text.matches("(?s).*[А-Яа-яЁё].*")?RUSSIAN:
        text.matches("(?s).*[\\p{IsHan}].*")?Locale.CHINESE:Locale.US;
    if(text.isEmpty() || playRecording(text,1f))return;
    synthesize(text);
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
    String locale=word.matches("(?s).*[А-Яа-яЁё].*")?"ru":language.getLanguage();
    offline.speak(word,rate,locale,()->android.widget.Toast.makeText(a,
        "这次朗读未完成，请再点一次",android.widget.Toast.LENGTH_SHORT).show());
  }

  void close() {
    stop();
    offline.close();
  }
}
