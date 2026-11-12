package io.plainround.seatwave.recovery.objectives;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Computes the recovery point and the recovery time of SeatWave's bookings database after the loss
 * of {@code region-a}, for the four disaster recovery tiers, from the mechanism of each: the RPO
 * from how the log reaches {@code region-b}, the RTO as detect + decide + provision + restore +
 * replay + verify + redirect, every term from a named input. It sets each tier against its monthly
 * cost (Module 34's prices) and the objective the business set (Section 7: RPO 5 minutes, RTO 60
 * minutes), and recomputes Module 34's break-even with the computed times instead of assumed ones.
 * Takes no arguments.
 */
public final class RecoveryObjectivesCli {

  // Detect: Module 37's fast-burn page fired 20 s after its fault (model); acknowledging it took
  // an assumed 3 minutes in Module 37's postmortem.
  private static final long PAGE_SECONDS = 20;
  private static final long ACKNOWLEDGE_SECONDS = 180;

  // Decide: confirm that the region, not a zone, is impaired, and declare (assumed).
  private static final long DECLARE_SECONDS = 600;

  // Provision in region-b from the environment's code (Module 35), assumed until a drill times it.
  private static final long REBUILD_ENVIRONMENT_SECONDS = 1_800;
  private static final long SCALE_STANDBY_NODES_SECONDS = 480;
  private static final long PROMOTE_REPLICAS_SECONDS = 300;
  private static final long PROMOTE_HOME_REGION_SECONDS = 120;

  // Restore and replay: Module 34's 400 GB is two clusters of 200 GB, restored in parallel, one
  // stream each at Module 18's assumed 250 MB/s. Log: Module 34's 20 GB a day, 10 GB per cluster;
  // with a daily base backup a restore may replay a whole day. Replay at 3,000 IOPS x 8 kB pages
  // (Module 1's volume, every record touching an uncached page): 24 MB/s, a worst case.
  private static final long CLUSTER_MEGABYTES = 200_000;
  private static final long STREAM_MEGABYTES_PER_SECOND = 250;
  private static final long LOG_MEGABYTES_PER_CLUSTER_DAY = 10_000;
  private static final long VOLUME_IOPS = 3_000;
  private static final long PAGE_KILOBYTES = 8;
  private static final long KILOBYTES_PER_MEGABYTE = 1_000;

  // Verify: the checker reads the restored cluster once (counts and checksums), at the same
  // throughput; a promoted replica was checked continuously by replication, so only the counts.
  private static final long VERIFY_REPLICA_SECONDS = 120;
  private static final long VERIFY_HOME_REGION_SECONDS = 60;

  // Redirect: Module 6's failover, health check every 10 s with a threshold of 3, plus a 60 s TTL.
  private static final long REDIRECT_SECONDS = 90;

  // RPO inputs: RDS uploads transaction logs to S3 every five minutes (RDS documentation, checked
  // 11 November 2026); copying them to region-b takes an assumed 5 more minutes; a cross-region
  // replica is behind by Module 34's assumed 100 ms of lag.
  private static final long LOG_UPLOAD_INTERVAL_MILLIS = 300_000;
  private static final long CROSS_REGION_COPY_MILLIS = 300_000;
  private static final long REPLICA_LAG_MILLIS = 100;

  // The objective for bookings, set with the CFO (Section 7), and the peak rate it protects.
  private static final long OBJECTIVE_RPO_MILLIS = 300_000;
  private static final long OBJECTIVE_RTO_SECONDS = 3_600;
  private static final long PEAK_BOOKINGS_PER_SECOND = 1_500;

  // Monthly cost over one region, Module 34's prices (us-west-2 at us-east-1's list prices):
  // pilot light = 2 x db.m6id.xlarge Single-AZ at $0.448 an hour x 730 hours + $52.56 of log.
  private static final BigDecimal BACKUP_RESTORE_MONTHLY = new BigDecimal("21.20");
  private static final BigDecimal PILOT_LIGHT_MONTHLY = new BigDecimal("706.64");
  private static final BigDecimal WARM_STANDBY_MONTHLY = new BigDecimal("1270.42");
  private static final BigDecimal ACTIVE_ACTIVE_MONTHLY = new BigDecimal("4455.64");

  // Module 34's decision inputs, unchanged: 0.5 regional events a year of 872 minutes, $100 a
  // minute down, $25,000 per disrupted on-sale, 52 on-sales of 15 minutes a year.
  private static final BigDecimal EVENTS_PER_YEAR = new BigDecimal("0.5");
  private static final BigDecimal PENALTY_PER_ON_SALE = new BigDecimal("25000");
  private static final BigDecimal ON_SALES_PER_YEAR = new BigDecimal("52");
  private static final BigDecimal ON_SALE_MINUTES = new BigDecimal("15");
  private static final BigDecimal MINUTES_PER_YEAR = new BigDecimal("525600");
  private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
  private static final long SECONDS_PER_MINUTE = 60;
  private static final long MILLIS_PER_SECOND = 1_000;

