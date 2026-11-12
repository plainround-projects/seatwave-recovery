package io.plainround.seatwave.recovery.drill;

import io.plainround.seatwave.recovery.backup.BaseBackup;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * A timed restore drill in two parts. Part 1 is measured: it takes a base backup of the drill store
 * at three sizes on this machine's disk and times the verified restore of each, as a drill log.
 * Part 2 is a model at SeatWave's real sizes (Modules 14, 18 and 34) with stated throughputs: it
 * shows that restore time grows with the data, how far parallel streams help before the target
 * volume caps them, and that a standby's promotion does not grow at all. Takes one argument, an
 * empty work folder.
 */
public final class RestoreDrillCli {

  private static final List<Integer> MEASURED_MEGABYTES = List.of(25, 100, 400);
  private static final int BYTES_PER_MEGABYTE = 1_000_000;
  private static final String ROW = "b-%09d\tA-%d\tCONFIRMED\t2026-11-10T10:00:00Z\t95.00\n";
  private static final int SEATS_PER_SECTION = 1_000;
  private static final double NANOS_PER_SECOND = 1e9;
  private static final double MILLIS_PER_SECOND = 1e3;

  // Part 2 inputs: Module 18's assumed 250 MB/s for one stream; a target volume provisioned for
  // 1,000 MB/s (assumed); the reference machine's network, up to 12.5 Gbps = 1,562.5 MB/s.
  private static final double STREAM_MEGABYTES_PER_SECOND = 250;
  private static final double VOLUME_CAP_MEGABYTES_PER_SECOND = 1_000;
  private static final double NETWORK_CAP_MEGABYTES_PER_SECOND = 1_562.5;
  private static final List<Integer> STREAMS = List.of(1, 4, 8);

  // A day of log per cluster replayed at 24 MB/s (RecoveryObjectivesCli), and Module 34's 5-minute
  // promotion of a standby, which does not depend on the size of the data.
  private static final double REPLAY_SECONDS = 10_000 / 24.0;
  private static final double PROMOTE_SECONDS = 300;

  // Backup and restore's people, code and DNS time (RecoveryObjectivesCli: 44 min 50 s) against
  // the 60-minute RTO: what is left for restore, replay and verify.
  private static final double NON_BYTE_SECONDS = 2_690;
  private static final double OBJECTIVE_RTO_SECONDS = 3_600;
  private static final double SECONDS_PER_MINUTE = 60;
  private static final long MINUTES_PER_HOUR = 60;

  /** One data size worth knowing, with where it comes from. */
  private record DataSize(String label, double megabytes) {}

  private RestoreDrillCli() {}

