package studio.tingye.reader;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Public, account-free release feed with a relay, checksum and installed-certificate check. */
final class AppUpdates {
  static final String FEED="https://raw.githubusercontent.com/ChenLaoshiYF/Slovo/main/updates/stable.json";
  static final String RELAY="https://gh-proxy.org/";
  private final WordActivity a;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private volatile HttpURLConnection connection;
  private volatile boolean busy,closed,cancelled;
  private boolean ready;
  private JSONObject release,asset;
  private String message="点一下，查看有没有新版本。";
  private TextView status;
  private LinearLayout actions;
  private ProgressBar progress;
  AppUpdates(WordActivity activity) { a=activity; }

  void render() {
    a.gap(a.body,20);
    a.add(a.body,a.txt("词汐 "+version(),26,PaperActivity.ink,true),-2);
    a.gap(a.body,8);
    a.add(a.body,a.txt("手机和平板使用同一个安装包。更新会保留学习记录、词库和设置。",15,PaperActivity.muted,false),-2);
    a.gap(a.body,18);
    a.row(a.body,"下载线路",direct()?"GitHub 原站":"国内加速 · 失败自动尝试原站",R.drawable.s_options,this::route);
    status=a.txt(message,16,PaperActivity.ink,false);a.add(a.body,status,-2);
    progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(1000);progress.setVisibility(busy?android.view.View.VISIBLE:android.view.View.GONE);
    a.add(a.body,progress,12);a.gap(a.body,14);
    actions=a.box(true);a.add(a.body,actions,-2);drawActions();
    a.gap(a.body,20);
    a.row(a.body,"GitHub 发布页","查看版本说明和全部安装包",R.drawable.s_arrow,()->browse("https://github.com/ChenLaoshiYF/Slovo/releases"));
    a.row(a.body,"下载加速服务","GH-Proxy · 免费公共服务",R.drawable.s_arrow,()->browse("https://gh-proxy.org/"));
  }
  private void browse(String url) {
    try { a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
    catch(ActivityNotFoundException unavailable) { a.tip("当前设备没有浏览器"); }
  }
  private boolean direct() { return a.getPreferences(0).getBoolean("updatesDirect",false); }
  private void route() {
    if(busy) { a.tip("请先取消当前操作");return; }
    new FeedbackDialog(a).setTitle("下载线路")
        .setSingleChoiceItems(new String[]{"国内加速（自动切换原站）","GitHub 原站"},direct()?1:0,(d,index)->{
          d.dismiss();a.getPreferences(0).edit().putBoolean("updatesDirect",index==1).apply();a.show("updates");
        }).setNegativeButton("取消",null).show();
  }
  String version() {
    try { return a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionName; }
    catch(PackageManager.NameNotFoundException impossible) { return ""; }
  }
  private long code() throws PackageManager.NameNotFoundException {
    PackageInfo info=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
    return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
  }
  private void drawActions() {
    if(actions==null || !actions.isAttachedToWindow() && !a.screen.equals("updates"))return;
    actions.removeAllViews();
    if(busy) {
      a.add(actions,a.action("取消",0,false,this::cancel),-2);return;
    }
    a.add(actions,a.action(ready?"安装更新":"检查更新",R.drawable.s_arrow,true,ready?this::install:this::check),-2);
    if(!ready && asset!=null) {
      a.gap(actions,9);
      a.add(actions,a.action("下载 "+release.optString("versionName")+" · "+(asset.optLong("size")/1024/1024)+" MB",0,false,this::download),-2);
    }
  }
  private void state(String text,boolean working) {
    message=text;busy=working;
    if(!a.isDestroyed() && a.screen.equals("updates")) {
      if(status!=null)status.setText(text);
      if(progress!=null)progress.setVisibility(working?android.view.View.VISIBLE:android.view.View.GONE);
      drawActions();
    }
  }
  void check() {
    if(busy)return;
    cancelled=false;asset=null;release=null;
    state("正在检查更新…",true);
    worker.execute(()->{
      try {
        JSONObject feed=null;Exception last=null;
        for(String url:routes(FEED)) {
          try { feed=new JSONObject(new String(fetch(url,256*1024),StandardCharsets.UTF_8));break; }
          catch(Exception failure) { last=failure;if(cancelled)throw failure; }
        }
        if(feed==null)throw last==null?new IOException("无法读取版本信息"):last;
        if(!"studio.tingye.reader".equals(feed.getString("package")))throw new IOException("更新信息不匹配");
        boolean newer=feed.getLong("versionCode")>code();
        JSONObject choice=null;
        if(newer) {
          if(feed.optInt("minSdk",26)>Build.VERSION.SDK_INT)throw new IOException("新版本需要更高的安卓版本");
          JSONObject packages=feed.getJSONObject("assets");
          for(String abi:Build.SUPPORTED_ABIS)if(packages.has(abi)) { choice=packages.getJSONObject(abi);break; }
          if(choice==null)throw new IOException("暂时没有适合这台设备的安装包");
          validateAsset(choice);
        }
        JSONObject latest=feed,selected=choice;
        String description=newer?"发现 "+feed.getString("versionName")+"\n\n"+feed.optString("notes"):"已经是最新版本 · "+version();
        post(()->{ release=latest;asset=selected;state(description,false); });
      } catch(Exception error) { post(()->state(cancelled?"已取消":"暂时没查到更新，可以重试或打开发布页。",false)); }
    });
  }
  static void validateAsset(JSONObject item) throws Exception {
    URI uri=new URI(item.getString("url"));
    if(!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getUserInfo()!=null
        || !uri.getPath().startsWith("/ChenLaoshiYF/Slovo/releases/download/") || !uri.getPath().endsWith(".apk")
        || uri.getQuery()!=null || uri.getFragment()!=null || !item.getString("sha256").matches("[a-f0-9]{64}")
        || item.getLong("size")<100000 || item.getLong("size")>900L*1024*1024)
      throw new IOException("更新安装包信息无效");
  }
  private List<String> routes(String url) { return direct()?Collections.singletonList(url):Arrays.asList(RELAY+url,url); }
  private HttpURLConnection open(String address) throws IOException {
    URL url=new URL(address);
    for(int redirects=0;redirects<5;redirects++) {
      if(cancelled || closed)throw new IOException("Cancelled");
      if(!"https".equals(url.getProtocol()))throw new IOException("HTTPS required");
      HttpURLConnection c=(HttpURLConnection)url.openConnection(); connection=c;
      c.setConnectTimeout(9000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent","Slovo-Android/0.9");c.setRequestProperty("Cache-Control","no-cache");
      int response=c.getResponseCode();
      if(response>=300 && response<=399) {
        String location=c.getHeaderField("Location");c.disconnect();
        if(location==null)throw new IOException("No redirect target");url=new URL(url,location);continue;
      }
      if(response!=200) { c.disconnect();throw new IOException("HTTP "+response); }
      return c;
    }
    throw new IOException("Too many redirects");
  }
  private byte[] fetch(String url,int limit) throws IOException {
    HttpURLConnection c=open(url);
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;) {
        if(cancelled || closed || out.size()+n>limit)throw new IOException("Cancelled or too large");out.write(buffer,0,n);
      }
      return out.toByteArray();
    } finally { c.disconnect();connection=null; }
  }
  private void download() {
    if(busy || asset==null)return;
    cancelled=false;state("正在下载，下载后会校验安装包…",true);
    JSONObject chosen=asset;long targetVersion=release.optLong("versionCode");
    worker.execute(()->{
      File part=new File(a.getCacheDir(),"slovo-update.part");
      try {
        Exception last=null;boolean obtained=false;
        for(String url:routes(chosen.getString("url"))) {
          try {
            HttpURLConnection c=open(url);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0,lastProgress=0;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part)) {
              byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;) {
                if(cancelled || closed)throw new IOException("Cancelled");
                total+=n;if(total>chosen.getLong("size"))throw new IOException("Package exceeds declared size");
                out.write(buffer,0,n);digest.update(buffer,0,n);
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastProgress>200) {
                  lastProgress=now;long count=total;
                  post(()->{if(progress!=null)progress.setProgress((int)(count*1000/chosen.optLong("size")));});
                }
              }
            } finally { c.disconnect();connection=null; }
            if(total!=chosen.getLong("size") || !hex(digest.digest()).equals(chosen.getString("sha256")))
              throw new IOException("安装包校验不一致");
            verify(a,part,targetVersion);
            if(!part.renameTo(UpdateFile.file(a)))throw new IOException("无法保存更新");
            obtained=true;break;
          } catch(Exception error) { last=error;part.delete();if(cancelled || closed)throw error; }
        }
        if(!obtained)throw last==null?new IOException("Download failed"):last;
        post(()->{ready=true;state("下载完成，文件与应用签名已核对。",false);});
      } catch(Exception error) {
        part.delete();android.util.Log.w("Slovo","Update download failed",error);
        post(()->state(cancelled?"已取消下载":"下载未完成，请重试或切换线路。",false));
      }
    });
  }
  static void verify(Context context,File apk,long expectedCode) throws Exception {
    PackageManager pm=context.getPackageManager();
    int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
    PackageInfo downloaded=pm.getPackageArchiveInfo(apk.getPath(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);
    if(downloaded==null || !context.getPackageName().equals(downloaded.packageName))throw new IOException("安装包不属于词汐");
    long next=Build.VERSION.SDK_INT>=28?downloaded.getLongVersionCode():downloaded.versionCode;
    long current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
    if(next!=expectedCode || next<=current)throw new IOException("更新版本不匹配");
    Signature[] old=Build.VERSION.SDK_INT>=28?installed.signingInfo.getApkContentsSigners():installed.signatures;
    Signature[] fresh=Build.VERSION.SDK_INT>=28?downloaded.signingInfo.getApkContentsSigners():downloaded.signatures;
    if(old==null || fresh==null || old.length!=fresh.length)throw new IOException("签名不匹配");
    for(int i=0;i<old.length;i++)if(!old[i].equals(fresh[i]))throw new IOException("签名不匹配");
  }
  private static String hex(byte[] bytes) {
    StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
  }
  private void install() {
    try {
      if(!UpdateFile.file(a).isFile()) { ready=false;state("安装包已被系统清理，请重新下载。",false);return; }
      verify(a,UpdateFile.file(a),release.getLong("versionCode"));
      if(!a.getPackageManager().canRequestPackageInstalls()) {
        new FeedbackDialog(a).setTitle("允许词汐安装更新")
            .setMessage("安卓需要你允许此应用安装新版本。返回后再点“安装更新”。")
            .setNegativeButton("稍后",null).setPositiveButton("去允许",(d,w)->a.startActivity(new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())))).show();return;
      }
      Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(UpdateFile.uri(a),"application/vnd.android.package-archive")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      a.startActivity(install);
    } catch(Exception error) { a.tip("没有打开安装程序，请重新下载或从发布页安装"); }
  }
  private void post(Runnable task) { a.ui.post(()->{if(!closed && !a.isDestroyed())task.run();}); }
  private void cancel() { cancelled=true;HttpURLConnection active=connection;if(active!=null)active.disconnect(); }
  void close() { closed=true;cancel();worker.shutdownNow(); }
}
