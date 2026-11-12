package io.plainround.seatwave.recovery.restore;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.recovery.archive.LogRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The one door through which a restore becomes a declared recovery. It compares what a restore
 * rebuilt with a drill manifest, the row count and a SHA-256 over every row, computed from an
 * independent record of the truth at the target moment. A restore is declared good only if both
 * match; any difference, any rejected backup and any replay that stopped short is refused with its
 * reasons. {@link Declared} has a private constructor, so no code outside this class can make one.
 * Stateless and thread-safe.
 */
public final class RestoreChecker {

  /**
   * What a correct restore to {@code stopBefore} must contain.
   *
   * @param stopBefore the recovery target the manifest describes
   * @param bookingCount the number of booking rows
   * @param bookingsSha256 SHA-256 over every {@code bookingId TAB seat} line, sorted by id
   */
  public record DrillManifest(Instant stopBefore, int bookingCount, String bookingsSha256) {
    /** Checks the manifest at construction. */
    public DrillManifest {
      Objects.requireNonNull(stopBefore, "stopBefore");
      Objects.requireNonNull(bookingsSha256, "bookingsSha256");
      if (bookingCount < 0) {
        throw new IllegalArgumentException("bookingCount cannot be negative");
      }
    }
  }

  /** A restore that matched its manifest. Only {@link RestoreChecker#declare} creates one. */
  public static final class Declared {
    private final RestoreOutcome.RestoredState state;

    private Declared(RestoreOutcome.RestoredState state) {
      this.state = state;
    }

    /** Returns the verified rows and how far the log was replayed. */
    public RestoreOutcome.RestoredState state() {
      return state;
    }
  }

  /** A restore that may not be used, with every reason found. */
  public record Refused(List<String> reasons) {
    /** Copies the reasons. */
    public Refused {
      reasons = List.copyOf(reasons);
    }
  }

  private static final String DIGEST_ALGORITHM = "SHA-256";

  private RestoreChecker() {}

  /**
   * Builds the manifest from the drill's independent record: the rows at the backup plus every
   * change committed before {@code stopBefore}. In production the same numbers come from a source
   * the restore did not produce (Section 6c).
   */
  public static DrillManifest manifestAt(
      Map<String, String> rowsAtBackup, List<LogRecord> changesAfterBackup, Instant stopBefore) {
    SortedMap<String, String> truth = new TreeMap<>(rowsAtBackup);
    for (LogRecord change : changesAfterBackup) {
      if (!change.committedAt().isBefore(stopBefore)) {
        break;
      }
      switch (change.operation()) {
        case BOOK -> truth.put(change.bookingId(), change.seat());
        case DELETE -> truth.remove(change.bookingId());
        case HEARTBEAT -> {
          // A heartbeat changes no booking row.
        }
      }
    }
    return new DrillManifest(stopBefore, truth.size(), digestOf(truth));
  }

  /**
   * Declares the restore good, or refuses it with every reason.
   *
   * @return the declared recovery, or the refusal with every reason found
   */
  public static Result<Declared, Refused> declare(
      Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome, DrillManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    return Objects.requireNonNull(outcome, "outcome")
        .map(recovered -> matchManifest(recovered.state(), manifest))
        .recoverError(failure -> judgeFailure(failure, manifest));
  }

  private static Result<Declared, Refused> matchManifest(
      RestoreOutcome.RestoredState state, DrillManifest manifest) {
    List<String> reasons = compare(state, manifest, null);
    return reasons.isEmpty()
        ? Result.success(new Declared(state))
        : Result.error(new Refused(reasons));
  }

  // An exhaustive switch over the sealed failure, with no default: a new kind of failure is a
  // compile error here until someone decides how the checker treats it.
  private static Result<Declared, Refused> judgeFailure(
      RestoreOutcome.Failure failure, DrillManifest manifest) {
    return switch (failure) {
      case RestoreOutcome.BackupRejected rejected ->
          Result.error(new Refused(List.of("backup rejected: " + rejected.reason())));
      case RestoreOutcome.StoppedShort stopped ->
          Result.error(
              new Refused(
                  compare(stopped.state(), manifest, "replay stopped short: " + stopped.reason())));
    };
  }

  private static List<String> compare(
      RestoreOutcome.RestoredState state, DrillManifest manifest, String firstReason) {
    List<String> reasons = new ArrayList<>();
    if (firstReason != null) {
      reasons.add(firstReason);
    }
    if (state.bookingCount() != manifest.bookingCount()) {
      reasons.add(
          "row count " + state.bookingCount() + ", manifest says " + manifest.bookingCount());
    }
    if (!digestOf(state.bookings()).equals(manifest.bookingsSha256())) {
      reasons.add("row checksum differs from the manifest");
    }
    return reasons;
  }

  private static String digestOf(SortedMap<String, String> rows) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("every JDK must provide " + DIGEST_ALGORITHM, missing);
    }
    rows.forEach((bookingId, seat) ->
        digest.update((bookingId + "\t" + seat + "\n").getBytes(StandardCharsets.UTF_8)));
    return HexFormat.of().formatHex(digest.digest());
  }
}
