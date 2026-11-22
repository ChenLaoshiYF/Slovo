package studio.tingye.reader;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

/** Forecast a fixed cohort; a hypothetical review resets elapsed time, never stored grades or strength. */
final class MemoryForecast {
  final List<StudyStore.Card> priority;
  final double[] unassisted, rehearsed, reference;
  final int rehearsedCount;
  final long at;

  MemoryForecast(List<StudyStore.Card> words, long at, int days, int sample) {
    this.at = at;
    priority = new ArrayList<>(words);
    priority.sort(Comparator.comparingDouble((StudyStore.Card c) -> probability(c, at))
        .thenComparingLong(c -> c.due).thenComparing(c -> c.k));
    rehearsedCount = Math.min(Math.max(0, sample), priority.size());
    unassisted = new double[days + 1];
    rehearsed = new double[days + 1];
    reference = new double[days + 1];
    double initialHalf = MemoryModel.half(MemoryModel.PRIOR, MemoryModel.features(0,0,5));
    for (int day = 0; day <= days; day++) reference[day] = MemoryModel.recall(day,initialHalf);
    for (int i = 0; i < priority.size(); i++) {
      StudyStore.Card card = priority.get(i);
      double start = probability(card, at);
      double decay = MemoryModel.recall(1, safeHalf(card));
      double measured = start, scenario = i < rehearsedCount ? 1 : start;
      for (int d = 0; d <= days; d++) {
        unassisted[d] += measured;
        rehearsed[d] += scenario;
        measured *= decay;
        scenario *= decay;
      }
    }
    if (priority.isEmpty()) return;
    for (int d = 0; d <= days; d++) {
      unassisted[d] /= priority.size();
      rehearsed[d] /= priority.size();
    }
  }

  private static double safeHalf(StudyStore.Card c) {
    return Double.isFinite(c.half) && c.half > 0 ? c.half : 2;
  }

  static double probability(StudyStore.Card c, long time) {
    return MemoryModel.recall((time - c.last) / MemoryModel.DAY, safeHalf(c));
  }

  static String percent(double fraction) {
    long rounded = Math.round(100 * fraction);
    if (rounded == 0 && fraction > 0) return "<1%";
    if (rounded == 100 && fraction < 1) return ">99%";
    return rounded + "%";
  }

  static long midnight(long anchor, int offset) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTimeInMillis(anchor);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    calendar.add(Calendar.DATE, offset);
    return calendar.getTimeInMillis();
  }

  /** Overdue words join day zero; every other word is counted exactly once. */
  static int[] dueDays(List<StudyStore.Card> words, long at) {
    int[] counts = new int[7];
    long[] limits = new long[7];
    for (int d = 0; d < limits.length; d++) limits[d] = midnight(at, d + 1);
    for (StudyStore.Card word : words) {
      for (int d = 0; d < counts.length; d++) {
        if (word.due < limits[d]) { counts[d]++; break; }
      }
    }
    return counts;
  }

  static int[][] budget(int[] demand, int capacity) {
    int[][] result = new int[demand.length][2];
    int carry = 0;
    for (int d = 0; d < demand.length; d++) {
      int total = carry + demand[d];
      result[d][0] = Math.min(total, capacity);
      carry = total - result[d][0];
      result[d][1] = carry;
    }
    return result;
  }
}
