package io.plainround.seatwave.recovery.labs;

import io.plainround.seatwave.recovery.backup.BaseBackup;
import io.plainround.seatwave.recovery.chaos.ChaosExperimentCli;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Lab 3's solution: time a full verified restore of a 1:1,000 copy of a 200 GB cluster (200 MB) on
 * this machine, scale it to 200 GB by the measured rate and by the planning rate of 250 MB/s, set
 * both against the 60-minute RTO, then rerun the primary-kill experiment after the pool fix and
 * write its result. Takes one argument, an empty work folder.
 */
public final class Lab3ScaledDrill {

  private static final long COPY_BYTES = 200_000_000L;
  private static final double SCALE = 1_000;
  private static final double PLANNING_MEGABYTES_PER_SECOND = 250;
  private static final double MEGABYTES = 1e6;
  private static final double NANOS_PER_SECOND = 1e9;
  private static final double SECONDS_PER_MINUTE = 60;

  // RecoveryObjectivesCli: backup and restore spends 44 min 50 s on people, code and DNS and up to
  // 6 min 56 s on replay, so restore and verify have 493 s of the 60 minutes.
  private static final double BYTES_BUDGET_SECONDS = 493;

  // After the pool fix (validate on borrow, re-resolve the writer endpoint), a client reconnects
  // an assumed 5 s after the 35 s failover.
  private static final int FIXED_ERROR_SECONDS = 40;
  private static final int ROLLING_RESTART_SECONDS = 77;

  private Lab3ScaledDrill() {}

  /** Runs the scaled drill in {@code arguments[0]} and the experiment. */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("usage: Lab3ScaledDrill <empty work folder>");
    }
    run(System.out, Path.of(arguments[0]));
  }

  /** Runs the scaled drill in the empty folder {@code work} and the experiment. */
  static void run(PrintStream out, Path work) throws IOException {
    Path data = Files.createDirectories(work.resolve("data"));
    Path backup = Files.createDirectories(work.resolve("backup"));
    Path restored = Files.createDirectories(work.resolve("restored"));
    try (BufferedWriter writer =
        Files.newBufferedWriter(data.resolve("bookings.tsv"), StandardCharsets.UTF_8)) {
      long written = 0;
      for (long row = 1; written < COPY_BYTES; row++) {
        String line =
            String.format(Locale.ROOT, "b-%09d\tA-%d\tCONFIRMED\t95.00%n", row, row % 1_000);
        writer.write(line);
        written += line.length();
      }
    }
    BaseBackup.take(data, backup, new BaseBackup.Label(Instant.parse("2026-11-10T10:00:00Z"), 1));
    long start = System.nanoTime();
    boolean isVerified = !BaseBackup.restoreFiles(backup, restored).isError();
    double seconds = (System.nanoTime() - start) / NANOS_PER_SECOND;
    double measuredRate = COPY_BYTES / MEGABYTES / seconds;
    double scaledByMeasured = seconds * SCALE;
    double scaledByPlanning = COPY_BYTES / MEGABYTES * SCALE / PLANNING_MEGABYTES_PER_SECOND;
    out.printf(
        Locale.ROOT,
        "restore + verify of 200 MB: %s in %.3f s (%.0f MB/s, measured)%n",
        isVerified ? "VERIFIED" : "REJECTED",
        seconds,
        measuredRate);
    out.printf(
        Locale.ROOT,
        "scaled x1,000 to 200 GB: %.1f min at the measured rate, %.1f min at"
            + " the planning 250 MB/s%n",
        scaledByMeasured / SECONDS_PER_MINUTE,
        scaledByPlanning / SECONDS_PER_MINUTE);
    out.printf(
        Locale.ROOT,
        "restore and verify fit the RTO's %.0f s of byte time: measured %s," + " planning %s%n",
        BYTES_BUDGET_SECONDS,
        scaledByMeasured <= BYTES_BUDGET_SECONDS ? "yes" : "NO",
        scaledByPlanning <= BYTES_BUDGET_SECONDS ? "yes" : "NO");
    ChaosExperimentCli.report(
        out,
        new ChaosExperimentCli.Experiment(
            "Lab 3: kill shard-37's writer again, after the pool fix",
            "holds on shard-37 are good again within 60 s, no acknowledged confirm is lost,"
                + " and the other 63 shards do not move",
            1,
            FIXED_ERROR_SECONDS,
            ROLLING_RESTART_SECONDS,
            List.of(ChaosExperimentCli.AbortCondition.values())),
        true);
  }
}
