package studio.tingye.reader;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

final class StudyStore extends SQLiteOpenHelper {
  static final int MAX_NEW_WORDS = 9999;
  private static final int NEW_PER_ROUND = 100;
  static final class Card {
    String k, ru, zh, pos, note = "", image = "";
    int seen, hits, miss;
    double half = 2;
    long first, last, due;

    Card(Lexicon.Word w) {
      k = w.k;
      ru = w.ru;
      zh = w.zh;
      pos = w.pos;
    }

    Lexicon.Word word() {
      return new Lexicon.Word(k, ru, zh, pos);
    }
    Card copy() {
      Card c = new Card(word());
      c.note = note; c.image = image; c.seen = seen; c.hits = hits; c.miss = miss;
      c.half = half; c.first = first; c.last = last; c.due = due;
      return c;
    }
  }

  static final class Session {
    final ArrayList<String> queue = new ArrayList<>();
    final HashSet<String> retry = new HashSet<>();
    String day;
    int index, right;

    boolean active() {
      return index < queue.size();
    }
    Session copy() {
      Session s = new Session();
      s.queue.addAll(queue); s.retry.addAll(retry);
      s.day = day; s.index = index; s.right = right;
      return s;
    }

    JSONObject json() throws JSONException {
      return new JSONObject()
          .put("q", new JSONArray(queue))
          .put("r", new JSONArray(retry))
          .put("i", index)
          .put("ok", right)
          .put("d", day);
    }
  }

  final LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
  final double[] weights = MemoryModel.PRIOR.clone();
  Session session = new Session();
  String plan = "";
  int goal = 10, adjustments = 0;
  final Lexicon lex;

  StudyStore(Context c, Lexicon lex) throws JSONException {
    super(c, "slovo-study.db", null, 3);
    this.lex = lex;
    setWriteAheadLoggingEnabled(true);
    try (Cursor cur =
        getReadableDatabase().rawQuery("select * from cards order by last desc", null)) {
      while (cur.moveToNext()) {
        Card card =
            new Card(
                new Lexicon.Word(
                    cur.getString(0), cur.getString(1), cur.getString(2), cur.getString(3)));
        card.seen = cur.getInt(4);
        card.hits = cur.getInt(5);
        card.miss = cur.getInt(6);
        card.half = cur.getDouble(7);
        card.first = cur.getLong(8);
        card.last = cur.getLong(9);
        card.due = cur.getLong(10);
        card.note = cur.getString(11);
        card.image = cur.getString(12);
        Lexicon.Word current = lex.publishedWord(card.k);
        if (current != null && !"自录词".equals(card.pos)) card.pos = current.pos;
        cards.put(card.k, card);
      }
    }
    try (Cursor cur =
        getReadableDatabase().rawQuery("select book,k from shelf_words order by rowid", null)) {
      while (cur.moveToNext()) {
        Lexicon.Book b = lex.book(cur.getString(0));
        String k = cur.getString(1);
        if (!b.words.contains(k)) b.words.add(k);
      }
    }
    plan = get("plan", "travel");
    if (plan.isEmpty()) plan = "travel";
    try { goal = Math.max(0, Math.min(MAX_NEW_WORDS,Integer.parseInt(get("goal", "10")))); }
    catch (NumberFormatException ignored) { goal = 10; }
    adjustments = Integer.parseInt(get("adjustments", "0"));
    JSONArray model = new JSONArray(get("theta", "[]"));
    for (int i = 0; i < weights.length && i < model.length(); i++) weights[i] = model.getDouble(i);
    JSONObject j = new JSONObject(get("session", "{}"));
    if (today().equals(j.optString("d"))) {
      session.day = today();
      session.index = j.optInt("i");
      session.right = j.optInt("ok");
      JSONArray q = j.getJSONArray("q"), r = j.getJSONArray("r");
      for (int i = 0; i < q.length(); i++) session.queue.add(q.getString(i));
      for (int i = 0; i < r.length(); i++) session.retry.add(r.getString(i));
    }
  }

