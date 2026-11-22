package studio.tingye.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public class ReaderActivity extends Activity {
  final String origin = "https://reader.invalid";
  WebView web;
  SceneryPane scenery;
  MediaPlayer player;
  final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
  File library;
  long playbackToken;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    library = new File(getFilesDir(), "lessons");
    library.mkdirs();
    getWindow().setStatusBarColor(0xfff5f3ec);
    getWindow().setNavigationBarColor(0xfff5f3ec);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    web = new WebView(this);
    FrameLayout layers = new FrameLayout(this);
    scenery = new SceneryPane(this);
    layers.addView(scenery,new FrameLayout.LayoutParams(-1,-1));
    View headerArt=new View(this);
    int headerHeight=Math.round(60*getResources().getDisplayMetrics().density);
    layers.addView(headerArt,new FrameLayout.LayoutParams(-1,headerHeight));
    scenery.bindGlass(headerArt,SceneryPane.HEADER);
    web.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    layers.addView(web,new FrameLayout.LayoutParams(-1,-1));
    setContentView(layers);
    chooseScenery("home");
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(false);
    web.addJavascriptInterface(new Bridge(), "Reader");
    web.setWebViewClient(
        new WebViewClient() {
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return true;
          }

          public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri uri = r.getUrl();
            String path = uri.getPath();
            try {
              if (!"reader.invalid".equals(uri.getHost()) || path == null || path.contains(".."))
                throw new IOException();
              String type =
                  path.endsWith(".js")
                      ? "application/javascript"
                      : path.endsWith(".css")
                          ? "text/css"
                          : path.endsWith(".json")
                              ? "application/json"
                              : path.endsWith(".webp")
                                  ? "image/webp"
                                  : path.endsWith(".png") ? "image/png" : "text/html";
              InputStream in;
              if (path.startsWith("/books/")) {
                File f = new File(library, path.substring(7)).getCanonicalFile();
                if (!f.toPath().startsWith(library.getCanonicalFile().toPath()))
                  throw new IOException();
                in = new FileInputStream(f);
              } else if ("/scenery/park.webp".equals(path))
                in = getResources().openRawResource(R.raw.land_park);
              else if ("/scenery/mist.webp".equals(path))
                in = getResources().openRawResource(R.raw.mist_park);
              else in = getAssets().open(path.substring(1));
              return new WebResourceResponse(type, type.startsWith("image") ? null : "UTF-8", in);
            } catch (Exception ex) {
              android.util.Log.w("TingyeReader", "Local asset unavailable: " + path, ex);
              return new WebResourceResponse(
                  "text/plain",
                  "UTF-8",
                  404,
                  "Missing",
                  Collections.emptyMap(),
                  new ByteArrayInputStream(new byte[0]));
            }
          }
        });
    web.loadUrl(origin + "/classroom.html");
  }

  void notice(String message) {
    if (!isFinishing() && !isDestroyed())
      new FeedbackDialog(this).setMessage(message).setPositiveButton("关闭", null).show();
  }

  private void chooseScenery(String page) {
    int id = "lesson".equals(page) ? 4 : "review".equals(page) ? 2 : "terms".equals(page) ? 5 : 3;
    android.content.SharedPreferences p = getSharedPreferences("WordActivity",MODE_PRIVATE);
    int fixed=p.getInt(FieldArt.SCENE_PREF,-1);
    android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
    boolean animate=android.animation.ValueAnimator.areAnimatorsEnabled() && !power.isPowerSaveMode();
    scenery.scene(fixed>=0 && fixed<FieldArt.SCENES.length ? fixed : id,false,
        animate,p.getBoolean(SceneryPane.CROSSFADE,false));
  }

  void notifyJs(String code) {
    runOnUiThread(
        () -> {
          if (!isDestroyed()) web.evaluateJavascript(code, null);
        });
  }

  void stopAudio() {
    playbackToken++;
    clock.removeCallbacksAndMessages(null);
    if (player != null) {
      player.release();
      player = null;
    }
  }

  void finished() {
    stopAudio();
    notifyJs("window.audioDone()");
  }

  @Override
  protected void onStop() {
    super.onStop();
    finished();
  }

  @Override
  protected void onDestroy() {
    stopAudio();
    if (web != null) {
      web.stopLoading();
      web.removeJavascriptInterface("Reader");
      if (web.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) web.getParent()).removeView(web);
      web.destroy();
    }
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (web==null || isFinishing() || isDestroyed()) return;
    web.evaluateJavascript(
        "typeof window.goBack === 'function' && window.goBack() === true",
        value -> {
          if (!"true".equals(value) && !isFinishing() && !isDestroyed()) super.onBackPressed();
        });
  }

  class Bridge {
    @JavascriptInterface public void scene(String page) {
      runOnUiThread(() -> { if (!isDestroyed()) chooseScenery(page); });
    }
    @JavascriptInterface
    public boolean motionEnabled() {
      return android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    @JavascriptInterface
    public void tapFeedback() {
      runOnUiThread(
          () -> web.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY));
    }

    @JavascriptInterface
    public String libraryIds() {
      String[] dirs = library.list((dir, name) -> name.matches("b[0-9a-f]{32}"));
      return new JSONArray(dirs == null ? Collections.emptyList() : Arrays.asList(dirs)).toString();
    }

    @JavascriptInterface
    public void chooseBook() {
      runOnUiThread(
          () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 17);
          });
    }

    @JavascriptInterface
    public void stop() {
      runOnUiThread(() -> stopAudio());
    }

    @JavascriptInterface
    public void play(String book, double start, double end, float rate) {
      runOnUiThread(
          () -> {
            stopAudio();
            long token = playbackToken;
            try {
              player = new MediaPlayer();
              if ("demo".equals(book)) {
                try (AssetFileDescriptor fd = getAssets().openFd("demo/audio.mp3")) {
                  player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
              } else {
                if (!book.matches("b[0-9a-f]{32}")) throw new IOException();
                player.setDataSource(new File(new File(library, book), "audio.mp3").getPath());
              }
              player.setOnErrorListener(
                  (mp, a, b) -> {
                    finished();
                    return true;
                  });
              player.setOnCompletionListener(mp -> finished());
              player.setOnPreparedListener(
                  mp -> {
                    if (token != playbackToken) return;
                    mp.setOnSeekCompleteListener(
                        ready -> {
                          if (token != playbackToken) return;
                          ready.setPlaybackParams(
                              ready
                                  .getPlaybackParams()
                                  .setSpeed(Math.max(.6f, Math.min(1.5f, rate))));
                          ready.start();
                          clock.post(
                              new Runnable() {
                                public void run() {
                                  if (token != playbackToken || player == null) return;
                                  if (player.getCurrentPosition() >= end * 1000) {
                                    finished();
                                  } else clock.postDelayed(this, 45);
                                }
                              });
                        });
                    mp.seekTo((long) (start * 1000), MediaPlayer.SEEK_CLOSEST);
                  });
              player.prepareAsync();
            } catch (Exception ex) {
              finished();
              notice("音频未能打开");
            }
          });
    }
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req != 17 || result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    new Thread(() -> importBook(uri)).start();
  }

  void importBook(Uri uri) {
    String id = "b" + UUID.randomUUID().toString().replace("-", "");
    File stage = new File(library, "tmp-" + id);
    stage.mkdir();
    try {
      Set<String> names = new HashSet<>();
      long bytes = 0;
      try (ZipInputStream zip = new ZipInputStream(getContentResolver().openInputStream(uri))) {
        ZipEntry entry;
        byte[] buffer = new byte[65536];
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName();
          if (!name.matches("lesson\\.json|audio\\.mp3|page[0-9]{1,4}\\.png")
              || !names.add(name)
              || names.size() > 500) throw new IOException();
          long size = 0;
          try (OutputStream out = new FileOutputStream(new File(stage, name))) {
            int count;
            while ((count = zip.read(buffer)) != -1) {
              size += count;
              bytes += count;
              if (bytes > 512L * 1024 * 1024 || ("lesson.json".equals(name) && size > 3000000))
                throw new IOException();
              out.write(buffer, 0, count);
            }
          }
        }
      }
      JSONObject lesson =
          new JSONObject(
              new String(
                  java.nio.file.Files.readAllBytes(new File(stage, "lesson.json").toPath()),
                  "UTF-8"));
      JSONArray cues = lesson.getJSONArray("cues");
      if (lesson.getString("title").isEmpty()
          || cues.length() == 0
          || cues.length() > 3000
          || !names.contains("audio.mp3")) throw new IOException();
      for (int i = 0; i < cues.length(); i++) {
        JSONObject cue = cues.getJSONObject(i);
        if (cue.getString("ru").isEmpty()
            || !cue.has("zh")
            || cue.getDouble("start") < 0
            || cue.getDouble("end") <= cue.getDouble("start")) throw new IOException();
        int page = cue.optInt("page", 0);
        if (page > 0 && !names.contains("page" + page + ".png")) throw new IOException();
      }
      if (!stage.renameTo(new File(library, id))) throw new IOException();
      notifyJs("window.imported(" + JSONObject.quote(id) + ")");
    } catch (Exception ex) {
      File[] files = stage.listFiles();
      if (files != null) for (File f : files) f.delete();
      stage.delete();
      runOnUiThread(() -> notice("请选择 .tingye 课程包"));
    }
  }
}
