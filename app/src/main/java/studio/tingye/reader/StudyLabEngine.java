package studio.tingye.reader;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Question construction is independent of Activity state and repeatable for a saved round. */
final class StudyLabEngine {
  enum Mode {
    LISTEN("听音辨义","听俄语录音，找中文意思",0),
    DICT("单词听写","听录音，把单词写出来",0),
    STRESS("重音定位","找出重读的那个元音",0),
    LETTERS("拼字拼图","看中文，用字母拼词",0),
    NEAR("易混词辨析","从拼写相近的词里辨认词义",1),
    LEMMA("词形还原","从变格、变位找到词典原形",1),
    INFLECT("词形填空","照提示写出指定的词形",1),
    ASPECT("动词体","用句子的语境判断动作的体",1),
    CLOZE("例句补词","把原句空出来的词补回去",2),
    ORDER("原句还原","照中文提示排回书中的句子",2),
    PREP("介词搭配","看名词的格，判断介词",2),
    CLOCK("读钟报时","把电子钟的时间用俄语读出来",2);
    final String label, description;
    final int group;
    Mode(String label,String description,int group) {
      this.label=label; this.description=description; this.group=group;
    }
  }

  enum Input { CHOICE, WRITE, LETTERS, WORDS }

  static final class Entry {
    final String id, ru, zh, stress, pos, example, translation;
    final String[] senses;
    final String[][] forms;
    Entry(String id,String ru,String zh,String pos,String stress,String forms,String example,String translation) {
      this.id=id; this.ru=ru; this.zh=zh; this.pos=pos;
      this.senses=senses(zh);
      this.stress=stress; this.example=example; this.translation=translation;
      ArrayList<String[]> parsed=new ArrayList<>();
      for (String item:forms.split("·")) {
        String[] pair=item.trim().split("[\\s　]+",2);
        if (pair.length==2 && pair[1].matches("[а-яА-ЯёЁ́-]+")) parsed.add(pair);
      }
      this.forms=parsed.toArray(new String[0][]);
    }
  }

  static final class Question {
    final Input input;
    final String prompt, material, answer, reason, audio;
    final List<String> choices;
    Question(Input input,String prompt,String material,String answer,String reason,String audio,List<String> choices) {
      this.input=input; this.prompt=prompt; this.material=material; this.answer=answer;
      this.reason=reason; this.audio=audio; this.choices=choices;
    }
    boolean accepts(String typed) {
      return input==Input.CHOICE ? answer.equals(typed) : normalize(answer).equals(normalize(typed));
    }
  }