  @Override
  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "create table cards(k text primary key,ru text,zh text,pos text,seen integer,hits"
            + " integer,miss integer,half real,first integer,last integer,due integer,note"
            + " text,image text)");
    d.execSQL("create table prefs(k text primary key,v text)");
    d.execSQL("create table activity(day text,k text,n integer,primary key(day,k))");
    createShelf(d);
    createLog(d);
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    if (a < 2) {
      createShelf(d);
      d.execSQL(
          "insert or ignore into shelf_words(book,k) select 'mine',k from cards where seen=0 or"
              + " note<>'' or image<>''");
    }
    if (a < 3) createLog(d);
  }

  static void createLog(SQLiteDatabase d) {
    d.execSQL("create table review_log(id integer primary key,k text not null,at integer not null,"
        + "grade integer not null,prior real,half real not null,next_due integer not null,book text not null)");
    d.execSQL("create index review_time on review_log(at)");
    d.execSQL("create index review_word_time on review_log(k,at)");
  }

  static void createShelf(SQLiteDatabase d) {
    d.execSQL("create table if not exists shelf_words(book text,k text,primary key(book,k))");
  }

  int addToBook(String bookKey, Collection<Lexicon.Word> words) throws JSONException {
    SQLiteDatabase db = getWritableDatabase();
    Lexicon.Book book = lex.book(bookKey);
    LinkedHashMap<String, Card> newCards = new LinkedHashMap<>();
    LinkedHashSet<String> added = new LinkedHashSet<>();
    boolean reset = false;
    db.beginTransaction();
    try {
      for (Lexicon.Word word : words) {
        if (book.words.contains(word.k) || !added.add(word.k)) continue;
        if (!cards.containsKey(word.k) && !newCards.containsKey(word.k)) {
          Card card = new Card(word);
          writeCard(card);
          newCards.put(word.k, card);
        }
        ContentValues values = new ContentValues();
        values.put("book", bookKey);
        values.put("k", word.k);
        db.insertOrThrow("shelf_words", null, values);
      }
      reset = !added.isEmpty() && bookKey.equals(plan) && session.index == 0;
      if (reset) put("session", "{}");
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    cards.putAll(newCards);
    book.words.addAll(added);
    if (reset) session = new Session();
    return added.size();
  }

  static String today() {
    return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
  }

  static long midnight() {
    Calendar c = Calendar.getInstance();
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }

  int todayCount() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("select count(*) from activity where day=?", new String[] {today()})) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  int todayNew() {
    long start = midnight();
    int n = 0;
    for (Card c : cards.values()) if (c.first >= start) n++;
    return n;
  }

  int newRemaining() {
    int limit = Math.max(0,goal-todayNew());
    if (limit == 0) return 0;
    int available = 0;
    for (String key : lex.book(plan).words) {
      Card card = cards.get(key);
      if ((card == null || card.seen == 0) && ++available == limit) return limit;
    }
    return available;
  }

  int due() {
    int n = 0;
    long now = System.currentTimeMillis();
    for (Card c : cards.values()) if (c.seen > 0 && c.due <= now) n++;
    return n;
  }

  static final class PreviousReview {
    final Lexicon.Word word;
    final int grade;
    PreviousReview(Lexicon.Word word,int grade) { this.word=word; this.grade=grade; }
  }

  PreviousReview previous(int offset) {
    try(Cursor c=getReadableDatabase().rawQuery(
        "SELECT k,grade FROM review_log WHERE at>=? ORDER BY id DESC LIMIT 1 OFFSET ?",
        new String[]{Long.toString(midnight()),Integer.toString(offset)})) {
      if(!c.moveToFirst()) return null;
      Lexicon.Word result=word(c.getString(0));
      return result==null?null:new PreviousReview(result,c.getInt(1));
    }
  }

  String get(String key, String def) {
    try (Cursor c =
        getReadableDatabase().rawQuery("select v from prefs where k=?", new String[] {key})) {
      return c.moveToFirst() ? c.getString(0) : def;
    }
  }

  void put(String key, Object val) {
    getWritableDatabase()
        .execSQL(
            "insert or replace into prefs values(?,?)", new Object[] {key, String.valueOf(val)});
  }

  Lexicon.Word word(String k) {
    Card c = cards.get(k);
    return c != null ? c.word() : lex.publishedWord(k);
  }

  void save(Card c) {
    writeCard(c);
    cards.put(c.k, c);
  }

  void writeCard(Card c) {
    getWritableDatabase()
        .execSQL(
            "insert or replace into cards values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            new Object[] {
              c.k, c.ru, c.zh, c.pos, c.seen, c.hits, c.miss, c.half, c.first, c.last, c.due,
              c.note, c.image
            });
  }

  int practiceDays() {
    try (Cursor c = getReadableDatabase().rawQuery("select count(distinct day) from activity", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  Card pin(Lexicon.Word w) {
    Card c = cards.get(w.k);
    if (c == null) {
      c = new Card(w);
      save(c);
    }
    return c;
  }

  void plan(String chosen, int limit) {
    if (limit < 0 || limit > MAX_NEW_WORDS) throw new IllegalArgumentException("每日新词量超出范围");
    if (chosen.equals(plan) && limit == goal) return;
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      put("session", "{}"); put("plan", chosen); put("goal", limit);
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
    session = new Session();
    plan = chosen;
    goal = limit;
  }

  boolean resumable() {
    return today().equals(session.day) && session.active();
  }

  void start() throws JSONException {
    if (resumable()) return;
    session = new Session();
    session.day = today();
    List<Card> all = new ArrayList<>(cards.values());
    all.sort(Comparator.comparingLong(c -> c.due));
    long now = System.currentTimeMillis();
    for (Card c : all)
      if (c.seen > 0 && c.due <= now && session.queue.size() < 25) session.queue.add(c.k);
    int limit = Math.min(NEW_PER_ROUND,Math.max(0,goal-todayNew()));
    List<String> book = lex.book(plan).words;
    Set<String> keys = new HashSet<>(book);
    for (Card c : all)
      if (c.seen == 0 && limit > 0 && keys.contains(c.k)) {
        session.queue.add(c.k);
        limit--;
      }
    for (String k : book) {
      if (limit == 0) break;
      if (!cards.containsKey(k)) {
        session.queue.add(k);
        limit--;
      }
    }
    put("session", session.json());
  }

  void extraRound() throws JSONException {
    session = new Session();
    session.day = today();
    List<Card> candidates = new ArrayList<>(cards.values());
    candidates.sort(
        Comparator.comparingDouble(
            c ->
                c.seen == 0
                    ? 0
                    : MemoryModel.recall(
                        (System.currentTimeMillis() - c.last) / MemoryModel.DAY, c.half)));
    for (Card c : candidates) {
      if (c.seen == 0) continue;
      session.queue.add(c.k);
      if (session.queue.size() == 5) break;
    }
    put("session", session.json());
  }

  static final class Estimate {
    final Card card;
    final double[] weights;
    boolean calibrated;
    Double prior;
    Estimate(Card c, double[] w) { card=c; weights=w; }
  }

  Estimate estimate(String expected, int g, long now) {
    Card old = cards.get(expected);
    Card c = old == null ? new Card(word(expected)) : old.copy();
    double[] learned = weights.clone();
    Estimate outcome = new Estimate(c,learned);
    if (c.seen > 0) {
      double age = Math.max(0, now - c.last) / MemoryModel.DAY;
      outcome.prior = MemoryModel.recall(age, c.half);
      if (age >= 10.0 / 1440) {
        MemoryModel.update(
            learned, MemoryModel.features(c.hits, c.miss, c.k.length()), age, g / 2.0, c.half);
        outcome.calibrated = true;
      }
    } else c.first = now;
    c.seen++;
    if (g == 2) c.hits++;
    else if (g == 0) c.miss++;
    c.half =
        MemoryModel.half(learned, MemoryModel.features(c.hits, c.miss, c.k.length()))
            * (g == 0 ? .45 : g == 1 ? .72 : 1);
    c.last = now;
    c.due = now + MemoryModel.delay(c.half, g);
    return outcome;
  }

  long grade(String expected, int g, long now) throws JSONException {
    if (g < 0 || g > 2) throw new IllegalArgumentException("请选择一个记忆程度");
    if (!session.active() || !session.queue.get(session.index).equals(expected))
      throw new IllegalStateException("词卡已提交");
    Estimate estimate = estimate(expected,g,now);
    Card c = estimate.card;
    double[] learned = estimate.weights;
    int updates = adjustments + (estimate.calibrated ? 1 : 0);
    Double prior = estimate.prior;
    Session upcoming = session.copy();
    if (g < 2 && upcoming.retry.add(c.k))
      upcoming.queue.add(Math.min(upcoming.queue.size(), upcoming.index + 3), c.k);
    if (g == 2) upcoming.right++;
    upcoming.index++;
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      writeCard(c);
      String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(now));
      d.execSQL("insert or ignore into activity values(?,?,0)", new Object[] {day, c.k});
      d.execSQL("update activity set n=n+1 where day=? and k=?", new Object[] {day, c.k});
      d.execSQL("insert into review_log(k,at,grade,prior,half,next_due,book) values(?,?,?,?,?,?,?)",
          new Object[] {c.k, now, g, prior, c.half, c.due, plan});
      JSONArray a = new JSONArray();
      for (double v : learned) a.put(v);
      put("theta", a);
      put("adjustments", updates);
      put("session", upcoming.json());
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
    cards.put(c.k, c);
    System.arraycopy(learned, 0, weights, 0, learned.length);
    adjustments = updates;
    session = upcoming;
    return c.due;
  }
}
