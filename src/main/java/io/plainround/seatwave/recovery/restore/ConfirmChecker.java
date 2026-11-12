package io.plainround.seatwave.recovery.restore;

import com.github.sviperll.result4j.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The checker of the real lab: the rule {@link RestoreChecker} enforces on the drill store, applied
 * to the booking ids a restored PostgreSQL holds. A restore is declared good only if every confirm
 * acknowledged before the target is present, no acknowledged confirm at or after the target is
 * present, and no row is present that the load generator never sent. Uncertain confirms may be
 * present or not. Stateless and thread-safe.
 */
public final class ConfirmChecker {

  /** What the restored copy must hold. */
  public sealed interface Expectation permits Before, Everything {}

  /**
   * A point-in-time restore: every acknowledged confirm committed before {@code target}, none at or
   * after it (the target is exclusive, as pgBackRest's {@code --target-exclusive}).
   */
  public record Before(Instant target) implements Expectation {
    /** Refuses a missing target. */
    public Before {
      Objects.requireNonNull(target, "target");
    }
  }

  /**
   * A restore to the end of an archive known to be complete, or a primary restarted after a crash:
   * every acknowledged confirm.
   */
  public record Everything() implements Expectation {}

  /**
   * A restore that matched the manifest.
   *
   * @param present acknowledged confirms found, as expected
   * @param absent acknowledged confirms at or after the target, absent as expected
   * @param uncertainPresent uncertain confirms that turned out to have committed
   */
  public record Match(int present, int absent, int uncertainPresent) {}

  /**
   * A restore that may not be used.
   *
   * @param reasons every difference found
   * @param missingCommits the commit times of the acknowledged confirms that should be present and
   *     are not, oldest first
   * @param isTailLoss true when every missing confirm committed after every acknowledged one that
   *     is present: the restore stopped early, as a replay at a gap or an archive that was behind
   *     does, and kept a consistent prefix; false when confirms are missing from the middle
   */
  public record Mismatch(List<String> reasons, List<Instant> missingCommits, boolean isTailLoss) {
    /** Copies both lists. */
    public Mismatch {
      reasons = List.copyOf(reasons);
      missingCommits = missingCommits.stream().sorted().toList();
    }

    /** Returns the commit time of the oldest missing confirm, if any is missing. */
    public Optional<Instant> oldestMissingCommit() {
      return missingCommits.stream().findFirst();
    }
  }

  private ConfirmChecker() {}

  /**
   * Compares the booking ids a restored copy holds with the manifest.
   *
   * @return the match, or every difference with what was lost
   */
  public static Result<Match, Mismatch> check(
      ConfirmManifest manifest, Set<String> presentBookingIds, Expectation expectation) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(presentBookingIds, "presentBookingIds");
    Objects.requireNonNull(expectation, "expectation");
    List<Map.Entry<String, Instant>> missing = new ArrayList<>();
    int present = 0;
    int absent = 0;
    int presentAfterTarget = 0;
    Optional<Instant> newestPresent = Optional.empty();
    for (Map.Entry<String, Instant> confirm : manifest.acknowledged().entrySet()) {
      boolean isExpected = isExpected(confirm.getValue(), expectation);
      boolean isPresent = presentBookingIds.contains(confirm.getKey());
      if (isPresent) {
        newestPresent = later(newestPresent, confirm.getValue());
      }
      if (isExpected && isPresent) {
        present++;
      } else if (isExpected) {
        missing.add(confirm);
      } else if (isPresent) {
        presentAfterTarget++;
      } else {
        absent++;
      }
    }
    long unknown =
        presentBookingIds.stream()
            .filter(bookingId ->
                !manifest.acknowledged().containsKey(bookingId)
                    && !manifest.uncertain().contains(bookingId))
            .count();
    int uncertainPresent =
        (int) manifest.uncertain().stream().filter(presentBookingIds::contains).count();
    List<String> reasons = new ArrayList<>();
    if (!missing.isEmpty()) {
      reasons.add(missing.size() + " acknowledged confirms are missing");
    }
    if (presentAfterTarget > 0) {
      reasons.add(presentAfterTarget + " confirms committed at or after the target are present");
    }
    if (unknown > 0) {
      reasons.add(unknown + " rows are present that the load generator never sent");
    }
    if (reasons.isEmpty()) {
      return Result.success(new Match(present, absent, uncertainPresent));
    }
    List<Instant> missingCommits = missing.stream().map(Map.Entry::getValue).toList();
    Optional<Instant> newestPresentCommit = newestPresent;
    boolean isTailLoss =
        missingCommits.stream()
            .min(Comparator.naturalOrder())
            .map(oldest -> newestPresentCommit.map(oldest::isAfter).orElse(true))
            .orElse(false);
    return Result.error(new Mismatch(reasons, missingCommits, isTailLoss));
  }

  /**
   * Returns the latest commit time among the acknowledged confirms the copy holds: how far the
   * restored data reaches, by the load generator's record.
   */
  public static Optional<Instant> newestPresentCommit(
      ConfirmManifest manifest, Set<String> presentBookingIds) {
    return manifest.acknowledged().entrySet().stream()
        .filter(confirm -> presentBookingIds.contains(confirm.getKey()))
        .map(Map.Entry::getValue)
        .max(Comparator.naturalOrder());
  }

  private static boolean isExpected(Instant committedAt, Expectation expectation) {
    return switch (expectation) {
      case Before before -> committedAt.isBefore(before.target());
      case Everything everything -> true;
    };
  }

  private static Optional<Instant> later(Optional<Instant> newest, Instant candidate) {
    return Optional.of(newest.filter(known -> known.isAfter(candidate)).orElse(candidate));
  }
}
