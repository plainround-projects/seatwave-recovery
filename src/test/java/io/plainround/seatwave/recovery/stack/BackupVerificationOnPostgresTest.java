package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.backup.BackupHealth;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Section 10's first metric on the real repository: a backup counts as a success only once
 * pgBackRest's verify called it valid, so a damaged newest backup leaves the older one as the
 * newest good backup.
 */
@Tag("stack")
class BackupVerificationOnPostgresTest {

  private static final Pattern OBJECT =
      Pattern.compile("<Key>([^<]+)</Key>.*?<Size>([0-9]+)</Size>");

  @TempDir Path folder;

  @Test
  void aDamagedNewestBackupIsNotTheNewestGoodOne() throws Exception {
    LabStack.pgbackrest("--type=full", "backup");
    LabStack.pgbackrest("--type=full", "backup");
    List<PgBackRestInfo.BackupSet> sets =
        PgBackRestInfo.backups(
            LabStack.pgbackrest("info", "--output=json", "--log-level-console=off"), "seatwave");
    String newest = sets.getLast().label();
    String older = sets.get(sets.size() - 2).label();
    String damaged = largestObjectOf(newest);
    Path copy = folder.resolve("object");
    assertThat(LabStack.s3("GET", "/" + damaged, "-o", copy.toString()).status()).isEqualTo(200);
    byte[] bytes = Files.readAllBytes(copy);
    bytes[bytes.length / 2] ^= 1;
    Files.write(copy, bytes);
    assertThat(LabStack.s3("PUT", "/" + damaged, "--data-binary", "@" + copy).status())
        .isEqualTo(200);

    Map<String, BackupHealth.Verification> verified =
        PgBackRestInfo.verified(
            LabStack.pgbackrest("verify", "--output=text", "--verbose", "--log-level-console=off"));
    bytes[bytes.length / 2] ^= 1;
    Files.write(copy, bytes);
    LabStack.s3("PUT", "/" + damaged, "--data-binary", "@" + copy);

    assertThat(verified).containsEntry(newest, BackupHealth.Verification.FAILED);
    assertThat(BackupHealth.newestGood(PgBackRestInfo.runs(sets, verified)))
        .hasValueSatisfying(good -> assertThat(good.label()).isEqualTo(older));
  }

  private static String largestObjectOf(String label) {
    LabStack.Ran listing =
        LabStack.s3("GET", "?list-type=2&prefix=seatwave/backup/seatwave/" + label + "/pg_data/");
    Matcher object = OBJECT.matcher(listing.output());
    String largest = "";
    long largestSize = -1;
    while (object.find()) {
      long size = Long.parseLong(object.group(2));
      if (size > largestSize) {
        largest = object.group(1);
        largestSize = size;
      }
    }
    assertThat(largest).as(listing.output()).isNotEmpty();
    return largest;
  }
}
