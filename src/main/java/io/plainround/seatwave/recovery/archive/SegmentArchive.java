package io.plainround.seatwave.recovery.archive;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * The log archive of the drill store: a folder of numbered segment files, each a run of framed
 * records ({@code length 4 B | CRC32C 4 B | payload}, Module 26's frame). It plays PostgreSQL's
 * archiver: a segment is copied to the archive only once it is closed, and it closes when it is
 * full or when the archive timeout has passed since its first record ({@code archive_timeout}).
 * Whatever sits in the open segment when the primary is lost never reaches the archive; that tail
 * is the recovery point a restore from the archive can never get back. Not thread-safe: one drill
 * uses one archive.
 */
public final class SegmentArchive {

  /** The result of archiving a history up to the moment the primary was lost. */
  public record ArchiveSummary(
      int segmentsArchived,
      int recordsArchived,
      int recordsNeverArchived,
      Optional<Instant> lastArchivedCommit) {}

  /**
   * Why a segment cannot be read: a frame was torn or failed its checksum, so nothing after it in
   * the segment can be trusted.
   */
  public record Damaged(String reason) {}

  private static final String SEGMENT_SUFFIX = ".seg";
  private static final String SEGMENT_NAME_FORMAT = "%016d" + SEGMENT_SUFFIX;
  private static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES;

  private final Path archiveDirectory;
  private final int maxRecordsPerSegment;
  private final Duration archiveTimeout;
  private final Duration copyLag;

  /**
   * Creates an archive in {@code archiveDirectory}, which must exist.
   *
   * @param maxRecordsPerSegment records that fill a segment (PostgreSQL's is a size, 16 MB)
   * @param archiveTimeout the longest a segment with data stays open
   * @param copyLag the time from closing a segment to its copy being safe in the archive
   */
  public SegmentArchive(
      Path archiveDirectory, int maxRecordsPerSegment, Duration archiveTimeout, Duration copyLag) {
    this.archiveDirectory = Objects.requireNonNull(archiveDirectory, "archiveDirectory");
    if (maxRecordsPerSegment < 1) {
      throw new IllegalArgumentException("a segment holds at least 1 record");
    }
    if (archiveTimeout.isNegative() || archiveTimeout.isZero() || copyLag.isNegative()) {
      throw new IllegalArgumentException(
          "archive timeout must be positive and copy lag not negative");
    }
    this.maxRecordsPerSegment = maxRecordsPerSegment;
    this.archiveTimeout = archiveTimeout;
    this.copyLag = copyLag;
  }

  /**
   * Archives {@code history} as the primary's archiver would have done before it was lost at {@code
   * lostAt}: every segment that closed, and whose copy finished, before {@code lostAt}.
   */
  public ArchiveSummary archiveUntil(List<LogRecord> history, Instant lostAt) {
    List<LogRecord> openSegment = new ArrayList<>();
    int segmentNumber = 0;
    int recordsArchived = 0;
    Optional<Instant> lastArchivedCommit = Optional.empty();
    List<LogRecord> committedBeforeLoss =
        history.stream().filter(change -> change.committedAt().isBefore(lostAt)).toList();
    for (LogRecord change : committedBeforeLoss) {
      // The timeout closes a segment even if it is not full: this is what bounds the RPO.
      if (!openSegment.isEmpty() && !change.committedAt().isBefore(timeoutOf(openSegment))) {
        if (isSafeBefore(timeoutOf(openSegment), lostAt)) {
          writeSegment(++segmentNumber, openSegment);
          recordsArchived += openSegment.size();
          lastArchivedCommit = Optional.of(openSegment.getLast().committedAt());
        }
        openSegment.clear();
      }
      openSegment.add(change);
      if (openSegment.size() == maxRecordsPerSegment) {
        if (isSafeBefore(change.committedAt(), lostAt)) {
          writeSegment(++segmentNumber, openSegment);
          recordsArchived += openSegment.size();
          lastArchivedCommit = Optional.of(change.committedAt());
        }
        openSegment.clear();
      }
    }
    if (!openSegment.isEmpty() && isSafeBefore(timeoutOf(openSegment), lostAt)) {
      writeSegment(++segmentNumber, openSegment);
      recordsArchived += openSegment.size();
      lastArchivedCommit = Optional.of(openSegment.getLast().committedAt());
    }
    return new ArchiveSummary(
        segmentNumber,
        recordsArchived,
        committedBeforeLoss.size() - recordsArchived,
        lastArchivedCommit);
  }

  /** Returns the archive's segment files, ordered by their number. */
  public List<Path> segmentsInOrder() {
    try (Stream<Path> files = Files.list(archiveDirectory)) {
      return files
          .filter(file -> file.getFileName().toString().endsWith(SEGMENT_SUFFIX))
          .sorted()
          .toList();
    } catch (IOException listFailure) {
      throw new UncheckedIOException("cannot list archive " + archiveDirectory, listFailure);
    }
  }

  /** Returns the number in a segment file's name. */
  public static long segmentNumberOf(Path segment) {
    String name = segment.getFileName().toString();
    return Long.parseLong(name.substring(0, name.length() - SEGMENT_SUFFIX.length()));
  }

  /** Returns the file name segment {@code segmentNumber} has in the archive. */
  public static String segmentName(long segmentNumber) {
    return String.format(Locale.ROOT, SEGMENT_NAME_FORMAT, segmentNumber);
  }

  /**
   * Reads and checks every frame of one segment.
   *
   * @return the segment's records in order, or why the segment is damaged
   */
  public static Result<List<LogRecord>, Damaged> read(Path segment) {
    ByteBuffer bytes;
    try {
      bytes = ByteBuffer.wrap(Files.readAllBytes(segment));
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read segment " + segment, readFailure);
    }
    List<LogRecord> records = new ArrayList<>();
    while (bytes.hasRemaining()) {
      if (bytes.remaining() < HEADER_BYTES) {
        return Result.error(new Damaged("torn frame header at byte " + bytes.position()));
      }
      int length = bytes.getInt();
      int storedChecksum = bytes.getInt();
      // Check the length before trusting it, so a damaged length cannot drive an allocation.
      if (length <= 0 || length > bytes.remaining()) {
        return Result.error(
            new Damaged("bad frame length " + length + " in " + segment.getFileName()));
      }
      byte[] payload = new byte[length];
      bytes.get(payload);
      if (checksumOf(payload) != storedChecksum) {
        return Result.error(new Damaged("checksum mismatch in " + segment.getFileName()));
      }
      records.add(LogRecord.decode(new String(payload, StandardCharsets.UTF_8)));
    }
    return Result.success(List.copyOf(records));
  }

  private Instant timeoutOf(List<LogRecord> openSegment) {
    return openSegment.getFirst().committedAt().plus(archiveTimeout);
  }

  private boolean isSafeBefore(Instant closedAt, Instant lostAt) {
    return closedAt.plus(copyLag).isBefore(lostAt);
  }

  private void writeSegment(int segmentNumber, List<LogRecord> records) {
    ByteBuffer segment = ByteBuffer.allocate(framedSize(records));
    for (LogRecord change : records) {
      byte[] payload = change.encode().getBytes(StandardCharsets.UTF_8);
      segment.putInt(payload.length).putInt(checksumOf(payload)).put(payload);
    }
    try {
      Files.write(archiveDirectory.resolve(segmentName(segmentNumber)), segment.array());
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot archive segment " + segmentNumber, writeFailure);
    }
  }

  private static int framedSize(List<LogRecord> records) {
    return records.stream()
        .mapToInt(change -> HEADER_BYTES + change.encode().getBytes(StandardCharsets.UTF_8).length)
        .sum();
  }

  private static int checksumOf(byte[] payload) {
    CRC32C crc = new CRC32C();
    crc.update(payload);
    // CRC32C is a 32-bit value returned in a long; the cast keeps exactly those 32 bits.
    return (int) crc.getValue();
  }
}
