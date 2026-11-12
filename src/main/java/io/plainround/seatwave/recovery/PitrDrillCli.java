package io.plainround.seatwave.recovery;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.recovery.archive.LogRecord;
import io.plainround.seatwave.recovery.archive.SegmentArchive;
import io.plainround.seatwave.recovery.backup.BaseBackup;
import io.plainround.seatwave.recovery.restore.PointInTimeRestore;
import io.plainround.seatwave.recovery.restore.RestoreChecker;
import io.plainround.seatwave.recovery.restore.RestoreOutcome;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
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
 * Four restores of the drill store on real files, each printed with what it recovered and what it
 * lost: (1) a bad delete undone by restoring to just before it; (2) the same restore with one
 * archived segment missing; (3) a base backup that was never test-restored and is unreadable; (4) a
 * region loss, restored from what reached the archive. Takes one argument, an empty work folder.
 */
public final class PitrDrillCli {

  private static final Instant OLDER_BACKUP_AT = Instant.parse("2026-11-10T09:00:00Z");
  private static final Instant NEWER_BACKUP_AT = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant BAD_DELETE_AT = Instant.parse("2026-11-10T10:41:07Z");
  private static final Instant LAST_BOOKING_BEFORE = Instant.parse("2026-11-10T10:45:00Z");
  private static final Instant REGION_LOST_AT = Instant.parse("2026-11-10T10:43:30Z");
  private static final int ROWS_BEFORE_OLDER_BACKUP = 120;
  private static final Duration EARLY_BOOKING_GAP = Duration.ofSeconds(18);
  private static final Duration BUSY_BOOKING_GAP = Duration.ofSeconds(2);
  private static final Duration HEARTBEAT_GAP = Duration.ofSeconds(10);
  private static final String SECTIONS = "ABC";
  private static final String DELETED_SECTION = "A";

  /** Segment rules, PostgreSQL's in small: a segment holds 64 records or closes after 60 s. */
  private static final int RECORDS_PER_SEGMENT = 64;

  private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COPY_LAG_TO_REGION_B = Duration.ofSeconds(5);

  /** After the bad delete the primary is alive: a forced segment switch archives everything. */
  private static final Duration SWITCH_AND_ARCHIVE = Duration.ofSeconds(60);

  /** Scenario 2 removes the archived segment that holds the first change after this moment. */
  private static final Instant MISSING_SEGMENT_AFTER = Instant.parse("2026-11-10T10:20:00Z");

  private static final int CORRUPTED_BYTE_OFFSET = 100;

  /** One generated history: the rows at each backup and every change after the older one. */
  private record History(
      Map<String, String> rowsAtOlderBackup, long newerStartLsn, List<LogRecord> changes) {}

  private PitrDrillCli() {}

