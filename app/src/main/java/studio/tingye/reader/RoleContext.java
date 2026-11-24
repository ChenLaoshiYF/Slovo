package studio.tingye.reader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Builds each prompt exclusively from the active practice room. */
final class RoleContext {
  static String instruction(String role,List<String> turns){
    String persona;
    switch(role){
      case "CAFE":persona="Вы бариста. Примите заказ и уточните предпочтения.";break;
      case "STATION":persona="Вы кассир на вокзале. Помогите выбрать маршрут, дату и билет.";break;
      case "STUDENT":persona="Ты российский однокурсник. Поговори с собеседником о его интересах.";break;
      default:persona="Вы преподаватель русского языка. Объясните вопрос ученика на простом примере.";
    }
    StringBuilder prompt=new StringBuilder(persona)
        .append(" Ответь на последнее сообщение по-русски, даже если оно на китайском. ")
        .append("Продолжи тему, а затем задай один короткий вопрос. Не начинай диалог заново. ");
    // Older learner utterances supply details that fall outside the raw three-pair window.
    LinkedHashSet<String> older=new LinkedHashSet<>();
    for(int i=turns.size()-8;i>=0 && older.size()<3;i-=2){
      String line=turns.get(i).trim();
      if(!line.isEmpty())older.add(line.substring(0,line.offsetByCodePoints(0,Math.min(60,line.codePointCount(0,line.length())))));
    }
    if(!older.isEmpty()){
      List<String> chronological=new ArrayList<>(older);
      java.util.Collections.reverse(chronological);
      prompt.append("\n这个角色较早收到的用户原话：").append(String.join(" / ",chronological));
    }
    return prompt.append(" /no_think").toString();
  }

  static String lesson(String role,List<String> turns,String message){
    String text=message.trim().toLowerCase(Locale.ROOT);
    // A short answer such as “明天” only has meaning in the context of the preceding question.
    if(role.equals("TUTOR") && (text.contains("过去时") || text.contains("прошедшее время")))
      return "В прошедшем времени важно, кто выполнял действие: он читал, она читала, они читали. Попробуй закончить: «Вчера Анна … книгу».";
    if(!turns.isEmpty()){
      String previous=turns.get(turns.size()-1);
      if(role.equals("STATION") && previous.contains("дату") && text.matches("(明天|明天的|завтра)[。.!！]?")){
        if(previous.contains("Москвы"))return "Хорошо, билет до Москвы на завтра. В одну сторону или туда и обратно?";
        return "Хорошо, на завтра. В одну сторону или туда и обратно?";
      }
      if(role.equals("STATION") && previous.contains("обратно") && text.matches("(单程|往返|往返票|单程票)[。！!]?"))
        return text.startsWith("单程")?"В одну сторону. Сколько билетов вам нужно?":"Туда и обратно. На какую дату нужен обратный билет?";
      if(role.equals("CAFE") && (previous.contains("собой") || previous.contains("здесь"))){
        if(text.matches("(带走|打包|带走吧|打包带走)[。！!]?"))return "С собой, хорошо. Что-нибудь ещё?";
        if(text.matches("(在这喝|在店里喝|在这里喝|堂食)[。！!]?"))return "Хорошо, здесь. Хотите что-нибудь к напитку?";
      }
      return "";
    }
    if(text.contains("天气") || text.contains("喜欢") || text.contains("不加糖") && text.contains("茶"))return "";
    return TutorGuide.answer(role,message);
  }

  static String russianFirst(String raw){
    List<String> russian=new ArrayList<>();
    String explanation="";
    for(String line:raw.trim().replace("**","").split("[\\r\\n]+")){
      String clean=line.trim();
      long ru=clean.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.CYRILLIC).count();
      long han=clean.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).count();
      if(ru>=3 && ru>han*2 && (han==0 || clean.matches("^[А-Яа-яЁё«„\\\"].*")))russian.add(clean);
      else if(han>0 && explanation.isEmpty() && clean.length()<=80)explanation=clean;
    }
    if(russian.isEmpty())return "";
    String answer=String.join("\n",russian);
    return explanation.isEmpty()?answer:answer+"\n"+explanation;
  }
}
