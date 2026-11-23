package studio.tingye.reader;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repair direct numeral + inanimate noun pairs commonly encountered in beginner exercises. */
final class TranslationAgreement {
  private static final String[][] NOUNS={
      {"два","билета","билет","билеты"}, {"две","книги","книга","книги"},
      {"два","словаря","словарь","словари"}, {"два","места","место","места"},
      {"две","комнаты","комната","комнаты"}, {"два","рубля","рубль","рубли"},
      {"два","часа","час","часы"}, {"две","минуты","минута","минуты"}
  };
  private static final Pattern PAIR=Pattern.compile("(?iu)(?<![а-яё])(два|две|три|четыре)( +)([а-яё]+)(?![а-яё])");
  private static final Pattern TRAVEL=Pattern.compile("(我|我们)(想|不想|打算|计划)(明天|今天|下周)?去(莫斯科|圣彼得堡|俄罗斯|北京|上海)[。.!！]?");
  static String travel(String text){
    Matcher m=TRAVEL.matcher(text.trim());
    if(!m.matches())return null;
    boolean plural=m.group(1).equals("我们");
    String verb=m.group(2).contains("想")?(plural?"хотим":"хочу"):(plural?"планируем":"планирую");
    String city;
    switch(m.group(4)){
      case "莫斯科":city="Москву";break;
      case "圣彼得堡":city="Санкт-Петербург";break;
      case "俄罗斯":city="Россию";break;
      case "北京":city="Пекин";break;
      default:city="Шанхай";
    }
    String day=m.group(3);
    return (plural?"Мы ":"Я ")+(m.group(2).equals("不想")?"не ":"")+verb+" поехать в "+city+
        (day==null?"":day.equals("今天")?" сегодня":day.equals("明天")?" завтра":" на следующей неделе")+".";
  }

  static String correct(String text) {
    Matcher m=PAIR.matcher(text);
    StringBuffer out=new StringBuffer();
    while(m.find()){
      String noun=m.group(3).toLowerCase(Locale.ROOT);
      for(String[] row:NOUNS){
        if(!(noun.equals(row[1]) || noun.equals(row[2]) || noun.equals(row[3])))continue;
        String n=m.group(1).toLowerCase(Locale.ROOT);
        if(n.equals("два") || n.equals("две"))n=row[0];
        if(Character.isUpperCase(m.group(1).charAt(0)))n=Character.toUpperCase(n.charAt(0))+n.substring(1);
        m.appendReplacement(out,Matcher.quoteReplacement(n+m.group(2)+row[1]));
        break;
      }
    }
    return m.appendTail(out).toString();
  }
}