  /** Runs the four restores in {@code arguments[0]}. */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("usage: PitrDrillCli <empty work folder>");
    }
    run(System.out, Path.of(arguments[0]));
  }

  /** Runs the four restores in the empty folder {@code work}, printing to {@code out}. */
  static void run(PrintStream out, Path work) {
    History withDelete = history(true);
    out.println(
        "POINT-IN-TIME RESTORE DRILLS on real files (times UTC on 10 November 2026; segments of "
            + RECORDS_PER_SEGMENT
            + " records or "
            + ARCHIVE_TIMEOUT.toSeconds()
            + " s, framed with CRC32C)");
    out.println(
        "base backups at "
            + clock(OLDER_BACKUP_AT)
            + " and "
            + clock(NEWER_BACKUP_AT)
            + "; bad DELETE of section "
            + DELETED_SECTION
            + " at "
            + clock(BAD_DELETE_AT)
            + "\n");

    out.println(
        "1. Undo the bad delete: restore the 10:00 backup to just before " + clock(BAD_DELETE_AT));
    Drill first =
        prepare(
            work.resolve("1-bad-delete"), withDelete, LAST_BOOKING_BEFORE.plus(SWITCH_AND_ARCHIVE));
    report(out, first, withDelete, BAD_DELETE_AT, first.newerBackup());

    Drill second =
        prepare(
            work.resolve("2-missing-segment"),
            withDelete,
            LAST_BOOKING_BEFORE.plus(SWITCH_AND_ARCHIVE));
    Path lostSegment = segmentHolding(second.archiveDirectory(), MISSING_SEGMENT_AFTER);
    out.println(
        "\n2. The same restore with segment "
            + lostSegment.getFileName()
            + " (the one holding "
            + clock(MISSING_SEGMENT_AFTER)
            + ") missing from the archive");
    delete(lostSegment);
    report(out, second, withDelete, BAD_DELETE_AT, second.newerBackup());

    out.println("\n3. The 10:00 backup was never test-restored; one byte of it is damaged");
    Drill third =
        prepare(
            work.resolve("3-unreadable-backup"),
            withDelete,
            LAST_BOOKING_BEFORE.plus(SWITCH_AND_ARCHIVE));
    flipByte(third.newerBackup().resolve(PointInTimeRestore.BOOKINGS_FILE));
    report(out, third, withDelete, BAD_DELETE_AT, third.newerBackup());
    out.println("   fall back to the 09:00 backup and replay one more hour of log:");
    report(out, third, withDelete, BAD_DELETE_AT, third.olderBackup());

    History noDelete = history(false);
    out.println(
        "\n4. region-a lost at "
            + clock(REGION_LOST_AT)
            + "; restore everything that reached the archive in region-b");
    Drill fourth = prepare(work.resolve("4-region-lost"), noDelete, REGION_LOST_AT);
    report(out, fourth, noDelete, PointInTimeRestore.END_OF_ARCHIVE, fourth.newerBackup());
  }

  /** The folders of one drill. */
  private record Drill(
      Path olderBackup,
      Path newerBackup,
      Path archiveDirectory,
      SegmentArchive.ArchiveSummary summary) {}

  private static Drill prepare(Path folder, History history, Instant archivedUntil) {
    Path data = folder.resolve("data");
    Path olderBackup = folder.resolve("backup-0900");
    Path newerBackup = folder.resolve("backup-1000");
    Path archive = folder.resolve("archive");
    createFolders(data, olderBackup, newerBackup, archive);
    Path dataFile = data.resolve(PointInTimeRestore.BOOKINGS_FILE);
    PointInTimeRestore.writeBookings(dataFile, history.rowsAtOlderBackup());
    BaseBackup.take(data, olderBackup, new BaseBackup.Label(OLDER_BACKUP_AT, 1));
    PointInTimeRestore.writeBookings(dataFile, rowsAt(history, NEWER_BACKUP_AT));
    BaseBackup.take(
        data, newerBackup, new BaseBackup.Label(NEWER_BACKUP_AT, history.newerStartLsn()));
    SegmentArchive segments =
        new SegmentArchive(archive, RECORDS_PER_SEGMENT, ARCHIVE_TIMEOUT, COPY_LAG_TO_REGION_B);
    return new Drill(
        olderBackup, newerBackup, archive, segments.archiveUntil(history.changes(), archivedUntil));
  }

  private static void report(
      PrintStream out, Drill drill, History history, Instant stopBefore, Path backup) {
    Path target = drill.archiveDirectory().resolveSibling("restored-from-" + backup.getFileName());
    createFolders(target);
    SegmentArchive archive =
        new SegmentArchive(
            drill.archiveDirectory(), RECORDS_PER_SEGMENT, ARCHIVE_TIMEOUT, COPY_LAG_TO_REGION_B);
    Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome =
        PointInTimeRestore.restore(backup, archive, target, stopBefore);
    boolean afterRegionLoss = stopBefore.equals(PointInTimeRestore.END_OF_ARCHIVE);
    Instant truthAt = afterRegionLoss ? REGION_LOST_AT : stopBefore;
    // After a region loss the manifest describes what the archiver says it shipped, not the loss
    // moment: the checker proves the restore holds all of the archive; the loss is reported apart.
    Instant manifestAt =
        afterRegionLoss
            ? drill.summary().lastArchivedCommit().orElse(NEWER_BACKUP_AT).plusNanos(1)
            : stopBefore;
    RestoreChecker.DrillManifest truth =
        RestoreChecker.manifestAt(history.rowsAtOlderBackup(), history.changes(), manifestAt);
    Result<RestoreChecker.Declared, RestoreChecker.Refused> verdict =
        RestoreChecker.declare(outcome, truth);
    out.printf(
        Locale.ROOT,
        "   archive: %d segments, %d records; %d records never archived%n",
        drill.summary().segmentsArchived(),
        drill.summary().recordsArchived(),
        drill.summary().recordsNeverArchived());
    outcome
        .peekSuccess(recovered -> {
          out.println("   restore: replay ended at " + recovered.stopPoint());
          printState(out, recovered.state(), history, truthAt);
        })
        .peekError(failure -> {
          switch (failure) {
            case RestoreOutcome.BackupRejected rejected ->
                out.println("   restore: REFUSED, " + rejected.reason());
            case RestoreOutcome.StoppedShort stopped -> {
              out.println("   restore: STOPPED SHORT, " + stopped.reason());
              printState(out, stopped.state(), history, truthAt);
            }
          }
        });
    out.println(
        verdict
            .map(declared ->
                String.format(
                    Locale.ROOT,
                    "   checker: DECLARED GOOD, %d rows match the manifest's count and SHA-256",
                    declared.state().bookingCount()))
            .recoverError(
                refused -> "   checker: REFUSED, " + String.join("; ", refused.reasons())));
  }

  private static void printState(
      PrintStream out, RestoreOutcome.RestoredState state, History history, Instant truthAt) {
    Map<String, String> truth = rowsAt(history, truthAt);
    List<LogRecord> deletes =
        history.changes().stream()
            .filter(change -> change.operation() == LogRecord.Operation.DELETE)
            .toList();
    long deletedBack = deletes.stream().filter(change -> state.holds(change.bookingId())).count();
    long missing = truth.keySet().stream().filter(bookingId -> !state.holds(bookingId)).count();
    long laterBookings =
        history.changes().stream()
            .filter(change -> change.operation() == LogRecord.Operation.BOOK)
            .filter(change ->
                !change.committedAt().isBefore(truthAt)
                    && change.committedAt().isBefore(LAST_BOOKING_BEFORE))
            .count();
    out.printf(
        Locale.ROOT,
        "   recovered %d booking rows; last change replayed LSN %d at %s; newest heartbeat %s%n",
        state.bookingCount(),
        state.lastAppliedLsn(),
        clock(state.lastAppliedCommit()),
        clock(state.lastHeartbeat()));
    out.printf(
        Locale.ROOT,
        "   truth at %s: %d rows; missing from the restore: %d%n",
        clock(truthAt),
        truth.size(),
        missing);
    if (!deletes.isEmpty()) {
      out.printf(
          Locale.ROOT,
          "   rows of the bad delete brought back: %d of %d%n",
          deletedBack,
          deletes.size());
    }
    if (truthAt.equals(BAD_DELETE_AT)) {
      out.printf(
          Locale.ROOT,
          "   not in the restored copy: %d bookings made after %s (still in the archive and in"
              + " production)%n",
          laterBookings,
          clock(truthAt));
    } else {
      out.printf(
          Locale.ROOT,
          "   lost with region-a: %d bookings; data loss window %d s by the last replayed commit,"
              + " %d s by the heartbeat%n",
          missing,
          Duration.between(state.lastAppliedCommit(), REGION_LOST_AT).toSeconds(),
          Duration.between(state.lastHeartbeat(), REGION_LOST_AT).toSeconds());
      out.printf(
          Locale.ROOT,
          "   bound from the mechanism: archive timeout %d s + copy lag %d s = %d s%n",
          ARCHIVE_TIMEOUT.toSeconds(),
          COPY_LAG_TO_REGION_B.toSeconds(),
          ARCHIVE_TIMEOUT.plus(COPY_LAG_TO_REGION_B).toSeconds());
    }
  }

  /**
   * Returns the archived segment that holds the first change committed at or after {@code moment}.
   */
  private static Path segmentHolding(Path archiveDirectory, Instant moment) {
    SegmentArchive archive =
        new SegmentArchive(
            archiveDirectory, RECORDS_PER_SEGMENT, ARCHIVE_TIMEOUT, COPY_LAG_TO_REGION_B);
    return archive.segmentsInOrder().stream()
        .filter(segment ->
            SegmentArchive.read(segment)
                .map(records ->
                    records.stream().anyMatch(change -> !change.committedAt().isBefore(moment)))
                .recoverError(damaged -> false))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no segment holds a change after " + moment));
  }

  /** Replays the generated history up to {@code moment} with no files at all: the drill's truth. */
  private static Map<String, String> rowsAt(History history, Instant moment) {
    TreeMap<String, String> rows = new TreeMap<>(history.rowsAtOlderBackup());
    for (LogRecord change : history.changes()) {
      if (!change.committedAt().isBefore(moment)) {
        break;
      }
      switch (change.operation()) {
        case BOOK -> rows.put(change.bookingId(), change.seat());
        case DELETE -> rows.remove(change.bookingId());
        case HEARTBEAT -> {
          // no row changes
        }
      }
    }
    return rows;
  }

  /**
   * 120 rows at 09:00; a booking every 18 s until 10:00, then every 2 s until 10:45; a heartbeat
   * every 10 s; and, if asked, one DELETE per section-A booking at 10:41:07, as one statement does.
   */
  private static History history(boolean includeBadDelete) {
    TreeMap<String, String> rowsAtOlder = new TreeMap<>();
    for (int bookingNumber = 1; bookingNumber <= ROWS_BEFORE_OLDER_BACKUP; bookingNumber++) {
      rowsAtOlder.put(bookingId(bookingNumber), seat(bookingNumber));
    }
    List<Instant> bookingTimes = new ArrayList<>();
    for (Instant at = OLDER_BACKUP_AT.plus(EARLY_BOOKING_GAP.dividedBy(2));
        at.isBefore(NEWER_BACKUP_AT);
        at = at.plus(EARLY_BOOKING_GAP)) {
      bookingTimes.add(at);
    }
    for (Instant at = NEWER_BACKUP_AT.plusSeconds(1);
        at.isBefore(LAST_BOOKING_BEFORE);
        at = at.plus(BUSY_BOOKING_GAP)) {
      bookingTimes.add(at);
    }
    List<LogRecord> changes = new ArrayList<>();
    TreeMap<String, String> live = new TreeMap<>(rowsAtOlder);
    long nextLsn = 1;
    long newerStartLsn = 0;
    int nextBooking = ROWS_BEFORE_OLDER_BACKUP + 1;
    int bookingIndex = 0;
    boolean deleteDone = !includeBadDelete;
    for (Instant tick = OLDER_BACKUP_AT.plus(HEARTBEAT_GAP);
        tick.isBefore(LAST_BOOKING_BEFORE.plus(HEARTBEAT_GAP));
        tick = tick.plus(HEARTBEAT_GAP)) {
      while (bookingIndex < bookingTimes.size() && bookingTimes.get(bookingIndex).isBefore(tick)) {
        Instant at = bookingTimes.get(bookingIndex++);
        if (newerStartLsn == 0 && !at.isBefore(NEWER_BACKUP_AT)) {
          newerStartLsn = nextLsn;
        }
        if (!deleteDone && !at.isBefore(BAD_DELETE_AT)) {
          for (String bookingId : sectionRows(live)) {
            changes.add(
                new LogRecord(
                    nextLsn++,
                    BAD_DELETE_AT,
                    LogRecord.Operation.DELETE,
                    bookingId,
                    LogRecord.NO_SEAT));
            live.remove(bookingId);
          }
          deleteDone = true;
        }
        String bookingId = bookingId(nextBooking);
        changes.add(
            new LogRecord(nextLsn++, at, LogRecord.Operation.BOOK, bookingId, seat(nextBooking++)));
        live.put(bookingId, changes.getLast().seat());
      }
      if (tick.isBefore(LAST_BOOKING_BEFORE)) {
        changes.add(LogRecord.heartbeat(nextLsn++, tick));
      }
    }
    return new History(rowsAtOlder, newerStartLsn, List.copyOf(changes));
  }

  private static List<String> sectionRows(Map<String, String> live) {
    return live.entrySet().stream()
        .filter(row -> row.getValue().startsWith(DELETED_SECTION + "-"))
        .map(Map.Entry::getKey)
        .toList();
  }

  private static String bookingId(int bookingNumber) {
    return String.format(Locale.ROOT, "b-%06d", bookingNumber);
  }

  private static String seat(int bookingNumber) {
    return SECTIONS.charAt(bookingNumber % SECTIONS.length())
        + "-"
        + (bookingNumber / SECTIONS.length() + 1);
  }

  private static String clock(Instant moment) {
    String time = moment.toString().substring("2026-11-10T".length());
    return moment.equals(Instant.MAX) ? "end of archive" : time.substring(0, time.length() - 1);
  }

  private static void flipByte(Path file) {
    try {
      byte[] bytes = Files.readAllBytes(file);
      // One flipped bit, as bit rot or a bad copy leaves it: the size stays the same.
      bytes[CORRUPTED_BYTE_OFFSET] ^= 1;
      Files.write(file, bytes);
    } catch (IOException damageFailure) {
      throw new UncheckedIOException("cannot damage " + file, damageFailure);
    }
  }

  private static void delete(Path file) {
    try {
      Files.delete(file);
    } catch (IOException deleteFailure) {
      throw new UncheckedIOException("cannot delete " + file, deleteFailure);
    }
  }

  private static void createFolders(Path... folders) {
    for (Path folder : folders) {
      try {
        Files.createDirectories(folder);
      } catch (IOException createFailure) {
        throw new UncheckedIOException("cannot create " + folder, createFailure);
      }
    }
  }
}
