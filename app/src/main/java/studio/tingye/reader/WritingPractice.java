package studio.tingye.reader;

import android.content.Context;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured local writing support for short learner texts. */
final class WritingPractice {
  private static final String[] DAYS={"понедельник","вторник","среду","четверг","пятницу","субботу","воскресенье"};
  private static final Pattern DAY=Pattern.compile("(?:周|星期|礼拜)([一二三四五六日天])");
  static String answer(Context context,String task,String instruction,List<String> history,String input) throws IOException {
    if(task.equals("COMPOSE") && history.isEmpty() && input.matches(
        "(请)?(给|帮我给)老师写(一封)?俄文?(电子)?邮件[，,。 ]*(请问|问问|问)(周|星期|礼拜)[一二三四五六日天](能否|是否方便|可不可以)见面[。？?！!]?")) {
      Matcher day=DAY.matcher(input);
      if(day.find()){
        int i="一二三四五六日天".indexOf(day.group(1));i=Math.min(i,6);
        String when=(i==1?"во ":"в ")+DAYS[i];
        return "Здравствуйте!\n\nПодскажите, пожалуйста, удобно ли Вам будет встретиться "+when+
            "?\n\nЗаранее спасибо за ответ.\nС уважением,\n[Ваше имя]";
      }
    }
    if(task.equals("EXPAND")) {
      String prompt="Continue the learner's Russian paragraph. Write ONLY three connected sentences in Russian. Keep the original subject and tense. Add a relevant detail and a concluding observation. Example: Вчера я ходил в библиотеку. → Вчера я ходил в библиотеку. Там я выбирал книги для занятий. Особенно мне понравился сборник рассказов. /no_think";
      String result=LocalLanguageModel.chat(context,prompt,history,input);
      if(result.replace(input,"").trim().length()<12 || result.split("[.!?]+").length<2){
        String extra=LocalLanguageModel.complete(context,"Напиши два новых предложения, продолжающих текст. Не повторяй исходное предложение. Отвечай только по-русски. /no_think",input,120);
        if(extra.replace(input,"").trim().length()<12)throw new IOException("扩写没有产生新句子");
        result=input+" "+extra.replace(input,"").trim();
      }
      return result;
    }
    return LocalLanguageModel.chat(context,instruction,history,input);
  }
}
