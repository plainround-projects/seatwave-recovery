package io.plainround.seatwave.recovery.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 10's backup age counts only verified backups. */
class BackupHealthTest {

  private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");
  private static final BackupHealth.BackupRun VERIFIED =
      new BackupHealth.BackupRun(
          "20261111-010000F",
          Instant.parse("2026-11-11T01:30:00Z"),
          BackupHealth.Verification.PASSED);

  @Test
  void measuresTheAgeFromTheNewestVerifiedBackup() {
    assertThat(BackupHealth.lastSuccessAge(List.of(VERIFIED), NOW))
        .hasValue(Duration.ofMinutes(3_330));
  }

  @Test
  void doesNotCountANewerBackupThatNobodyVerified() {
    BackupHealth.BackupRun unverified =
        new BackupHealth.BackupRun(
            "20261113-010000F",
            Instant.parse("2026-11-13T01:30:00Z"),
            BackupHealth.Verification.NOT_RUN);

    assertThat(BackupHealth.newestGood(List.of(VERIFIED, unverified))).hasValue(VERIFIED);
  }

  @Test
  void doesNotCountANewerBackupWhoseVerificationFailed() {
    BackupHealth.BackupRun damaged =
        new BackupHealth.BackupRun(
            "20261113-010000F",
            Instant.parse("2026-11-13T01:30:00Z"),
            BackupHealth.Verification.FAILED);

    assertThat(BackupHealth.newestGood(List.of(damaged, VERIFIED))).hasValue(VERIFIED);
  }

  @Test
  void hasNoAgeWhenNoBackupWasEverVerified() {
    BackupHealth.BackupRun unverified =
        new BackupHealth.BackupRun("20261113-010000F", NOW, BackupHealth.Verification.NOT_RUN);

    assertThat(BackupHealth.lastSuccessAge(List.of(unverified), NOW)).isEmpty();
  }
}
