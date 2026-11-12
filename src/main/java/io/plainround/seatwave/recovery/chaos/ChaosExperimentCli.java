package io.plainround.seatwave.recovery.chaos;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One chaos experiment, "kill the writer of shard-37 during load", as a model run second by second,
 * with a written hypothesis, a steady state taken from Module 37's hold SLO, a blast radius and
 * abort conditions; then the same experiment when the hypothesis is wrong; then an experiment whose
 * abort conditions watch the wrong thing; then a game day sized so that Module 37's burn-rate pages
 * must fire. An experiment cannot be built without at least one abort condition. Takes no
 * arguments; every run is deterministic.
 */
public final class ChaosExperimentCli {

  // Steady load on a weekday (assumed: about 5 times the month's average of 19.3 holds a second
  // from Module 37's 50 million a month); experiments never run in a sale window (Module 35).
  private static final int HOLDS_PER_SECOND = 100;
  private static final int SHARDS = 64;

  // Module 37's hold SLO: 99.9 percent good, so a budget share of 0.001 and 50,000 bad holds a
  // month; fast burn 14.4 over 1 h and 5 min; slow burn 6 over 6 h and 30 min.
  private static final double BUDGET_SHARE = 0.001;
  private static final int MONTHLY_BUDGET_HOLDS = 50_000;
  private static final double FAST_BURN = 14.4;
  private static final double SLOW_BURN = 6;
  private static final int HOUR = 3_600;
  private static final int SIX_HOURS = 21_600;
  private static final int FIVE_MINUTES = 300;
  private static final int THIRTY_MINUTES = 1_800;

  // Mechanism inputs: an RDS Multi-AZ DB cluster fails over typically under 35 s (Module 34);
  // a pool that keeps connections to the old writer holds them for its assumed 30-minute maximum
  // lifetime; a rolling restart of seatwave takes 77 s (Module 33's model).
  private static final int FAILOVER_SECONDS = 35;
  private static final int STALE_POOL_SECONDS = 1_800;
  private static final int ROLLING_RESTART_SECONDS = 77;
  private static final int GAME_DAY_FAULT_SECONDS = 180;
  private static final int SIMULATED_SECONDS = 3_600;

  // Abort thresholds: the experiment group must be healthy again 60 s after the fault; at most
  // 1 percent of the month's budget (500 holds) may be spent by one experiment.
  private static final int RECOVERY_DEADLINE_SECONDS = 60;
  private static final int RECENT_WINDOW_SECONDS = 30;
  private static final double NOT_RECOVERED_ERROR_RATIO = 0.5;
  private static final double EXPERIMENT_BUDGET_SHARE = 0.01;
  private static final int PERCENT = 100;

  /** The conditions that stop an experiment, each read every second. */
  public enum AbortCondition {
    /** The experiment group's error ratio over 30 s is above 50 percent, 60 s after the fault. */
    EXPERIMENT_GROUP_NOT_RECOVERED,
    /** Module 37's hold fast-burn page fires. */
    FAST_BURN_PAGE,
    /** The experiment has spent more than 1 percent of the month's hold budget. */
    EXPERIMENT_BUDGET_SPENT,
    /** The booking counter finds an acknowledged confirm missing after failover. */
    ACKNOWLEDGED_CONFIRM_LOST,
    /** A staging game day stops if production's hold SLI moves: they share something after all. */
    PRODUCTION_SLI_MOVES
  }

  /**
   * A chaos experiment as written before it runs.
   *
   * @param name what is done
   * @param hypothesis what will stay true, in terms of the steady state
   * @param shardsAffected the blast radius, in shards out of 64
   * @param errorSeconds how long the experiment group fails if nothing stops it (the mechanism)
   * @param abortRecoverySeconds how long the abort action takes to end the failures
   * @param abortConditions at least one; the experiment is refused without
   */
  public record Experiment(
      String name,
      String hypothesis,
      int shardsAffected,
      int errorSeconds,
      int abortRecoverySeconds,
      List<AbortCondition> abortConditions) {
    /** Refuses an experiment with no abort condition or an impossible blast radius. */
    public Experiment {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(hypothesis, "hypothesis");
      if (shardsAffected < 1 || shardsAffected > SHARDS) {
        throw new IllegalArgumentException("blast radius must be 1 to " + SHARDS + " shards");
      }
      abortConditions = List.copyOf(abortConditions);
      // The module's invariant: no drill runs without abort conditions.
      if (abortConditions.isEmpty()) {
        throw new IllegalArgumentException("refused: '" + name + "' has no abort condition");
      }
    }
  }

