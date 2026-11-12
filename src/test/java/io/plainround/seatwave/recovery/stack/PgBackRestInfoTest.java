package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.recovery.backup.BackupHealth;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reads pgBackRest's own output, captured from the lab on 12 November 2026. */
class PgBackRestInfoTest {

  private static final String VERIFY_TEXT =
      """
      stanza: seatwave
      status: error
        archiveId: 18-1, total WAL checked: 23, total valid WAL: 22
          missing: 0, checksum invalid: 1, size invalid: 0, other: 0
        backup: 20261112-193917F, status: valid, total files checked: 1286, total valid files: 1286
          missing: 0, checksum invalid: 0, size invalid: 0, other: 0
        backup: 20261112-194316F, status: invalid, total files checked: 1289, total valid files: 1288
          missing: 0, checksum invalid: 1, size invalid: 0, other: 0
      """;

  @Test
  void readsTheBackupSetsOfTheStanza() throws Exception {
    String json =
        Files.readString(
            Path.of("src/test/resources/pgbackrest-info.json"), StandardCharsets.UTF_8);

    assertThat(PgBackRestInfo.backups(json, "seatwave"))
        .containsExactly(
            new PgBackRestInfo.BackupSet(
                "20261112-193917F",
                Instant.ofEpochSecond(1_794_512_359),
                "0/7000028",
                32_451_632,
                3_976_576));
  }

  @Test
  void refusesADocumentWithoutTheStanza() throws Exception {
    String json =
        Files.readString(
            Path.of("src/test/resources/pgbackrest-info.json"), StandardCharsets.UTF_8);

    assertThatThrownBy(() -> PgBackRestInfo.backups(json, "other"))
        .hasMessageContaining("no stanza other");
  }

  @Test
  void readsEachBackupsVerificationFromWhatVerifyPrinted() {
    assertThat(PgBackRestInfo.verified(VERIFY_TEXT))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "20261112-193917F", BackupHealth.Verification.PASSED,
                "20261112-194316F", BackupHealth.Verification.FAILED));
  }

  @Test
  void treatsABackupVerifyDidNotListAsNotVerified() {
    PgBackRestInfo.BackupSet set =
        new PgBackRestInfo.BackupSet("20261112-200000F", Instant.EPOCH, "0/9000028", 1, 1);

    assertThat(PgBackRestInfo.runs(List.of(set), Map.of()))
        .extracting(BackupHealth.BackupRun::verification)
        .containsExactly(BackupHealth.Verification.NOT_RUN);
  }
}
