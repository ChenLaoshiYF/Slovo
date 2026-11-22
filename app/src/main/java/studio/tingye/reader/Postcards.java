package studio.tingye.reader;

import java.util.*;

final class Postcards {
  static final int[] VERSE_PLACE = {2, 3, 0, 1, 2};
  static final String[][] VERSE = {
    {"День веселья, верь, настанет.", "相信吧，欢欣的日子总会到来。", "普希金《假如生活欺骗了你》"},
    {"Я помню чудное мгновенье…", "我记得那美妙的一瞬……", "普希金《致凯恩》"},
    {"Мороз и солнце; день чудесный!", "霜与阳光，多美的一天！", "普希金《冬晨》"},
    {"Люблю грозу в начале мая,", "我爱五月初的雷雨，", "丘特切夫《春雷》"},
    {"Есть в осени первоначальной короткая, но дивная пора…", "初秋有一段短暂却奇妙的时光……", "丘特切夫《初秋》"}
  };
  static final String[][] LEADS = {
    {"Сегодня можно идти медленнее.", "今天可以走慢一点。"},
    {"Одно слово уже нашло своё место.", "已经有一个词找到了自己的位置。"},
    {"Знакомое слово можно встретить заново.", "熟悉的词，也可以再认识一次。"},
    {"Ты снова здесь.", "你又回到了这里。"}
  };
  static final String[][] ENDS = {
    {"Остальное подождёт.", "其余的事可以等一等。"},
    {"Пусть вечер будет тихим.", "愿今晚安安静静。"},
    {"Оставь немного места для радости.", "给开心的事留一点位置。"},
    {"Даже маленький шаг — это движение.", "短短的一步，也是向前。"},
    {"Завтра будет ещё одна страница.", "明天还会有新的一页。"}
  };

  static String[] entry(boolean personal, int number, StudyStore store) {
    if (!personal) return VERSE[Math.floorMod(number, VERSE.length)];
    int seen = 0, repeated = 0;
    StudyStore.Card recent = null;
    for (StudyStore.Card c : store.cards.values()) {
      if (c.seen > 0) {
        seen++;
        if (recent == null || c.last > recent.last) recent = c;
      }
      if (c.miss > 0) repeated++;
    }
    int choices = seen == 0 ? 0 : store.todayCount() == 0 ? 3 : repeated > 0 ? 2 : 1;
    String[] a = LEADS[choices], b = ENDS[Math.floorMod(number, ENDS.length)];
    if (recent != null && recent.last >= StudyStore.midnight() && Math.floorMod(number, 3) != 0)
      return new String[] {
        "Сегодня тебе встретилось слово «" + recent.ru + "». " + b[0],
        "今天，你和「" + recent.ru + "」打过照面了。" + b[1],
        "词汐 · 写给你"
      };
    return new String[] {a[0] + " " + b[0], a[1] + b[1], "词汐 · 写给你"};
  }
}
