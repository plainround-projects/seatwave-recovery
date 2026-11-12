package io.plainround.seatwave.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.recovery.archive.LogRecord;
import io.plainround.seatwave.recovery.archive.SegmentArchive;
import io.plainround.seatwave.recovery.backup.BaseBackup;
import io.plainround.seatwave.recovery.chaos.ChaosExperimentCli;
import io.plainround.seatwave.recovery.restore.PointInTimeRestore;
import io.plainround.seatwave.recovery.restore.RestoreChecker;
import io.plainround.seatwave.recovery.restore.RestoreOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the module's one invariant: a restore is declared good only after it matches its manifest,
 * and no experiment runs without an abort condition. Each test breaks exactly one thing.
 */
class RecoveryInvariantTest {

  private static final Instant BACKUP_AT = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant TARGET = Instant.parse("2026-11-10T10:05:00Z");
  private static final int BOOKINGS_AFTER_BACKUP = 300;
  private static final int RECORDS_PER_SEGMENT = 10;
  private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(60);
  private static final Map<String, String> ROWS_AT_BACKUP =
      Map.of("b-000001", "A-1", "b-000002", "B-1");

  @TempDir Path folder;
  private Path backup;
  private Path target;
  private SegmentArchive archive;
  private List<LogRecord> changes;

  @BeforeEach
  void takeBackupAndArchive() throws IOException {
    Path data = Files.createDirectories(folder.resolve("data"));
    backup = Files.createDirectories(folder.resolve("backup"));
    target = Files.createDirectories(folder.resolve("target"));
    Path archived = Files.createDirectories(folder.resolve("archive"));
    PointInTimeRestore.writeBookings(
        data.resolve(PointInTimeRestore.BOOKINGS_FILE), ROWS_AT_BACKUP);
    BaseBackup.take(data, backup, new BaseBackup.Label(BACKUP_AT, 1));
    changes = new ArrayList<>();
    for (int bookingNumber = 1; bookingNumber <= BOOKINGS_AFTER_BACKUP; bookingNumber++) {
      changes.add(
          new LogRecord(
              bookingNumber,
              BACKUP_AT.plusSeconds(bookingNumber),
              LogRecord.Operation.BOOK,
              "b-1" + bookingNumber,
              "C-" + bookingNumber));
    }
    archive = new SegmentArchive(archived, RECORDS_PER_SEGMENT, ARCHIVE_TIMEOUT, Duration.ZERO);
    archive.archiveUntil(changes, TARGET.plus(ARCHIVE_TIMEOUT));
  }

  @Test
  void declaresARestoreGoodWhenItMatchesTheManifest() {
    Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome =
        PointInTimeRestore.restore(backup, archive, target, TARGET);

    assertThat(RestoreChecker.declare(outcome, manifestAt(TARGET)).isError()).isFalse();
  }

  @Test
  void refusesARestoreWhoseBackupFailsItsManifest() throws IOException {
    Path dataFile = backup.resolve(PointInTimeRestore.BOOKINGS_FILE);
    byte[] bytes = Files.readAllBytes(dataFile);
    bytes[0] ^= 1;
    Files.write(dataFile, bytes);

    Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome =
        PointInTimeRestore.restore(backup, archive, target, TARGET);

    assertThat(RestoreChecker.declare(outcome, manifestAt(TARGET)).isError()).isTrue();
    try (Stream<Path> left = Files.list(target)) {
      assertThat(left)
          .as("a rejected backup leaves nothing behind to be used by mistake")
          .isEmpty();
    }
  }

  @Test
  void refusesAReplayThatStopsAtAMissingSegment() throws IOException {
    Files.delete(archive.segmentsInOrder().get(RECORDS_PER_SEGMENT));

    Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome =
        PointInTimeRestore.restore(backup, archive, target, TARGET);

    assertThat(
            outcome
                .map(recovered -> "recovered")
                .recoverError(failure -> failure.getClass().getSimpleName()))
        .isEqualTo("StoppedShort");
    assertThat(RestoreChecker.declare(outcome, manifestAt(TARGET)).isError()).isTrue();
  }

  @Test
  void refusesARestoreThatDoesNotMatchTheMomentTheManifestDescribes() {
    Result<RestoreOutcome.Recovered, RestoreOutcome.Failure> outcome =
        PointInTimeRestore.restore(backup, archive, target, TARGET);

    assertThat(RestoreChecker.declare(outcome, manifestAt(TARGET.minusSeconds(1))).isError())
        .isTrue();
  }

  @Test
  void refusesAnExperimentWithoutAbortConditions() {
    assertThatThrownBy(() ->
            new ChaosExperimentCli.Experiment(
                "kill a writer", "holds recover in 60 s", 1, 35, 77, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no abort condition");
  }

  private RestoreChecker.DrillManifest manifestAt(Instant stopBefore) {
    return RestoreChecker.manifestAt(ROWS_AT_BACKUP, changes, stopBefore);
  }
}
