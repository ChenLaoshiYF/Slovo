package studio.tingye.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

final class Lexicon {
  static String key(String s) {
    return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).trim()
        .toLowerCase(Locale.ROOT).replace("́", "").replace('ё', 'е')
        .replace('‑','-').replace('‐','-');
  }

  static final class Word {
    final String k, ru, zh, pos;

    Word(String k, String ru, String zh, String pos) {
      this.k = k;
      this.ru = ru;
      this.zh = zh;
      this.pos = pos;
    }
  }

  static final class Book {
    final String k, title, sub, ru, series, cover, reference;
    final int themeCount;
    final List<String> words = new ArrayList<>();

    Book(JSONObject j) throws JSONException {
      k = j.getString("key");
      title = j.getString("title");
      sub = j.getString("sub");
      ru = j.getString("ru");
      series = j.optString("series", k);
      cover = j.optString("cover", "book_mine");
      reference = j.optString("reference", title);
      JSONArray a = j.getJSONArray("words");
      themeCount = j.optInt("themeCount", a.length());
      for (int i = 0; i < a.length(); i++) words.add(a.getString(i));
    }

    Book(String key, String name, String description) {
      k = key;
      title = name;
      sub = description;
      ru = "";
      series = k;
      cover = "book_mine";
      reference = "个人笔记本";
      themeCount = 0;
    }
  }

  final Context context;
  final List<Book> books = new ArrayList<>();
  final LinkedHashMap<String, Word> core = new LinkedHashMap<>();
  final LinkedHashMap<String, Word> supplements = new LinkedHashMap<>();
  final HashMap<String, AudioRange> supplementalAudio = new HashMap<>();
  final JSONObject usage;
  final JSONObject reviewEvidence;
  int size;
  private SQLiteDatabase db;

  static final class AudioRange {
    final long start;
    final int bytes;

    AudioRange(long start, int bytes) {
      this.start = start;
      this.bytes = bytes;
    }
  }

  Lexicon(Context c) throws Exception {
    context = c.getApplicationContext();
    try (InputStream in = c.getAssets().open("usage.json");
         BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      usage = new JSONObject(r.readLine());
    }
    try (InputStream in = c.getAssets().open("shelves.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      JSONObject j = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
      size = j.getInt("lexCount");
      JSONArray rows = j.getJSONArray("books");
      for (int i = 0; i < rows.length(); i++) books.add(new Book(rows.getJSONObject(i)));
      books.add(new Book("mine", "我的生词本", "自己收下的词"));
      JSONObject words = j.getJSONObject("words");
      for (Iterator<String> it = words.keys(); it.hasNext(); ) {
        String k = it.next();
        JSONArray v = words.getJSONArray(k);
        core.put(k, new Word(k, v.getString(0), v.getString(1), v.getString(2)));
      }
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        c.getAssets().open("supplements.json"), StandardCharsets.UTF_8))) {
      JSONObject words = new JSONObject(reader.readLine());
      for (Iterator<String> it = words.keys(); it.hasNext(); ) {
        String key = it.next();
        JSONArray values = words.getJSONArray(key);
        supplements.put(key, new Word(key, values.getString(0), values.getString(1), values.getString(2)));
        String stress = values.getString(3);
        if (!stress.isEmpty()) {
          JSONObject hints=usage.optJSONObject(key);
          if(hints==null){hints=new JSONObject();usage.put(key,hints);}
          if(!hints.has("stress"))hints.put("stress",stress);
        }
      }
    }
    try (BufferedReader reader=new BufferedReader(new InputStreamReader(
        c.getAssets().open("review-evidence.json"),StandardCharsets.UTF_8))) {
      reviewEvidence=new JSONObject(reader.readLine());
      for(Iterator<String> it=reviewEvidence.keys();it.hasNext();){
        String key=it.next();
        JSONObject record=reviewEvidence.getJSONObject(key);
        String stress=record.optString("stress");
        if(!stress.isEmpty()){
          JSONObject hint=usage.optJSONObject(key);
          if(hint==null){hint=new JSONObject();usage.put(key,hint);}
          if(!hint.has("stress") || record.has("meaning"))hint.put("stress",stress);
        }
        if(record.has("meaning")){
          Word previous=publishedWord(key);
          if(previous==null)continue;
          Word corrected=new Word(key,stress.replace("́",""),record.getString("meaning"),previous.pos);
          if(core.containsKey(key))core.put(key,corrected);
          else supplements.put(key,corrected);
        }
      }
    }
    try (BufferedReader reader=new BufferedReader(new InputStreamReader(c.getAssets().open("dictionary-shortcuts.json"),StandardCharsets.UTF_8))) {
      JSONObject extra=new JSONObject(reader.readLine());
      for(Iterator<String> it=extra.keys();it.hasNext();) {
        String key=it.next();JSONArray values=extra.getJSONArray(key);
        supplements.putIfAbsent(key,new Word(key,values.getString(0),values.getString(1),values.getString(2)));
      }
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        c.getAssets().open("voices-supplements.tsv"), StandardCharsets.UTF_8))) {
      for (String row; (row = reader.readLine()) != null; ) {
        String[] fields = row.split("\t", 3);
        supplementalAudio.put(fields[0], new AudioRange(Long.parseLong(fields[1]),
            Integer.parseInt(fields[2])));
      }
    }
  }

  Word publishedWord(String key) {
    Word original = core.get(key);
    return original != null ? original : supplements.get(key);
  }

  Book book(String k) {
    for (Book b : books) if (b.k.equals(k)) return b;
    return books.get(0);
  }

  synchronized SQLiteDatabase dictionary() throws IOException {
    if (db != null) return db;
    File f = new File(context.getFilesDir(), "slovo-lex-1.sqlite");
    if (!f.isFile()) {
      File reserve = new File(f.getPath() + ".part");
      try (InputStream in =
              new java.util.zip.GZIPInputStream(context.getAssets().open("lexicon.bin"));
          OutputStream out = new FileOutputStream(reserve)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
      if (!reserve.renameTo(f)) throw new IOException("词典未能保存");
    }
    db =
        SQLiteDatabase.openDatabase(
            f.getPath(),
            null,
            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    return db;
  }

  void readRows(Map<String, Word> out, String sql, String... args) throws IOException {
    try (Cursor cur = dictionary().rawQuery(sql, args)) {
      while (cur.moveToNext() && out.size() < 32) {
        String k = cur.getString(0);
        Word contemporary = publishedWord(k);
        out.putIfAbsent(k, contemporary != null ? contemporary :
            new Word(k, cur.getString(1), cur.getString(2), cur.getString(3)));
      }
    }
  }

  List<Word> search(String raw, boolean exact) throws IOException {
    String q = key(raw);
    LinkedHashMap<String, Word> out = new LinkedHashMap<>();
    if (q.isEmpty()) return new ArrayList<>();
    Word published = publishedWord(q);
    if (published != null) out.put(q, published);
    if (q.matches(".*[а-яё].*")) {
      readRows(out, "SELECT * FROM lex WHERE k=?", q);
      readRows(
          out,
          "SELECT l.* FROM forms f JOIN lex l ON l.k=f.k WHERE f.f=? ORDER BY length(l.k) LIMIT 12",
          q);
      if (!exact)
        readRows(out, "SELECT * FROM lex WHERE k>=? AND k<? ORDER BY k LIMIT 32", q, q + "\uffff");
      if (!exact) {
        for (Word word : core.values()) {
          if (out.size() >= 32) break;
          if (word.k.startsWith(q)) out.putIfAbsent(word.k, word);
        }
      }
    } else if (!exact) {
      for (Word w : core.values()) if (w.zh.contains(q) && out.size() < 14) out.put(w.k, w);
      String gram = q.substring(0, Math.min(2, q.length()));
      readRows(
          out,
          "SELECT l.* FROM zh_index z JOIN lex l ON l.k=z.k WHERE z.gram=? AND instr(l.zh,?)>0"
              + " LIMIT 32",
          gram,
          q);
    }
    return new ArrayList<>(out.values());
  }

  List<Word> closeSpellings(String raw) {
    String query = key(raw);
    if (!query.matches("[а-я-]{3,32}")) return Collections.emptyList();
    ArrayList<Word> near = new ArrayList<>();
    for (Word w : core.values()) if (!w.k.equals(query) && oneEdit(query, w.k)) near.add(w);
    near.sort(Comparator.comparing(w -> w.ru));
    return near.subList(0, Math.min(near.size(), 3));
  }

  // Insertion, omission, substitution or one neighbouring transposition.
  static boolean oneEdit(String a, String b) {
    if (Math.abs(a.length() - b.length()) > 1) return false;
    if (a.length() > b.length()) return oneEdit(b, a);
    int i = 0;
    while (i < a.length() && a.charAt(i) == b.charAt(i)) i++;
    if (i == a.length()) return b.length() == a.length() + 1;
    if (a.length() < b.length()) return a.regionMatches(i, b, i + 1, a.length() - i);
    if (a.regionMatches(i + 1, b, i + 1, a.length() - i - 1)) return true;
    return i + 1 < a.length() && a.charAt(i) == b.charAt(i+1) && a.charAt(i+1) == b.charAt(i)
        && a.regionMatches(i+2, b, i+2, a.length()-i-2);
  }

  void close() {
    if (db != null) db.close();
  }
}
