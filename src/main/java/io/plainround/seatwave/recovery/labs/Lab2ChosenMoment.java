package io.plainround.seatwave.recovery.labs;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.recovery.archive.LogRecord;
import io.plainround.seatwave.recovery.archive.SegmentArchive;
import io.plainround.seatwave.recovery.backup.BaseBackup;
import io.plainround.seatwave.recovery.restore.PointInTimeRestore;
import io.plainround.seatwave.recovery.restore.RestoreChecker;
import io.plainround.seatwave.recovery.restore.RestoreOutcome;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lab 2's solution: a point-in-time restore to a moment you choose, verified by the checker. A job
 * cancels 40 bookings of section C by mistake at 14:30:00.500; the lab restores to three moments
 * around it and shows that only a target before the mistake brings the rows back, and that the
 * checker declares each restore good only against the manifest of its own moment. Takes one
 * argument, an empty work folder.
 */
public final class Lab2ChosenMoment {

  private static final Instant BACKUP_AT = Instant.parse("2026-11-10T14:00:00Z");
  private static final Instant MISTAKE_AT = Instant.parse("2026-11-10T14:30:00.500Z");
  private static final int BOOKINGS = 3_000;
  private static final int CANCELLED = 40;
  private static final Duration BOOKING_GAP = Duration.ofSeconds(1);
  private static final int RECORDS_PER_SEGMENT = 64;
  private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(60);
  private static final List<Duration> OFFSETS =
      List.of(Duration.ofMillis(-500), Duration.ZERO, Duration.ofMillis(500));

  private Lab2ChosenMoment() {}

  /** Runs the three restores in {@code arguments[0]}. */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("usage: Lab2ChosenMoment <empty work folder>");
    }
    run(System.out, Path.of(arguments[0]));
  }

  /** Runs the three restores in the empty folder {@code work}. */
  static void run(PrintStream out, Path work) throws IOException {
    Path data = Files.createDirectories(work.resolve("data"));
    Path backup = Files.createDirectories(work.resolve("backup"));
    Path archived = Files.createDirectories(work.resolve("archive"));
    Map<String, String> rowsAtBackup = Map.of();
    PointInTimeRestore.writeBookings(data.resolve(PointInTimeRestore.BOOKINGS_FILE), rowsAtBackup);
    BaseBackup.take(data, backup, new BaseBackup.Label(BACKUP_AT, 1));
    List<LogRecord> changes = history();
    SegmentArchive archive =
        new SegmentArchive(archived, RECORDS_PER_SEGMENT, ARCHIVE_TIMEOUT, Duration.ZERO);
    archive.archiveUntil(changes, BACKUP_AT.plus(Duration.ofHours(1)));
    for (Duration offset : OFFSETS) {
      Instant target = MISTAKE_AT.plus(offset);
      Path restored = Files.createDirectories(work.resolve("restored" + offset.toMillis()));
      Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome =
          PointInTimeRestore.restore(backup, archive, restored, target);
      Result<RestoreChecker.Declared, RestoreChecker.Refused> verdict =
          RestoreChecker.declare(outcome, RestoreChecker.manifestAt(rowsAtBackup, changes, target));
      RestoreOutcome.Recovered recovered =
          outcome.orOnErrorThrow(failure ->
              new IllegalStateException("the lab's archive is complete; got " + failure));
      long cancelledBack =
          changes.stream()
              .filter(change -> change.operation() == LogRecord.Operation.DELETE)
              .filter(change -> recovered.state().holds(change.bookingId()))
              .count();
      out.printf(
          Locale.ROOT,
          "target %s: %d rows, %d of %d cancelled rows back, checker %s%n",
          target.toString().substring("2026-11-10T".length()),
          recovered.state().bookingCount(),
          cancelledBack,
          CANCELLED,
          verdict.isError() ? "REFUSED" : "DECLARED GOOD");
    }
  }

  /** A booking a second from 14:00:01; at 14:30:00.500 one statement cancels 40 of section C. */
  private static List<LogRecord> history() {
    List<LogRecord> changes = new ArrayList<>();
    TreeMap<String, String> live = new TreeMap<>();
    long lsn = 1;
    boolean mistakeDone = false;
    for (int number = 1; number <= BOOKINGS; number++) {
      Instant at = BACKUP_AT.plus(BOOKING_GAP.multipliedBy(number));
      if (!mistakeDone && at.isAfter(MISTAKE_AT)) {
        List<String> sectionC =
            live.entrySet().stream()
                .filter(row -> row.getValue().startsWith("C-"))
                .limit(CANCELLED)
                .map(Map.Entry::getKey)
                .toList();
        for (String bookingId : sectionC) {
          changes.add(
              new LogRecord(
                  lsn++, MISTAKE_AT, LogRecord.Operation.DELETE, bookingId, LogRecord.NO_SEAT));
          live.remove(bookingId);
        }
        mistakeDone = true;
      }
      String bookingId = String.format(Locale.ROOT, "b-%06d", number);
      String seat = "ABC".charAt(number % 3) + "-" + number;
      changes.add(new LogRecord(lsn++, at, LogRecord.Operation.BOOK, bookingId, seat));
      live.put(bookingId, seat);
    }
    return List.copyOf(changes);
  }
}
