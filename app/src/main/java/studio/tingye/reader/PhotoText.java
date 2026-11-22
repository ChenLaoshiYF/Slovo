package studio.tingye.reader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PhotoText {
  private static final Pattern LETTER = Pattern.compile(
      "[\\p{IsLatin}\\p{IsCyrillic}]+(?:[-'][\\p{IsLatin}\\p{IsCyrillic}]+)*");
  private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]+(?:[-'][A-Za-z]+)*");
  private static final Pattern RUSSIAN = Pattern.compile("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*");
  private static final Pattern RUSSIAN_FRAGMENT = Pattern.compile(
      "[А-Яа-яЁё]+(?:[- ,:;«»–—]+[А-Яа-яЁё]+)*[.!?]?");
  private static final Pattern SPACE = Pattern.compile("[ \\t]+");
  private static final Pattern HAN_SPACE = Pattern.compile("(?<=\\p{IsHan}) +(?=[\\p{IsHan}，。！？；：、])");

  final String text;
  final List<String> sentences = new ArrayList<>();
  final List<String> englishWords;
  final List<String> englishPhrases;
  final List<String> russianWords;

  PhotoText(String raw) {
    text = raw == null ? "" : raw.replace("\r\n","\n").replace('\r','\n').replace("\f","").trim();
    LinkedHashMap<String,String> english = new LinkedHashMap<>();
    LinkedHashMap<String,String> phrases = new LinkedHashMap<>();
    LinkedHashSet<String> russian = new LinkedHashSet<>();
    for (String row : text.split("\n")) {
      String line = HAN_SPACE.matcher(row.trim()).replaceAll("");
      if (line.isEmpty()) continue;
      for (String sentence : line.split("(?<=[.!?。！？])\\s+")) {
        if (!sentence.trim().isEmpty()) sentences.add(sentence.trim());
      }
      String stripped = normalized(row);
      Matcher word = LETTER.matcher(stripped);
      ArrayList<String> run = new ArrayList<>();
      int previousEnd = -1;
      while (word.find()) {
        String token = word.group();
        if (previousEnd < 0 || !SPACE.matcher(stripped.substring(previousEnd, word.start())).matches()) {
          addPhrases(run, phrases);
          run.clear();
        }
        if (ENGLISH.matcher(token).matches()) {
          english.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
          run.add(token);
        } else {
          addPhrases(run, phrases);
          run.clear();
          if (RUSSIAN.matcher(token).matches()) russian.add(token.toLowerCase(Locale.ROOT));
        }
        previousEnd = word.end();
      }
      addPhrases(run, phrases);
    }
    englishWords = new ArrayList<>(english.values());
    englishPhrases = new ArrayList<>(phrases.values());
    russianWords = new ArrayList<>(russian);
  }

  private static String normalized(String text) {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        .replace("́","").replace("\u00ad","").replace('‐','-').replace('‑','-').replace('’','\'');
  }

  private static void addPhrases(List<String> run, LinkedHashMap<String,String> out) {
    for (int at=0; at+1<run.size(); ) {
      int left = run.size()-at;
      int size = left==7 ? 4 : Math.min(left,6);
      String excerpt = String.join(" ",run.subList(at,at+size));
      out.putIfAbsent(excerpt.toLowerCase(Locale.ROOT),excerpt);
      at += size;
    }
  }

  static String russianSource(String sentence) {
    Matcher fragments = RUSSIAN_FRAGMENT.matcher(normalized(sentence));
    StringBuilder out = new StringBuilder();
    while (fragments.find()) {
      String piece = fragments.group();
      if (piece.length()<2) continue;
      if (out.length()>0) out.append(' ');
      out.append(piece);
    }
    return out.toString();
  }
}

