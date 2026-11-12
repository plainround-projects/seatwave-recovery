package io.plainround.seatwave.recovery.restore;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.recovery.archive.LogRecord;
import io.plainround.seatwave.recovery.archive.SegmentArchive;
import io.plainround.seatwave.recovery.backup.BaseBackup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Point-in-time recovery for the drill store, in PostgreSQL's order: restore the base backup's
 * files (checked against its manifest), then replay archived log segments from the backup's start
 * LSN, in LSN order, and stop just before the first change that committed at or after the target
 * (PostgreSQL's {@code recovery_target_time} with {@code recovery_target_inclusive = off}). A
 * missing or damaged segment ends replay early and says so; it never skips ahead, because a change
 * replayed without the changes before it would build a state that never existed. Stateless and
 * thread-safe.
 */
public final class PointInTimeRestore {

  /** The data file inside a backup: one line per booking, {@code bookingId TAB seat}. */
  public static final String BOOKINGS_FILE = "bookings.tsv";

  /** Pass as the target to replay everything the archive holds (a restore after a region loss). */
  public static final Instant END_OF_ARCHIVE = Instant.MAX;

  private static final String FIELD_SEPARATOR = "\t";

  private PointInTimeRestore() {}

  /**
   * Restores {@code backupDirectory} into the empty {@code targetDirectory} and replays {@code
   * archive} up to, not including, the first change at or after {@code stopBefore}.
   *
   * @return the recovered copy, or why the restore did not reach its goal
   */
  public static Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> restore(
      Path backupDirectory, SegmentArchive archive, Path targetDirectory, Instant stopBefore) {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(stopBefore, "stopBefore");
    return BaseBackup.restoreFiles(backupDirectory, targetDirectory)
        .<RestoreOutcome.Failure>mapError(
            rejected -> new RestoreOutcome.BackupRejected(rejected.reason()))
        .flatMap(verified -> replay(verified.label(), archive, targetDirectory, stopBefore));
  }

  private static Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> replay(
      BaseBackup.Label label, SegmentArchive archive, Path targetDirectory, Instant stopBefore) {
    TreeMap<String, String> bookings = readBookings(targetDirectory.resolve(BOOKINGS_FILE));
    Replay replay = new Replay(bookings, label.startLsn() - 1, label.takenAt(), label.takenAt());
    String previousSegment = "none";
    for (Path segment : archive.segmentsInOrder()) {
      Result<List<LogRecord>, SegmentArchive.Damaged> read = SegmentArchive.read(segment);
      if (read.isError()) {
        return replay.stoppedShort(
            read.map(records -> "").recoverError(SegmentArchive.Damaged::reason));
      }
      for (LogRecord change : read.discardError().orElseThrow()) {
        if (change.lsn() < label.startLsn()) {
          continue; // already inside the base backup
        }
        // Continuity is checked on LSNs, not file names: a segment the restore does not need may
        // be gone (retention), but a hole in the changes it does need ends the replay.
        if (change.lsn() != replay.lastLsn + 1) {
          return replay.stoppedShort(
              "the log jumps from LSN "
                  + replay.lastLsn
                  + " to "
                  + change.lsn()
                  + ": a segment between "
                  + previousSegment
                  + " and "
                  + segment.getFileName()
                  + " is missing");
        }
        if (!change.committedAt().isBefore(stopBefore)) {
          return Result.success(
              new RestoreOutcome.Recovered(
                  replay.state(), RestoreOutcome.StopPoint.REACHED_TARGET));
        }
        replay.apply(change);
      }
      previousSegment = segment.getFileName().toString();
    }
    if (stopBefore.equals(END_OF_ARCHIVE)) {
      return Result.success(
          new RestoreOutcome.Recovered(replay.state(), RestoreOutcome.StopPoint.END_OF_ARCHIVE));
    }
    return replay.stoppedShort("the archive ends before the recovery target " + stopBefore);
  }

  /** Writes {@code bookings} as the data file the store keeps and a base backup copies. */
  public static void writeBookings(Path dataFile, Map<String, String> bookings) {
    StringBuilder text = new StringBuilder();
    bookings.forEach((bookingId, seat) ->
        text.append(bookingId).append(FIELD_SEPARATOR).append(seat).append('\n'));
    try {
      Files.writeString(dataFile, text, StandardCharsets.UTF_8);
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot write " + dataFile, writeFailure);
    }
  }

  private static TreeMap<String, String> readBookings(Path dataFile) {
    TreeMap<String, String> bookings = new TreeMap<>();
    try {
      for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
        String[] fields = line.split(FIELD_SEPARATOR, -1);
        bookings.put(fields[0], fields[1]);
      }
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read " + dataFile, readFailure);
    }
    return bookings;
  }

  /** The replay in progress: confined to one restore call. */
  private static final class Replay {
    private final TreeMap<String, String> bookings;
    private long lastLsn;
    private Instant lastCommit;
    private Instant lastHeartbeat;

    Replay(
        TreeMap<String, String> bookings, long lastLsn, Instant lastCommit, Instant lastHeartbeat) {
      this.bookings = bookings;
      this.lastLsn = lastLsn;
      this.lastCommit = lastCommit;
      this.lastHeartbeat = lastHeartbeat;
    }

    void apply(LogRecord change) {
      switch (change.operation()) {
        case BOOK -> bookings.put(change.bookingId(), change.seat());
        case DELETE -> bookings.remove(change.bookingId());
        case HEARTBEAT -> lastHeartbeat = change.committedAt();
      }
      lastLsn = change.lsn();
      lastCommit = change.committedAt();
    }

    RestoreOutcome.RestoredState state() {
      return new RestoreOutcome.RestoredState(bookings, lastLsn, lastCommit, lastHeartbeat);
    }

    Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> stoppedShort(String reason) {
      return Result.error(new RestoreOutcome.StoppedShort(state(), reason));
    }
  }
}