  /** What the model saw. */
  private record Observed(
      int badHolds,
      int failingSeconds,
      int abortedAt,
      int fastPageAt,
      int slowPageAt,
      String abortedBy) {}

  private static final int NEVER = -1;

  private ChaosExperimentCli() {}

  /** Runs the four experiments and prints each with its verdict. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Runs the four experiments and the refused fifth, printing to {@code out}. */
  static void run(PrintStream out) {
    List<AbortCondition> fullSet = List.of(AbortCondition.values());
    String hypothesis =
        "holds on shard-37 are good again within 60 s, no acknowledged confirm is lost,"
            + " and the other 63 shards do not move";
    report(
        out,
        new Experiment(
            "1. kill shard-37's writer (the client reconnects)",
            hypothesis,
            1,
            FAILOVER_SECONDS,
            ROLLING_RESTART_SECONDS,
            fullSet),
        true);
    report(
        out,
        new Experiment(
            "2. the same, as the system really behaves: the pool keeps its old connections",
            hypothesis,
            1,
            STALE_POOL_SECONDS,
            ROLLING_RESTART_SECONDS,
            fullSet),
        true);
    report(
        out,
        new Experiment(
            "3. kill the writer of seatwave-db-a (32 shards), abort only on a lost confirm",
            "the same, for 32 shards",
            SHARDS / 2,
            STALE_POOL_SECONDS,
            ROLLING_RESTART_SECONDS,
            List.of(AbortCondition.ACKNOWLEDGED_CONFIRM_LOST)),
        true);
    report(
        out,
        new Experiment(
            "4. game day in staging: a fault script blocks every shard's database port for 180 s",
            "the hold fast-burn page fires within 60 s and the slow-burn page within 150 s",
            SHARDS,
            GAME_DAY_FAULT_SECONDS,
            0,
            List.of(AbortCondition.PRODUCTION_SLI_MOVES)),
        false);
    try {
      new Experiment(
          "5. kill a writer with no abort conditions",
          hypothesis,
          1,
          FAILOVER_SECONDS,
          ROLLING_RESTART_SECONDS,
          List.of());
    } catch (IllegalArgumentException refused) {
      out.println("\n" + refused.getMessage());
    }
  }

  /**
   * Runs {@code experiment} in the model and prints its steady state, abort, budget, pages and
   * verdict. A production experiment is confirmed if its group recovers within 60 s; a game day if
   * both pages fire in time.
   */
  public static void report(PrintStream out, Experiment experiment, boolean isProduction) {
    Observed result = simulate(experiment, isProduction);
    double groupHoldsPerSecond = HOLDS_PER_SECOND * experiment.shardsAffected() / (double) SHARDS;
    out.printf(Locale.ROOT, "%n%s%n  hypothesis: %s%n", experiment.name(), experiment.hypothesis());
    out.printf(
        Locale.ROOT,
        "  steady state: hold SLI (not 5xx and <= 300 ms), %d holds/s; blast radius %d of %d shards"
            + " (%.1f holds/s)%n",
        HOLDS_PER_SECOND,
        experiment.shardsAffected(),
        SHARDS,
        groupHoldsPerSecond);
    out.println("  abort conditions: " + experiment.abortConditions());
    out.printf(
        Locale.ROOT,
        "  experiment group failing for %d s after the fault; aborted %s%n",
        result.failingSeconds(),
        result.abortedAt() == NEVER
            ? "never"
            : "at " + result.abortedAt() + " s by " + result.abortedBy());
    out.printf(
        Locale.ROOT,
        "  bad holds %,d = %.2f%% of %s month's budget; %s%n",
        result.badHolds(),
        result.badHolds() * (double) PERCENT / MONTHLY_BUDGET_HOLDS,
        isProduction ? "production's" : "staging's (same SLO)",
        experiment.shardsAffected() == SHARDS
            ? "no control group (every shard)"
            : "control group bad holds 0");
    out.printf(
        Locale.ROOT,
        "  fast-burn page: %s; slow-burn page: %s%n",
        result.fastPageAt() == NEVER ? "did not fire" : "fired at " + result.fastPageAt() + " s",
        result.slowPageAt() == NEVER ? "did not fire" : "fired at " + result.slowPageAt() + " s");
    boolean confirmed =
        isProduction
            ? result.failingSeconds() <= RECOVERY_DEADLINE_SECONDS
            : result.fastPageAt() != NEVER
                && result.fastPageAt() <= RECOVERY_DEADLINE_SECONDS
                && result.slowPageAt() != NEVER
                && result.slowPageAt() <= GAME_DAY_FAULT_SECONDS - RECOVERY_DEADLINE_SECONDS / 2;
    out.println("  verdict: hypothesis " + (confirmed ? "CONFIRMED" : "REFUTED"));
  }