  // Attested surface word in each of the 30 bilingual example sentences shipped with the app.
  private static final String[] TOKENS = {
      "город:городе","ребенок:Ребёнок","улица:улице","книга:книгу","возможность:возможность",
      "общество:общество","причина:причину","решение:решение","класс:классе","развитие:развитие",
      "площадь:площади","учитель:Учитель","студент:Студент","билет:билет","образование:образование",
      "ученик:Ученик","университет:университете","вокзал:вокзала","исследование:Исследование",
      "метро:метро","последствие:Последствия","доказательство:доказательства","лекция:лекции",
      "школьник:Школьник","противоречие:противоречие","аудитория:аудитория","словарь:словаре",
      "закономерность:закономерность","предпосылка:предпосылка","родители:родители"};
  // Prompt, answer, two contrastive distractors and a short explanation.
  private static final String[][] PREPOSITIONS = {
      {"Мы едем ___ Москву.","в","из","у","去莫斯科用 в + 第四格，表示目的地。"},
      {"Я приехал ___ Москвы.","из","к","в","从莫斯科来用 из + 第二格。"},
      {"Письмо лежит ___ столе.","на","к","с","在桌面上用 на + 第六格。"},
      {"Я иду ___ врачу.","к","от","о","去医生那里用 к + 第三格。"},
      {"Мы говорили ___ музыке.","о","из","через","говорить о + 第六格，表示谈论的话题。"},
      {"Я пишу карандашом ___ линейки.","без","по","перед","без + 第二格，表示没有某物。"},
      {"Мы гуляем ___ другом.","с","от","к","с + 第五格，表示和某人一起。"},
      {"Она вернулась ___ работы.","с","к","на","从工作地点回来用 с работы，与 на работу 相对。"},
      {"Он вышел ___ комнаты.","из","на","к","从房间内出来用 из + 第二格。"},
      {"Мы подошли ___ окну.","к","от","в","подойти к + 第三格，表示走近某物。"}
  };
  private static final String[][] ASPECTS = {
      {"Каждый день я ___ книгу перед сном.","читаю","прочитаю","прочитал","每天反复发生的动作，这里用未完成体 читаю。"},
      {"Наконец я ___ письмо и положил его в конверт.","написал","пишу","буду писать","信已经写好，接着装入信封。完成体 написал 表示取得结果。"},
      {"Вчера весь вечер мы ___ фильм.","смотрели","посмотрим","посмотрели","весь вечер 强调观看的过程，选未完成体 смотрели。"},
      {"Каждое утро она ___ окно.","открывает","откроет","открыла","每天的习惯动作，用未完成体 открывает。"},
      {"Я уже ___ задачу. Вот ответ.","решил","решаю","буду решать","答案已经得出，用完成体 решил。"},
      {"Раньше мы часто ___ письма друг другу.","писали","напишем","написали","раньше и часто 指过去反复写信，用 писали。"},
      {"Вчера поезд наконец ___ на станцию. Пассажиры вышли на платформу.","прибыл","прибывает","будет прибывать","вчера 指明过去时间，乘客随后下了车。已完成的一次抵达用 прибыл。"},
      {"Завтра к вечеру я ___ перевод целиком.","закончу","заканчивал","заканчиваю сейчас","有截止时间、要求全部做完，用完成体将来时 закончу。"}
  };
  private static final int[] TIMES = {60,65,71,82,121,125,135,180,224,267,310,375,452,509,550,601,660,725,731,787,840,918,1022,1261,1322,1435,5,30};
  private static final String[] NUM = {"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять","десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать","семнадцать","восемнадцать","девятнадцать"};
  private static final String[] TENS = {"","","двадцать","тридцать","сорок","пятьдесят"};
  private static final String VOWELS="аеёиоуыэюя";
  private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]+");
  private static final Map<String,Integer> RELATED=new HashMap<>();
  static {
    String[] groups={"老师 教师 任课教师","学生 中小学生 大学生","学习 研习 研究","信息 消息 新闻",
        "因此 因而","因为 由于","意见 看法","例子 事例","方法 手段","结果 后果",
        "性质 特征","论据 证据","假说 假设","装置 设备","情形 情况","变化 转变",
        "解释 讲解","理念 概念","考试 考查","路途 道路","小组 团队","练习 课业"};
    for(int i=0;i<groups.length;i++)for(String sense:groups[i].split(" "))RELATED.put(sense,i);
  }
  static boolean overlappingMeanings(Entry a,Entry b) {
    return overlappingMeanings(a.senses,b.senses);
  }
  static String[] senses(String zh) { return zh.trim().split("\\s*[；;，、/／]\\s*"); }
  static boolean overlappingMeanings(String[] a,String[] b) {
    for(String left:a)for(String right:b) {
      if(left.equals(right) || (left.length()>=2 && right.length()>=2 &&
          (left.contains(right) || right.contains(left))))return true;
      Integer group=RELATED.get(left);
      if(group!=null && group.equals(RELATED.get(right)))return true;
    }
    return false;
  }
  final Map<String,Entry> words=new LinkedHashMap<>();
  final EnumMap<Mode,List<String>> pools=new EnumMap<>(Mode.class);
  private final Map<String,String> tokens=new HashMap<>();
  StudyLabEngine(List<Entry> entries) {
    for (String link:TOKENS) {String[] pair=link.split(":");tokens.put(pair[0],pair[1]);}
    for (Mode mode:Mode.values()) pools.put(mode,new ArrayList<>());
    for (Entry e:entries) {
      if (e.ru.isEmpty() || e.zh.isEmpty()) continue;
      words.put(e.id,e);
      for (Mode mode:new Mode[]{Mode.LISTEN,Mode.DICT,Mode.NEAR}) pools.get(mode).add(e.id);
      if (CYRILLIC.matcher(e.ru).matches() && e.ru.length()>=3 && e.ru.length()<=12 &&
          new HashSet<>(letters(e.ru)).size()>1) pools.get(Mode.LETTERS).add(e.id);
      if (stressChoices(e).size()>1) pools.get(Mode.STRESS).add(e.id);
      if (!changedForms(e).isEmpty()) {
        pools.get(Mode.LEMMA).add(e.id); pools.get(Mode.INFLECT).add(e.id);
      }
      if (!e.example.isEmpty() && !e.translation.isEmpty()) {
        pools.get(Mode.ORDER).add(e.id);
        String token=tokens.get(e.id);
        if (token!=null && masked(e.example,token)!=null) pools.get(Mode.CLOZE).add(e.id);
      }
    }
    for (int i=0;i<ASPECTS.length;i++) pools.get(Mode.ASPECT).add(Integer.toString(i));
    for (int i=0;i<PREPOSITIONS.length;i++) pools.get(Mode.PREP).add(Integer.toString(i));
    for (int i:TIMES) pools.get(Mode.CLOCK).add(Integer.toString(i));
  }

  static String normalize(String s) {
    return s.trim().toLowerCase(Locale.ROOT).replace("́","").replace('ё','е').replaceAll("\\s+"," ");
  }
  private static String plain(String s) {return s.replace("́","");}
  private static List<String> letters(String s) {
    List<String> result=new ArrayList<>(s.length());
    for (int i=0;i<s.length();i++) result.add(s.substring(i,i+1));
    return result;
  }
  private static String masked(String text,String token) {
    Matcher match=CYRILLIC.matcher(text);
    while(match.find()) if (match.group().equalsIgnoreCase(token))
      return text.substring(0,match.start())+"____"+text.substring(match.end());
    return null;
  }
  private static List<String[]> changedForms(Entry e) {
    List<String[]> out=new ArrayList<>();
    for (String[] form:e.forms) if (!normalize(form[1]).equals(normalize(e.ru))) out.add(form);
    return out;
  }
  static List<String> stressChoices(Entry e) {
    String ru=plain(e.stress);
    List<String> out=new ArrayList<>();
    // A printed ё already locates the stress and cannot be a meaningful distractor here.
    if (!e.stress.contains("́") || ru.contains("ё")) return out;
    for (int i=0;i<ru.length();i++) if(VOWELS.indexOf(ru.charAt(i))>=0)
      out.add(ru.substring(0,i+1)+"́"+ru.substring(i+1));
    return out;
  }
  static int distance(String a,String b) {
    int[] prev=new int[b.length()+1], next=new int[b.length()+1];
    for(int j=0;j<=b.length();j++) prev[j]=j;
    for(int i=1;i<=a.length();i++) {
      next[0]=i;
      for(int j=1;j<=b.length();j++) next[j]=Math.min(Math.min(prev[j]+1,next[j-1]+1),
          prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
      int[] swap=next;next=prev;prev=swap;
    }
    return prev[b.length()];
  }
  // Estimated error rate with a beta prior. Previously missed items grow in priority with time.
  static double priority(int tries,int errors,boolean pending,long ageMillis,double recall,boolean inBook,double jitter) {
    double error=(errors+1.0)/(tries+3.0);
    double gap=Math.min(1,Math.max(0,ageMillis)/86400000.0);
    return 1.8*error+(pending?1.5:0)+.7*(1-recall)+(inBook?.65:0)
        +(tries==0?.55:gap-.95)+jitter*.32;
  }
  static String spellingHelp(String guess,String answer) {
    String typed=normalize(guess), target=normalize(answer);
    int i=0;
    while(i<typed.length() && i<target.length() && typed.charAt(i)==target.charAt(i)) i++;
    if (i==typed.length() && i<target.length()) return "后面还差「"+target.substring(i)+"」。";
    if (i==target.length() && i<typed.length()) return "末尾多写了「"+typed.substring(i)+"」。";
    return i<target.length() ? "从第 "+(i+1)+" 个字母检查，这里应写「"+target.charAt(i)+"」。" : "";
  }
  private static List<String> shuffled(List<String> input,Random rnd) {
    List<String> result=new ArrayList<>(input);
    Collections.shuffle(result,rnd);
    if (result.size()>1 && result.equals(input)) Collections.rotate(result,1);
    return result;
  }
  private List<String> contrasts(Entry target,boolean similar,boolean chinese,Random random) {
    List<Entry> candidates=new ArrayList<>(words.values());
    Collections.shuffle(candidates,random);
    if(similar) {
      Map<String,Integer> distances=new HashMap<>();
      for(Entry e:candidates) distances.put(e.id,4*distance(target.id,e.id)+(e.pos.equals(target.pos)?0:1));
      candidates.sort(Comparator.comparingInt(e -> distances.get(e.id)));
    }
    List<Entry> selected=new ArrayList<>();selected.add(target);
    List<String> values=new ArrayList<>();values.add(chinese?target.zh:target.ru);
    for(Entry e:candidates) {
      boolean overlaps=false;
      for(Entry prior:selected) if(overlappingMeanings(prior,e)){overlaps=true;break;}
      if(!overlaps){selected.add(e);values.add(chinese?e.zh:e.ru);}
      if(values.size()==3) break;
    }
    return shuffled(values,random);
  }
  Question question(Mode mode,String id,long seed) {
    Random rnd=new Random(seed ^ id.hashCode() ^ (mode.ordinal()*104729L));
    if(mode==Mode.PREP || mode==Mode.ASPECT) {
      String[] a=(mode==Mode.PREP?PREPOSITIONS:ASPECTS)[Integer.parseInt(id)];
      return new Question(Input.CHOICE,mode==Mode.PREP?"这里填哪个介词？":"这里用哪种动词形式？",a[0],a[1],a[4],"",
          shuffled(Arrays.asList(a[1],a[2],a[3]),rnd));
    }
    if(mode==Mode.CLOCK) {
      int t=Integer.parseInt(id);String answer=clock(t);
      return new Question(Input.CHOICE,"用俄语读出时间",String.format(Locale.ROOT,"%02d:%02d",t/60,t%60),
          answer,"按二十四小时制读数。小时用 час，分钟用 минута，各自随数词变化。","",
          shuffled(Arrays.asList(answer,clock((t+60)%1440),clock((t+3)%1440)),rnd));
    }
    Entry e=words.get(id);
    switch (mode) {
      case LISTEN: return new Question(Input.CHOICE,"听到的是哪个意思？","",e.zh,e.stress+"　·　"+e.zh,e.ru,contrasts(e,false,true,rnd));
      case DICT: return new Question(Input.WRITE,"听完录音，写下这个词","",e.ru,e.stress+"　·　"+e.zh,e.ru,Collections.emptyList());
      case STRESS:
        List<String> options=stressChoices(e);
        String answer=e.stress;
        options.remove(answer); Collections.shuffle(options,rnd);
        List<String> stress=new ArrayList<>(options.subList(0,Math.min(3,options.size())));stress.add(answer);
        return new Question(Input.CHOICE,"重音落在哪个元音上？",e.ru+"　·　"+e.zh,answer,
            "再读一遍："+answer+"。","",shuffled(stress,rnd));
      case LETTERS: return new Question(Input.LETTERS,"按顺序点字母",e.zh,e.ru,e.stress+"　·　"+e.zh,"",shuffled(letters(e.ru),rnd));
      case NEAR: return new Question(Input.CHOICE,"哪个俄语词是这个意思？",e.zh,e.ru,e.stress+"　·　"+e.zh,"",contrasts(e,true,false,rnd));
      case LEMMA:
      case INFLECT:
        List<String[]> forms=changedForms(e);String[] form=forms.get(rnd.nextInt(forms.size()));
        String clue=e.stress+" → "+form[1]+"（"+form[0]+"）";
        if (mode==Mode.LEMMA) return new Question(Input.CHOICE,"找出这个词的原形",form[1]+"　·　"+form[0],e.ru,
            clue,"",contrasts(e,true,false,rnd));
        return new Question(Input.WRITE,"写出「"+form[0]+"」的形式",e.stress+"　·　"+e.zh,plain(form[1]),clue,"",Collections.emptyList());
      case CLOZE:
        String token=tokens.get(id);
        return new Question(Input.WRITE,"用「"+e.ru+"」的适当形式补出原句",masked(e.example,token)+"\n"+e.translation,
            token,e.example,"",Collections.emptyList());
      case ORDER: return new Question(Input.WORDS,"按原句顺序点选词块",e.translation,e.example,
          e.example,"",shuffled(Arrays.asList(e.example.split("\\s+")),rnd));
      default: throw new IllegalArgumentException(mode.name());
    }
  }

  private static String number(int n,boolean feminine) {
    if(n>=20 && n%10!=0) return TENS[n/10]+" "+number(n%10,feminine);
    if(n>=20) return TENS[n/10];
    if(feminine && n==1) return "одна";
    if(feminine && n==2) return "две";
    return NUM[n];
  }
  private static String counted(int n,boolean minute) {
    int last=n%10;
    int form=n%100>=11 && n%100<=14 ? 2 : last==1 ? 0 : last>=2 && last<=4 ? 1 : 2;
    String[] forms=minute ? new String[]{"минута","минуты","минут"} : new String[]{"час","часа","часов"};
    return number(n,minute)+" "+forms[form];
  }
  static String clock(int time) {
    int minutes=time%60;
    return counted(time/60,false)+(minutes==0?" ровно":" "+counted(minutes,true));
  }
}
