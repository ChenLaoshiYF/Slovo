package studio.tingye.reader;

/** A local half-life predictor. Time is measured in days, weights use natural logs. */
final class MemoryModel {
  static final double DAY = 86400000d, TARGET = .84, LN2 = Math.log(2);
  static final double[] PRIOR = {1.05, .78, -.42, -.12};

  static double clamp(double n, double lo, double hi) {
    return Math.max(lo, Math.min(hi, n));
  }

  static double[] features(int correct, int miss, int letters) {
    return new double[] {
      1, Math.log1p(correct), Math.log1p(miss), Math.min(2, Math.max(0, letters - 5) / 7.0)
    };
  }

  static double half(double[] weights, double[] x) {
    double dot = 0;
    for (int i = 0; i < x.length; i++) dot += weights[i] * x[i];
    return clamp(Math.exp(dot), .12, 450);
  }

  static double recall(double elapsed, double half) {
    return Math.pow(.5, Math.max(0, elapsed) / half);
  }

  static void update(
      double[] weights, double[] x, double elapsed, double observed, double effectiveHalf) {
    if (elapsed < 10.0 / 1440) return;
    double age = elapsed / effectiveHalf, p = recall(elapsed, effectiveHalf);
    double step = .16 * (observed - p) * p * LN2 * Math.min(age, 10);
    for (int i = 0; i < weights.length; i++)
      weights[i] =
          clamp(
              weights[i] + step * x[i] - .001 * (weights[i] - PRIOR[i]),
              PRIOR[i] - 1.4,
              PRIOR[i] + 1.4);
  }

  static long delay(double half, int grade) {
    if (grade == 0) return 8 * 60000L;
    if (grade == 1) return (long) clamp(half * .055 * DAY, 20 * 60000L, .5 * DAY);
    return (long) (clamp(-Math.log(TARGET) / LN2 * half, .25, 120) * DAY);
  }
}