  /** Simulates one experiment second by second, the fault starting at second 0. */
  private static Observed simulate(Experiment experiment, boolean isProduction) {
    double groupRate = HOLDS_PER_SECOND * experiment.shardsAffected() / (double) SHARDS;
    List<Double> badPerSecond = new ArrayList<>();
    int abortedAt = NEVER;
    String abortedBy = "nothing";
    int failingUntil = experiment.errorSeconds();
    int fastPageAt = NEVER;
    int slowPageAt = NEVER;
    double badTotal = 0;
    for (int second = 0; second < SIMULATED_SECONDS; second++) {
      double bad = second < failingUntil ? groupRate : 0;
      badPerSecond.add(bad);
      badTotal += bad;
      // The windows were full of good holds before the fault (6 h of steady load), so a window's
      // bad share is the bad holds in it over the holds it covers.
      if (fastPageAt == NEVER
          && share(badPerSecond, HOUR) > FAST_BURN * BUDGET_SHARE
          && share(badPerSecond, FIVE_MINUTES) > FAST_BURN * BUDGET_SHARE) {
        fastPageAt = second;
      }
      if (slowPageAt == NEVER
          && share(badPerSecond, SIX_HOURS) > SLOW_BURN * BUDGET_SHARE
          && share(badPerSecond, THIRTY_MINUTES) > SLOW_BURN * BUDGET_SHARE) {
        slowPageAt = second;
      }
      if (abortedAt == NEVER) {
        Optional<AbortCondition> tripped =
            tripped(experiment, badPerSecond, badTotal, second, fastPageAt);
        if (tripped.isPresent()) {
          abortedAt = second;
          abortedBy = tripped.get().name();
          failingUntil = Math.min(failingUntil, second + experiment.abortRecoverySeconds());
        }
      }
    }
    int failingSeconds = Math.min(failingUntil, SIMULATED_SECONDS);
    return new Observed(
        (int) Math.round(badTotal), failingSeconds, abortedAt, fastPageAt, slowPageAt, abortedBy);
  }

  private static Optional<AbortCondition> tripped(
      Experiment experiment,
      List<Double> badPerSecond,
      double badTotal,
      int second,
      int fastPageAt) {
    double groupRate = HOLDS_PER_SECOND * experiment.shardsAffected() / (double) SHARDS;
    for (AbortCondition condition : experiment.abortConditions()) {
      boolean isTripped =
          switch (condition) {
            case EXPERIMENT_GROUP_NOT_RECOVERED ->
                second > RECOVERY_DEADLINE_SECONDS
                    && recentBad(badPerSecond) / (groupRate * RECENT_WINDOW_SECONDS)
                        > NOT_RECOVERED_ERROR_RATIO;
            case FAST_BURN_PAGE -> fastPageAt != NEVER;
            case EXPERIMENT_BUDGET_SPENT ->
                badTotal > EXPERIMENT_BUDGET_SHARE * MONTHLY_BUDGET_HOLDS;
            // The Multi-AZ cluster acknowledges a commit only once a reader in another zone holds
            // it
            // (Module 34), so the counter never finds one missing in this model.
            case ACKNOWLEDGED_CONFIRM_LOST -> false;
            // Staging and production share no database in Module 35's layout; the model keeps it
            // so.
            case PRODUCTION_SLI_MOVES -> false;
          };
      if (isTripped) {
        return Optional.of(condition);
      }
    }
    return Optional.empty();
  }

  private static double recentBad(List<Double> badPerSecond) {
    return badPerSecond
        .subList(Math.max(0, badPerSecond.size() - RECENT_WINDOW_SECONDS), badPerSecond.size())
        .stream()
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  private static double share(List<Double> badPerSecond, int windowSeconds) {
    double bad =
        badPerSecond
            .subList(Math.max(0, badPerSecond.size() - windowSeconds), badPerSecond.size())
            .stream()
            .mapToDouble(Double::doubleValue)
            .sum();
    return bad / ((double) HOLDS_PER_SECOND * windowSeconds);
  }
}
