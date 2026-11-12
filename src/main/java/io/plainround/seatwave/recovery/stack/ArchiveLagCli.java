package io.plainround.seatwave.recovery.stack;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Measures the two terms of a restore's recovery point from the archive on the lab: how old the
 * newest archived segment gets (Section 10's {@code archive_lag_seconds}, sampled once a second
 * from {@code pg_stat_archiver}), and how long a closed segment takes to reach the repository
 * (forced segment switches, each timed until the archiver reports it pushed). Usage: {@code
 * ArchiveLagCli <settings> <sample seconds> <switches>}.
 */
public final class ArchiveLagCli {

  private static final String ARCHIVER =
      """
      SELECT extract(epoch FROM now() - last_archived_time) FROM pg_stat_archiver\
      """;
  // A switch closes the current segment only if it holds log, so each one follows a heartbeat row;
  // pg_switch_wal returns the end of the switch record, which lies in the segment it closed.
  private static final String BEAT =
      "INSERT INTO booking.drill_heartbeat (beat_at) VALUES (clock_timestamp())";
  private static final String SWITCH = "SELECT pg_walfile_name(pg_switch_wal())";
  private static final String LAST_ARCHIVED = "SELECT last_archived_wal FROM pg_stat_archiver";
  private static final Duration SAMPLE_GAP = Duration.ofSeconds(1);
  private static final Duration POLL = Duration.ofMillis(20);
  private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration BETWEEN_SWITCHES = Duration.ofSeconds(2);
  private static final int MAX_SECONDS = 3_600;
  private static final int MAX_SWITCHES = 200;
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double PERCENTILE_50 = 0.50;
  private static final int USAGE_STATUS = 2;
  private static final int ARGUMENTS = 3;

  private ArchiveLagCli() {}

  /** Samples the archive's age, then times the pushes. */
  public static void main(String[] arguments) throws SQLException {
    if (arguments.length != ARGUMENTS) {
      System.err.println("usage: ArchiveLagCli <settings> <sample seconds> <switches>");
      System.exit(USAGE_STATUS);
    }
    StackSettings settings =
        StackSettings.load(Path.of(arguments[0]))
            .orOnErrorThrow(problems -> new IllegalArgumentException("settings: " + problems));
    int seconds = bounded(arguments[1], MAX_SECONDS);
    int switches = bounded(arguments[2], MAX_SWITCHES);
    try (Connection admin =
        LabDatabase.connect(settings, settings.primaryUrl(), settings.admin())) {
      System.out.println(sampleAge(admin, seconds));
      System.out.println(timePushes(admin, switches));
    }
  }

  private static String sampleAge(Connection admin, int seconds) throws SQLException {
    List<Double> ages = new ArrayList<>();
    try (PreparedStatement archiver = admin.prepareStatement(ARCHIVER)) {
      for (int sample = 0; sample < seconds; sample++) {
        try (ResultSet row = archiver.executeQuery()) {
          row.next();
          ages.add(row.getDouble(1));
        }
        LockSupport.parkNanos(SAMPLE_GAP.toNanos());
      }
    }
    List<Double> sorted = ages.stream().sorted().toList();
    return String.format(
        Locale.ROOT,
        "time since the newest segment was archived, over %d s sampled each second: median %.1f s,"
            + " max %.1f s",
        seconds,
        sorted.get((int) (PERCENTILE_50 * (sorted.size() - 1))),
        sorted.getLast());
  }

  private static String timePushes(Connection admin, int switches) throws SQLException {
    List<Long> pushNanos = new ArrayList<>();
    try (PreparedStatement beat = admin.prepareStatement(BEAT);
        PreparedStatement switchSegment = admin.prepareStatement(SWITCH);
        PreparedStatement lastArchived = admin.prepareStatement(LAST_ARCHIVED)) {
      for (int attempt = 0; attempt < switches; attempt++) {
        beat.executeUpdate();
        String closed;
        try (ResultSet row = switchSegment.executeQuery()) {
          row.next();
          closed = row.getString(1);
        }
        long switchedNanos = System.nanoTime();
        pushNanos.add(waitArchived(lastArchived, closed, switchedNanos) - switchedNanos);
        LockSupport.parkNanos(BETWEEN_SWITCHES.toNanos());
      }
    }
    List<Long> sorted = pushNanos.stream().sorted().toList();
    return String.format(
        Locale.ROOT,
        "segment push, switch to archived, over %d forced switches: min %.0f ms, median %.0f ms,"
            + " max %.0f ms",
        switches,
        sorted.getFirst() / NANOS_PER_MILLI,
        sorted.get((int) (PERCENTILE_50 * (sorted.size() - 1))) / NANOS_PER_MILLI,
        sorted.getLast() / NANOS_PER_MILLI);
  }

  private static long waitArchived(PreparedStatement lastArchived, String segment, long since)
      throws SQLException {
    while (System.nanoTime() - since < PUSH_TIMEOUT.toNanos()) {
      try (ResultSet row = lastArchived.executeQuery()) {
        row.next();
        String archived = row.getString(1);
        // Segment names of one timeline sort in log order.
        if (archived != null && archived.compareTo(segment) >= 0) {
          return System.nanoTime();
        }
      }
      LockSupport.parkNanos(POLL.toNanos());
    }
    throw new IllegalStateException(segment + " was not archived within " + PUSH_TIMEOUT);
  }

  private static int bounded(String text, int maximum) {
    int value = Integer.parseInt(text);
    if (value < 1 || value > maximum) {
      throw new IllegalArgumentException("expected 1 to " + maximum + ", was " + value);
    }
    return value;
  }
}
