package studio.tingye.reader;

import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared by photo OCR and the manual translator. No network is needed for local matches. */
final class OfflineTranslation {
  private static final Pattern RUSSIAN=Pattern.compile("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*");
  private final WordActivity a;
  private final LruCache<String,String> matches=new LruCache<>(96);
  private JSONObject sentences;

  OfflineTranslation(WordActivity activity) { a=activity; }

  private synchronized JSONObject sentences() throws Exception {
    if(sentences!=null)return sentences;
    try(InputStream file=a.getAssets().open("photo_sentences.json")) {
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      byte[] buffer=new byte[8192];
      for(int n;(n=file.read(buffer))!=-1;)bytes.write(buffer,0,n);
      sentences=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
    }
    return sentences;
  }

  private static String key(String raw) {
    String unstressed=Normalizer.normalize(raw.toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
        .replace("\u0301","");
    return Normalizer.normalize(unstressed,Normalizer.Form.NFC).replace('ё','е')
        .replaceAll("\\s+"," ").replaceAll("^[ .!?…«»\\\"]+|[ .!?…«»\\\"]+$","");
  }

  String find(String input) throws Exception {
    String normalized=key(input);
    String cached=matches.get(normalized);
    if(cached!=null)return cached;
    String result="";
    if(RUSSIAN.matcher(normalized).matches()) {
      List<Lexicon.Word> words=a.lex.search(normalized,true);
      if(!words.isEmpty()) {
        LinkedHashSet<String> meanings=new LinkedHashSet<>();
        for(Lexicon.Word word:words) {
          if(!word.zh.isEmpty())meanings.add(a.brief(word.zh));
          if(meanings.size()==3)break;
        }
        result=String.join(" ／ ",meanings);
      }
    } else {
      JSONArray row=sentences().optJSONArray(normalized);
      if(row!=null)result=row.optString(0);
    }
    if(!normalized.isEmpty())matches.put(normalized,result);
    return result;
  }

  private Map<String,String> words(String text) throws Exception {
    Map<String,String> result=new LinkedHashMap<>();
    Matcher tokens=RUSSIAN.matcher(text);
    while(tokens.find() && result.size()<7) {
      String token=Lexicon.key(tokens.group());
      if(token.length()<3 || result.containsKey(token))continue;
      List<Lexicon.Word> matches=a.lex.search(token,true);
      if(matches.isEmpty())continue;
      String meaning=matches.get(0).zh.split("[、，,；;。]",2)[0].trim();
      if(!meaning.isEmpty() && meaning.length()<=22)result.put(token,meaning);
    }
    return result;
  }

  String hints(String sentence) throws Exception {
    StringBuilder note=new StringBuilder();
    for(Map.Entry<String,String> item:words(sentence).entrySet()) {
      if(note.length()>0)note.append(" · ");
      note.append(item.getKey()).append(' ').append(item.getValue());
    }
    return note.toString();
  }

  String infer(String sentence) throws Exception {
    Map<String,String> words=words(sentence);
    String translated=LocalLanguageModel.translate(a,sentence);
    if(!translated.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN))
      throw new java.io.IOException("没有译出中文");
    Matcher leftovers=RUSSIAN.matcher(translated);
    StringBuffer readable=new StringBuffer();
    while(leftovers.find()) {
      String meaning=words.get(Lexicon.key(leftovers.group()));
      leftovers.appendReplacement(readable,Matcher.quoteReplacement(meaning==null?leftovers.group():meaning));
    }
    leftovers.appendTail(readable);
    return readable.toString();
  }
}
