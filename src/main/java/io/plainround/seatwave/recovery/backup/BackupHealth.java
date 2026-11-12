package io.plainround.seatwave.recovery.backup;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Section 10's first metric, {@code backup_last_success_age_seconds}: the time since the newest
 * backup that finished AND passed its verification. A backup job that exited 0 has written bytes;
 * only a verified backup counts, so a backup whose verification failed or never ran leaves the age
 * growing until the alert fires. Stateless and thread-safe.
 */
public final class BackupHealth {

  /** What the verification of one backup found. */
  public enum Verification {
    /** pgBackRest's verify read every file of the backup and found it valid. */
    PASSED,
    /** verify found a missing or damaged file. */
    FAILED,
    /** nobody verified it. */
    NOT_RUN
  }

  /**
   * One backup as the repository lists it.
   *
   * @param label pgBackRest's label, for example {@code 20261112-191313F}
   * @param finishedAt when the backup stopped
   * @param verification what its verification found
   */
  public record BackupRun(String label, Instant finishedAt, Verification verification) {
    /** Refuses missing fields. */
    public BackupRun {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(finishedAt, "finishedAt");
      Objects.requireNonNull(verification, "verification");
    }
  }

  private BackupHealth() {}

  /** Returns the newest backup whose verification passed, if there is one. */
  public static Optional<BackupRun> newestGood(List<BackupRun> runs) {
    return runs.stream()
        .filter(run -> run.verification() == Verification.PASSED)
        .max(Comparator.comparing(BackupRun::finishedAt));
  }

  /**
   * Returns {@code backup_last_success_age_seconds} at {@code now}, or empty when no backup has
   * passed verification (the alert then fires on the absence, as Section 10's rule says).
   */
  public static Optional<Duration> lastSuccessAge(List<BackupRun> runs, Instant now) {
    Objects.requireNonNull(now, "now");
    return newestGood(runs).map(run -> Duration.between(run.finishedAt(), now));
  }
}