  /** Runs the measured drill in {@code arguments[0]}, then prints the model. */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("usage: RestoreDrillCli <empty work folder>");
    }
    measuredDrill(System.out, Path.of(arguments[0]));
    model(System.out);
  }

  /** Runs part 1, the measured drill, in the empty folder {@code work}. */
  static void measuredDrill(PrintStream out, Path work) {
    out.println(
        "PART 1. TIMED RESTORE DRILL ON THIS MACHINE (measured; files freshly written,"
            + " so reads come mostly from the page cache)");
    out.printf(
        Locale.ROOT,
        "%-9s %-14s %11s %10s %12s%n",
        "size",
        "step",
        "started at",
        "took",
        "throughput");
    long drillStartNanos = System.nanoTime();
    for (int megabytes : MEASURED_MEGABYTES) {
      Path data = folder(work.resolve(megabytes + "mb/data"));
      Path backup = folder(work.resolve(megabytes + "mb/backup"));
      Path restored = folder(work.resolve(megabytes + "mb/restored"));
      writeRows(data.resolve("bookings.tsv"), (long) megabytes * BYTES_PER_MEGABYTE);
      long backupStart = System.nanoTime();
      BaseBackup.take(data, backup, new BaseBackup.Label(Instant.parse("2026-11-10T10:00:00Z"), 1));
      long backupNanos = System.nanoTime() - backupStart;
      long restoreStart = System.nanoTime();
      BaseBackup.restoreFiles(backup, restored)
          .orOnErrorThrow(rejected ->
              new IllegalStateException("the drill's own backup did not verify: " + rejected));
      long restoreNanos = System.nanoTime() - restoreStart;
      logStep(out, megabytes, "backup", backupStart - drillStartNanos, backupNanos);
      logStep(out, megabytes, "restore+verify", restoreStart - drillStartNanos, restoreNanos);
    }
  }

  /** Prints part 2, the model at SeatWave's sizes. */
  static void model(PrintStream out) {
    List<DataSize> sizes =
        List.of(
            new DataSize("bookings table, Module 14 (4.37 GB)", 4_370),
            new DataSize("one cluster today, Module 34 (200 GB)", 200_000),
            new DataSize("one shard, lifetime, Module 18 (312.5 GB)", 312_500),
            new DataSize("one cluster, lifetime, 32 shards (10 TB)", 10_000_000),
            new DataSize("all bookings, lifetime, Module 18 (20 TB)", 20_000_000));
    out.println(
        "\nPART 2. RESTORE TIME AT SEATWAVE'S SIZES (model: 250 MB/s a stream, capped at"
            + " 1,000 MB/s by the target volume; hours:minutes)");
    out.printf(
        Locale.ROOT,
        "%-44s %8s %8s %8s %13s %9s%n",
        "data",
        "1 stream",
        "4",
        "8",
        "4 + replay +",
        "standby");
    out.printf(Locale.ROOT, "%-44s %8s %8s %8s %13s %9s%n", "", "", "", "", "verify", "promote");
    for (DataSize size : sizes) {
      StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-44s", size.label()));
      for (int streams : STREAMS) {
        line.append(
            String.format(
                Locale.ROOT, " %8s", hoursMinutes(restoreSeconds(size.megabytes(), streams))));
      }
      double fourStreams = restoreSeconds(size.megabytes(), STREAMS.get(1));
      line.append(
          String.format(
              Locale.ROOT,
              " %13s %9s",
              hoursMinutes(fourStreams + REPLAY_SECONDS + fourStreams),
              hoursMinutes(PROMOTE_SECONDS)));
      out.println(line);
    }
    double bytesBudget = OBJECTIVE_RTO_SECONDS - NON_BYTE_SECONDS - REPLAY_SECONDS;
    out.printf(
        Locale.ROOT,
        "%nLargest cluster that backup and restore can bring back inside the 60-minute RTO"
            + " (%.0f s left after people, code, DNS and replay):%n",
        bytesBudget);
    for (int streams : STREAMS) {
      double rate = Math.min(streams * STREAM_MEGABYTES_PER_SECOND, cap());
      // Restore and verify each read the data once, so the budget covers two passes.
      out.printf(
          Locale.ROOT,
          "  %d stream(s) at %,.0f MB/s: %,.0f MB%n",
          streams,
          rate,
          bytesBudget * rate / 2);
    }
  }

  private static String hoursMinutes(double seconds) {
    long roundedMinutes = Math.round(seconds / SECONDS_PER_MINUTE);
    return String.format(
        Locale.ROOT,
        "%d:%02d",
        roundedMinutes / MINUTES_PER_HOUR,
        roundedMinutes % MINUTES_PER_HOUR);
  }

  private static double restoreSeconds(double megabytes, int streams) {
    return megabytes / Math.min(streams * STREAM_MEGABYTES_PER_SECOND, cap());
  }

  private static double cap() {
    return Math.min(VOLUME_CAP_MEGABYTES_PER_SECOND, NETWORK_CAP_MEGABYTES_PER_SECOND);
  }

  /**
   * Prints one drill-log line: when the step started after the drill began, and how long it took.
   */
  private static void logStep(
      PrintStream out, int megabytes, String step, long startedAfterNanos, long tookNanos) {
    double tookSeconds = tookNanos / NANOS_PER_SECOND;
    out.printf(
        Locale.ROOT,
        "%-9s %-14s %8.0f ms %7.0f ms %7.0f MB/s%n",
        megabytes + " MB",
        step,
        startedAfterNanos / NANOS_PER_SECOND * MILLIS_PER_SECOND,
        tookSeconds * MILLIS_PER_SECOND,
        megabytes / tookSeconds);
  }

  private static void writeRows(Path file, long bytes) {
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      long written = 0;
      for (long rowNumber = 1; written < bytes; rowNumber++) {
        String row = String.format(Locale.ROOT, ROW, rowNumber, rowNumber % SEATS_PER_SECTION);
        writer.write(row);
        written += row.length();
      }
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot write " + file, writeFailure);
    }
  }

  private static Path folder(Path path) {
    try {
      return Files.createDirectories(path);
    } catch (IOException createFailure) {
      throw new UncheckedIOException("cannot create " + path, createFailure);
    }
  }
}
