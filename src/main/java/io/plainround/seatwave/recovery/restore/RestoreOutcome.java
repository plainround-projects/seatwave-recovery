package io.plainround.seatwave.recovery.restore;

import java.time.Instant;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a point-in-time restore can produce: a {@link Recovered} copy, or a {@link Failure} that
 * says why it could not get there. {@link PointInTimeRestore#restore} returns one of them as a
 * result4j {@code Result}. None of them is a declared recovery: only {@link RestoreChecker#declare}
 * can turn a {@link Recovered} into one, after checking it.
 */
public final class RestoreOutcome {

  /**
   * The rows a restore rebuilt and how far into the log it got.
   *
   * @param bookings booking id to seat, sorted by id
   * @param lastAppliedLsn the last change replayed (the backup's start minus one if none)
   * @param lastAppliedCommit the commit time of that change (the backup's time if none)
   * @param lastHeartbeat the newest heartbeat replayed: the data really recovered reaches this far
   */
  public record RestoredState(
      SortedMap<String, String> bookings,
      long lastAppliedLsn,
      Instant lastAppliedCommit,
      Instant lastHeartbeat) {
    /** Copies the rows, so the state cannot change after the restore returns it. */
    public RestoredState {
      bookings = new TreeMap<>(Objects.requireNonNull(bookings, "bookings"));
      Objects.requireNonNull(lastAppliedCommit, "lastAppliedCommit");
      Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
    }

    @Override
    public SortedMap<String, String> bookings() {
      return new TreeMap<>(bookings);
    }

    /** Returns the number of booking rows. */
    public int bookingCount() {
      return bookings.size();
    }

    /** Returns true if the restored copy holds {@code bookingId}. */
    public boolean holds(String bookingId) {
      return bookings.containsKey(bookingId);
    }
  }

  /** Why replay ended where it did. */
  public enum StopPoint {
    /** The next change committed at or after the recovery target, so it was not applied. */
    REACHED_TARGET,
    /** No target was set and the archive ran out: everything archived was applied. */
    END_OF_ARCHIVE
  }

  /** Replay reached its goal. Still to be checked before anyone may call it good. */
  public record Recovered(RestoredState state, StopPoint stopPoint) {}

  /** Why a restore did not reach its goal: an expected failure, not a bug. */
  public sealed interface Failure permits StoppedShort, BackupRejected {}

  /**
   * Replay could not reach the target: a segment is missing or damaged. PostgreSQL 18 shuts down
   * with a fatal error when a target is set and not reached; the drill store reports how far it
   * got, so the drill can say what would be lost.
   */
  public record StoppedShort(RestoredState state, String reason) implements Failure {}

  /** The base backup failed its manifest check; nothing was restored. */
  public record BackupRejected(String reason) implements Failure {}

  private RestoreOutcome() {}
}