  /** One term of a recovery time: what it is, how long, and where the number comes from. */
  private record Term(String name, long seconds, String source) {}

  /** One tier: its monthly cost, how its log reaches region-b, and its recovery steps. */
  private record Tier(
      String name,
      BigDecimal extraMonthlyUsd,
      long rpoMillis,
      String rpoMechanism,
      List<Term> terms) {
    long rtoSeconds() {
      return terms.stream().mapToLong(Term::seconds).sum();
    }
  }

  private RecoveryObjectivesCli() {}

  /** Prints the four tiers, their terms, the objective check and the break-even prices. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints the four tiers, their terms, the objective check and the break-even to {@code out}. */
  static void run(PrintStream out) {
    List<Tier> tiers = tiers();
    out.println("BOOKINGS DATABASE AFTER LOSING region-a: RPO AND RTO FROM THE MECHANISM");
    out.printf(
        Locale.ROOT,
        "(2 clusters of %,d MB restored in parallel at %d MB/s; %,d MB of log per cluster a day;"
            + " replay at %d MB/s; times in minutes:seconds)%n",
        CLUSTER_MEGABYTES,
        STREAM_MEGABYTES_PER_SECOND,
        LOG_MEGABYTES_PER_CLUSTER_DAY,
        replayMegabytesPerSecond());
    for (Tier tier : tiers) {
      out.printf(
          Locale.ROOT,
          "%n%s, %s a month over one region%n  RPO %s: %s%n",
          tier.name(),
          dollars(tier.extraMonthlyUsd()),
          duration(tier.rpoMillis()),
          tier.rpoMechanism());
      for (Term term : tier.terms()) {
        out.printf(
            Locale.ROOT, "  %-10s %7s  %s%n", term.name(), clock(term.seconds()), term.source());
      }
      out.printf(Locale.ROOT, "  %-10s %7s%n", "RTO", clock(tier.rtoSeconds()));
    }
    out.printf(
        Locale.ROOT,
        "%nAGAINST THE OBJECTIVE FOR BOOKINGS (RPO %s, RTO %s) at %,d bookings a second at the"
            + " peak%n",
        duration(OBJECTIVE_RPO_MILLIS),
        clock(OBJECTIVE_RTO_SECONDS),
        PEAK_BOOKINGS_PER_SECOND);
    out.printf(
        Locale.ROOT,
        "%-22s %9s %9s %14s %7s %7s%n",
        "tier",
        "RPO",
        "RTO",
        "at risk, peak",
        "RPO ok",
        "RTO ok");
    for (Tier tier : tiers) {
      out.printf(
          Locale.ROOT,
          "%-22s %9s %9s %,14d %7s %7s%n",
          tier.name(),
          duration(tier.rpoMillis()),
          clock(tier.rtoSeconds()),
          PEAK_BOOKINGS_PER_SECOND * tier.rpoMillis() / MILLIS_PER_SECOND,
          tier.rpoMillis() <= OBJECTIVE_RPO_MILLIS ? "yes" : "NO",
          tier.rtoSeconds() <= OBJECTIVE_RTO_SECONDS ? "yes" : "NO");
    }
    out.println(
        "\nBREAK-EVEN: the cost of a minute down above which the faster tier is the cheaper one"
            + " (Module 34's formula, computed RTOs)");
    for (int index = 0; index + 1 < tiers.size(); index++) {
      Tier slower = tiers.get(index);
      Tier faster = tiers.get(index + 1);
      out.printf(
          Locale.ROOT,
          "  %-22s -> %-22s %s a minute%n",
          slower.name(),
          faster.name(),
          dollars(breakEven(slower, faster)));
    }
    Tier backup = tiers.getFirst();
    long bytesSeconds =
        backup.terms().stream()
            .filter(term -> List.of("restore", "replay", "verify").contains(term.name()))
            .mapToLong(Term::seconds)
            .sum();
    out.printf(
        Locale.ROOT,
        "%nOf backup and restore's %s, %s moves bytes (restore, replay, verify) and %s is people,"
            + " code and DNS.%n",
        clock(backup.rtoSeconds()),
        clock(bytesSeconds),
        clock(backup.rtoSeconds() - bytesSeconds));
  }

