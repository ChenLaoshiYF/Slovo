package studio.tingye.reader;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class CloudAi {
  enum Provider {
    DEEPSEEK("DeepSeek","https://api.deepseek.com","deepseek-flash","https://platform.deepseek.com/usage"),
    QWEN("千问 · 北京","https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-flash","https://bailian.console.aliyun.com/"),
    OPENAI("OpenAI","https://api.openai.com/v1","gpt-4o-mini","https://platform.openai.com/usage"),
    CUSTOM("自定义兼容服务","","","");

    final String title, endpoint, model, dashboard;
    Provider(String title,String endpoint,String model,String dashboard) {
      this.title=title;this.endpoint=endpoint;this.model=model;this.dashboard=dashboard;
    }
  }

  static final class Reply {
    final String text;
    final int tokens;
    Reply(String text,int tokens) { this.text=text;this.tokens=tokens; }
  }

  private static final String ALIAS="SlovoAiKeyEncryption";

  static Provider selected(SharedPreferences prefs) {
    try { return Provider.valueOf(prefs.getString("aiProvider","DEEPSEEK")); }
    catch(IllegalArgumentException error) { return Provider.DEEPSEEK; }
  }

  static boolean configured(SharedPreferences prefs,Provider provider) {
    return prefs.contains("keyEncrypted_"+provider.name());
  }

  static String model(SharedPreferences prefs,Provider provider) {
    return provider==Provider.CUSTOM?prefs.getString("customAiModel",""):provider.model;
  }

  static String endpoint(SharedPreferences prefs,Provider provider) {
    return provider==Provider.CUSTOM?prefs.getString("customAiUrl",""):provider.endpoint;
  }

  static String checkedEndpoint(String value) throws Exception {
    java.net.URI uri=new java.net.URI(value);
    if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null
        || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
      throw new IOException("请输入有效的 HTTPS 接口地址");
    return value.replaceFirst("/+$","").replaceFirst("/chat/completions$","");
  }

  private static synchronized SecretKey encryption(boolean make) throws Exception {
    KeyStore keys=KeyStore.getInstance("AndroidKeyStore");
    keys.load(null);
    SecretKey key=(SecretKey)keys.getKey(ALIAS,null);
    if(key!=null || !make) return key;
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS,
        KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256).build());
    return generator.generateKey();
  }

  static synchronized void saveKey(SharedPreferences prefs,Provider provider,String key,String url,String model) throws Exception {
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,encryption(true));
    cipher.updateAAD((provider.name()+url).getBytes(StandardCharsets.UTF_8));
    String data=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"
        +Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    SharedPreferences.Editor save=prefs.edit().putString("keyEncrypted_"+provider.name(),data)
        .remove(usageKey(provider));
    if(provider==Provider.CUSTOM)save.putString("customAiUrl",url).putString("customAiModel",model);
    save.apply();
  }

  static synchronized void clearKey(SharedPreferences prefs,Provider provider) {
    prefs.edit().remove("keyEncrypted_"+provider.name()).remove(usageKey(provider)).apply();
  }

  static String usageKey(Provider provider) { return "lastAiTokens_"+provider.name(); }

  private static String rawKey(SharedPreferences prefs,Provider provider) throws Exception {
    SecretKey key=encryption(false);
    String ciphertext=prefs.getString("keyEncrypted_"+provider.name(),"");
    String[] parts=ciphertext.split(":",2);
    if(key==null || parts.length!=2) throw new IOException("请先保存 API 密钥");
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
    String url=provider==Provider.CUSTOM?endpoint(prefs,provider):"";
    cipher.updateAAD((provider.name()+url).getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
  }

  static Reply complete(SharedPreferences prefs,Provider provider,String instruction,String input) throws Exception {
    JSONArray messages=new JSONArray()
        .put(new JSONObject().put("role","system").put("content",instruction))
        .put(new JSONObject().put("role","user").put("content",input));
    return send(prefs,provider,messages);
  }

  static Reply chat(SharedPreferences prefs,Provider provider,String instruction,
      java.util.List<String> history,String input) throws Exception {
    JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",instruction));
    for(int i=Math.max(0,history.size()-12);i<history.size();i++)
      messages.put(new JSONObject().put("role",i%2==0?"user":"assistant").put("content",history.get(i)));
    messages.put(new JSONObject().put("role","user").put("content",input));
    return send(prefs,provider,messages);
  }

  private static Reply send(SharedPreferences prefs,Provider provider,JSONArray messages) throws Exception {
    String endpoint,model,key,stored;
    synchronized(CloudAi.class) {
      endpoint=endpoint(prefs,provider);
      model=model(prefs,provider);
      key=rawKey(prefs,provider);
      stored=prefs.getString("keyEncrypted_"+provider.name(),"");
    }

    JSONObject body=new JSONObject().put("model",model).put("messages",messages)
        .put("stream",false).put("max_tokens",320).put("temperature",.3);
    if(provider==Provider.DEEPSEEK)body.put("thinking",new JSONObject().put("type","disabled"));
    if(provider==Provider.QWEN)body.put("enable_thinking",false);
    JSONObject response=request(endpoint+"/chat/completions",key,body);
    JSONArray choices=response.optJSONArray("choices");
    if(choices==null || choices.length()==0)throw new IOException("服务没有返回结果");
    JSONObject message=choices.getJSONObject(0).getJSONObject("message");
    String text=message.optString("content","").trim();
    if(text.isEmpty())throw new IOException("模型没有返回文字");
    JSONObject usage=response.optJSONObject("usage");
    int total=usage==null?-1:usage.optInt("total_tokens",-1);
    if(total>=0 && stored.equals(prefs.getString("keyEncrypted_"+provider.name(),"")))
      prefs.edit().putInt(usageKey(provider),total).apply();
    return new Reply(text,total);
  }

  static String balance(SharedPreferences prefs,Provider provider) throws Exception {
    if(provider!=Provider.DEEPSEEK)throw new IOException("请在服务商控制台查询账单或额度");
    JSONObject response=request(provider.endpoint+"/user/balance",rawKey(prefs,provider),null);
    JSONArray balances=response.getJSONArray("balance_infos");
    StringBuilder line=new StringBuilder();
    for(int i=0;i<balances.length();i++) {
      JSONObject item=balances.getJSONObject(i);
      if(i>0)line.append(" · ");
      line.append(item.getString("total_balance")).append(" ").append(item.getString("currency"));
    }
    return line.length()==0?"服务商未返回余额":line.toString();
  }

  private static JSONObject request(String url,String key,JSONObject body) throws Exception {
    HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();
    connection.setConnectTimeout(3200);
    connection.setReadTimeout(14_000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Authorization","Bearer "+key);
    connection.setRequestProperty("Accept","application/json");
    try {
      if(body!=null) {
        byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(data.length);
        try(java.io.OutputStream out=connection.getOutputStream()) { out.write(data); }
      }
      int status=connection.getResponseCode();
      if(status==401||status==403)throw new IOException("密钥无效，或没有此服务的访问权限");
      if(status==402)throw new IOException("账户余额不足");
      if(status==429)throw new IOException("请求受限，请查看服务商额度");
      if(status<200||status>=300)throw new IOException("服务返回 "+status);
      try(InputStream source=connection.getInputStream()) {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];
        for(int n;(n=source.read(buffer))!=-1;) {
          if(bytes.size()+n>128*1024)throw new IOException("结果过长");
          bytes.write(buffer,0,n);
        }
        return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
      }
    } finally { connection.disconnect(); }
  }
}
