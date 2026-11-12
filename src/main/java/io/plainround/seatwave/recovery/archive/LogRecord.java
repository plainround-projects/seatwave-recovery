package io.plainround.seatwave.recovery.archive;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One committed change in SeatWave's booking log: a booking, a deletion, or the drill heartbeat
 * that a recovery uses to read how far it really got. The log sequence number (LSN) orders every
 * change; a restore replays changes in LSN order and nothing else. Immutable and thread-safe.
 *
 * @param lsn the change's position in the log, from 1
 * @param committedAt when the change committed, in UTC
 * @param operation what the change does
 * @param bookingId the booking it touches ({@code heartbeat} for a heartbeat)
 * @param seat the seat, as section and number ({@code -} for a heartbeat or a deletion)
 */
public record LogRecord(
    long lsn, Instant committedAt, Operation operation, String bookingId, String seat) {

  /** The three kinds of change the drill store logs. */
  public enum Operation {
    /** A confirmed booking: the seat now belongs to the booking. */
    BOOK,
    /** The booking row is removed (in the drill, the mistaken DELETE). */
    DELETE,
    /** Written every few seconds so a restored copy can say up to when it holds data. */
    HEARTBEAT
  }

  /** The heartbeat's booking id; never a real booking's. */
  public static final String HEARTBEAT_ID = "heartbeat";

  /** The seat field when a change has no seat. */
  public static final String NO_SEAT = "-";

  private static final String FIELD_SEPARATOR = "\t";
  private static final int FIELD_COUNT = 5;

  // Ids and seats are written into tab-separated lines, so they may hold only these characters.
  private static final Pattern SAFE_TEXT = Pattern.compile("[A-Za-z0-9_-]{1,40}");

  /**
   * Validates every field at construction, so a record that exists is a record that can be logged.
   */
  public LogRecord {
    if (lsn < 1) {
      throw new IllegalArgumentException("lsn must be 1 or more, was " + lsn);
    }
    Objects.requireNonNull(committedAt, "committedAt");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(seat, "seat");
    if (!SAFE_TEXT.matcher(bookingId).matches() || !SAFE_TEXT.matcher(seat).matches()) {
      throw new IllegalArgumentException(
          "bookingId and seat must be 1 to 40 of A-Z, a-z, 0-9, _ or -, were '"
              + bookingId
              + "' and '"
              + seat
              + "'");
    }
  }

  /** Returns a heartbeat record at {@code lsn} and {@code committedAt}. */
  public static LogRecord heartbeat(long lsn, Instant committedAt) {
    return new LogRecord(lsn, committedAt, Operation.HEARTBEAT, HEARTBEAT_ID, NO_SEAT);
  }

  /** Returns the record as one tab-separated line, without a line break. */
  public String encode() {
    return String.join(
        FIELD_SEPARATOR,
        Long.toString(lsn),
        committedAt.toString(),
        operation.name(),
        bookingId,
        seat);
  }

  /**
   * Reads a line written by {@link #encode()}.
   *
   * @throws IllegalArgumentException if the line does not have the five fields in their formats
   */
  public static LogRecord decode(String line) {
    Objects.requireNonNull(line, "line");
    String[] fields = line.split(FIELD_SEPARATOR, -1);
    if (fields.length != FIELD_COUNT) {
      throw new IllegalArgumentException(
          "a log record has " + FIELD_COUNT + " fields, this line has " + fields.length);
    }
    return new LogRecord(
        Long.parseLong(fields[0]),
        Instant.parse(fields[1]),
        Operation.valueOf(fields[2]),
        fields[3],
        fields[4]);
  }
}