  private static List<Tier> tiers() {
    Term detect =
        new Term(
            "detect",
            PAGE_SECONDS + ACKNOWLEDGE_SECONDS,
            "fast-burn page 20 s + acknowledge 3 min (Module 37)");
    Term decide =
        new Term("decide", DECLARE_SECONDS, "confirm a region event and declare (assumed)");
    Term redirect =
        new Term("redirect", REDIRECT_SECONDS, "health check 3 x 10 s + TTL 60 s (Module 6)");
    long restoreSeconds = CLUSTER_MEGABYTES / STREAM_MEGABYTES_PER_SECOND;
    long replaySeconds = LOG_MEGABYTES_PER_CLUSTER_DAY / replayMegabytesPerSecond();
    Tier backupAndRestore =
        new Tier(
            "backup and restore",
            BACKUP_RESTORE_MONTHLY,
            LOG_UPLOAD_INTERVAL_MILLIS + CROSS_REGION_COPY_MILLIS,
            "log uploaded every 5 min (RDS) + copied to region-b (assumed 5 min)",
            List.of(
                detect,
                decide,
                new Term(
                    "provision",
                    REBUILD_ENVIRONMENT_SECONDS,
                    "rebuild the environment from code (assumed)"),
                new Term("restore", restoreSeconds, "200,000 MB at 250 MB/s, clusters in parallel"),
                new Term(
                    "replay", replaySeconds, "a day of log, 10,000 MB at 24 MB/s (worst case)"),
                new Term(
                    "verify", restoreSeconds, "the checker reads the cluster once at 250 MB/s"),
                redirect));
    Tier pilotLight =
        new Tier(
            "pilot light",
            PILOT_LIGHT_MONTHLY,
            REPLICA_LAG_MILLIS,
            "a logical-replication copy per cluster in region-b, 100 ms behind (assumed)",
            List.of(
                detect,
                decide,
                new Term(
                    "provision",
                    REBUILD_ENVIRONMENT_SECONDS,
                    "everything but the data, from code (assumed)"),
                new Term(
                    "promote",
                    PROMOTE_REPLICAS_SECONDS,
                    "promote both replicas (Module 34's 5 min)"),
                new Term(
                    "verify",
                    VERIFY_REPLICA_SECONDS,
                    "counts and checksums on the critical tables (assumed)"),
                redirect));
    Tier warmStandby =
        new Tier(
            "warm standby",
            WARM_STANDBY_MONTHLY,
            REPLICA_LAG_MILLIS,
            "the same replicas; 3 nodes and the cluster already run",
            List.of(
                detect,
                decide,
                new Term(
                    "provision",
                    SCALE_STANDBY_NODES_SECONDS,
                    "scale nodes from 3 to 10 (Module 34's 8 min)"),
                new Term(
                    "promote",
                    PROMOTE_REPLICAS_SECONDS,
                    "promote both replicas (Module 34's 5 min)"),
                new Term(
                    "verify",
                    VERIFY_REPLICA_SECONDS,
                    "counts and checksums on the critical tables (assumed)"),
                redirect));
    Tier activeActive =
        new Tier(
            "active-active",
            ACTIVE_ACTIVE_MONTHLY,
            REPLICA_LAG_MILLIS,
            "each event's home region copies to the other, 100 ms behind",
            List.of(
                new Term("detect", PAGE_SECONDS, "the same page; the failover is pre-approved"),
                new Term(
                    "promote",
                    PROMOTE_HOME_REGION_SECONDS,
                    "move the lost region's events home (assumed)"),
                new Term(
                    "verify", VERIFY_HOME_REGION_SECONDS, "counts on the moved events (assumed)"),
                redirect));
    return List.of(backupAndRestore, pilotLight, warmStandby, activeActive);
  }

  private static long replayMegabytesPerSecond() {
    return VOLUME_IOPS * PAGE_KILOBYTES / KILOBYTES_PER_MEGABYTE;
  }

  /**
   * Module 34's break-even: the extra cost a year less the penalty saved, over the minutes saved.
   */
  private static BigDecimal breakEven(Tier slower, Tier faster) {
    BigDecimal extraPerYear =
        faster.extraMonthlyUsd().subtract(slower.extraMonthlyUsd()).multiply(MONTHS_PER_YEAR);
    BigDecimal slowerMinutes = minutes(slower.rtoSeconds());
    BigDecimal fasterMinutes = minutes(faster.rtoSeconds());
    BigDecimal penaltySaved =
        EVENTS_PER_YEAR
            .multiply(PENALTY_PER_ON_SALE)
            .multiply(onSaleOverlap(slowerMinutes).subtract(onSaleOverlap(fasterMinutes)));
    BigDecimal minutesSaved = EVENTS_PER_YEAR.multiply(slowerMinutes.subtract(fasterMinutes));
    return extraPerYear.subtract(penaltySaved).divide(minutesSaved, MathContext.DECIMAL64);
  }

  private static BigDecimal onSaleOverlap(BigDecimal downMinutes) {
    return ON_SALES_PER_YEAR
        .multiply(downMinutes.add(ON_SALE_MINUTES))
        .divide(MINUTES_PER_YEAR, MathContext.DECIMAL64)
        .min(BigDecimal.ONE);
  }

  private static BigDecimal minutes(long seconds) {
    return BigDecimal.valueOf(seconds)
        .divide(BigDecimal.valueOf(SECONDS_PER_MINUTE), MathContext.DECIMAL64);
  }

  private static String dollars(BigDecimal usd) {
    return String.format(Locale.ROOT, "$%,.2f", usd.setScale(2, RoundingMode.HALF_EVEN));
  }

  private static String clock(long seconds) {
    return String.format(
        Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
  }

  private static String duration(long millis) {
    return millis < MILLIS_PER_SECOND
        ? millis + " ms"
        : String.format(
            Locale.ROOT, "%.1f min", millis / (double) (SECONDS_PER_MINUTE * MILLIS_PER_SECOND));
  }
}
