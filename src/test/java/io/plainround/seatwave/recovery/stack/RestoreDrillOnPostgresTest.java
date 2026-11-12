package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.restore.ConfirmManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The drill on real PostgreSQL and pgBackRest: a restore to a chosen moment holds every confirm
 * acknowledged before it and none after, and a restore whose archive lost or damaged a segment is
 * refused by the checker. Each test takes a fresh full backup first, so its replay never crosses a
 * segment another test removed.
 */
@Tag("stack")
class RestoreDrillOnPostgresTest {

  private static final String CONFIRMS = "1200";
  private static final String PER_SECOND = "200";
  // Confirms committed before the target: the target is the commit time of confirm 601.
  private static final int BEFORE_TARGET = 600;
  private static final long SWITCH_EVERY_MILLIS = 1_500;

  @TempDir Path folder;

  @AfterEach
  void unhideSegments() {
    LabStack.removeDeleteMarkers();
  }

  @Test
  void restoresToAChosenMomentWithEveryConfirmBeforeItAndNoneAfter() throws Exception {
    LabStack.pgbackrest("--type=full", "backup");
    Path manifest = load(false);
    Instant target = commitTimes(manifest).get(BEFORE_TARGET);

    LabStack.Ran drill = drill(manifest, "--before", target.toString());

    assertThat(drill.status()).as(drill.output()).isZero();
    assertThat(drill.output())
        .contains("checker: DECLARED GOOD, 600 acknowledged confirms present and 600 absent");
  }

  @Test
  void refusesARestoreCheckedAgainstAnEarlierMomentThanItStoppedAt() throws Exception {
    LabStack.pgbackrest("--type=full", "backup");
    Path manifest = load(false);
    List<Instant> commits = commitTimes(manifest);
    drill(manifest, "--before", commits.get(BEFORE_TARGET).toString());

    // The copy stopped before confirm 601; asking for a copy that stops before confirm 600 finds
    // one confirm too many.
    LabStack.Ran check = check(manifest, "--before", commits.get(BEFORE_TARGET - 1).toString());

    assertThat(check.status()).as(check.output()).isEqualTo(1);
    assertThat(check.output())
        .contains("REFUSED, 1 confirms committed at or after the target are present");
  }

  @Test
  void refusesAReplayThatStoppedAtAMissingSegment() throws Exception {
    LabStack.pgbackrest("--type=full", "backup");
    Path manifest = load(true);
    hideMiddleSegment();

    LabStack.Ran drill = drill(manifest, "--end");

    // PostgreSQL replays to the gap and opens as if the archive had ended there: only the checker
    // can tell that the copy is short.
    assertThat(drill.status()).as(drill.output()).isEqualTo(1);
    assertThat(drill.output())
        .contains("checker: REFUSED")
        .contains("acknowledged confirms are missing")
        .contains("all after the newest one present (the copy stops early)");
  }

  @Test
  void refusesAPointInTimeRestoreWhoseTargetLiesBeyondAMissingSegment() throws Exception {
    LabStack.pgbackrest("--type=full", "backup");
    Path manifest = load(true);
    List<Instant> commits = commitTimes(manifest);
    hideMiddleSegment();

    LabStack.Ran drill = drill(manifest, "--before", commits.getLast().toString());

    // With a target set, PostgreSQL itself refuses to open a copy that cannot reach it.
    assertThat(drill.status()).as(drill.output()).isEqualTo(1);
    assertThat(drill.output())
        .contains("REFUSED: the restored server stopped during replay")
        .contains("recovery ended before configured recovery target was reached");
  }

  @Test
  void refusesAReplayThatMeetsADamagedSegment() throws Exception {
    LabStack.pgbackrest("--type=full", "backup");
    Path manifest = load(true);
    String damaged = middleSegmentKey();
    // A new version of the object with one byte changed: what a bad copy or bit rot leaves.
    Path copy = folder.resolve("segment");
    LabStack.Ran original = LabStack.s3("GET", "/" + damaged, "-o", copy.toString());
    assertThat(original.status()).isEqualTo(200);
    byte[] bytes = Files.readAllBytes(copy);
    bytes[bytes.length / 2] ^= 1;
    Files.write(copy, bytes);
    assertThat(LabStack.s3("PUT", "/" + damaged, "--data-binary", "@" + copy).status())
        .isEqualTo(200);

    LabStack.Ran drill = drill(manifest, "--end");
    String verify = LabStack.pgbackrest("verify", "--output=text", "--verbose");
    LabStack.s3("PUT", "/" + damaged, "--data-binary", "@" + original(copy, bytes));

    assertThat(drill.status()).as(drill.output()).isEqualTo(1);
    assertThat(drill.output())
        .contains("checker: REFUSED")
        .contains("acknowledged confirms are missing");
    // verify exits 0 here too; what it printed is the finding.
    assertThat(verify).contains("status: error").contains("checksum invalid: 1");
  }

  private Path original(Path copy, byte[] damagedBytes) throws Exception {
    damagedBytes[damagedBytes.length / 2] ^= 1;
    Files.write(copy, damagedBytes);
    return copy;
  }

  /** Sends the confirms; with {@code switching}, closes a segment every 1.5 s meanwhile. */
  private Path load(boolean switching) throws Exception {
    Path manifest = folder.resolve("manifest.txt");
    StackSettings settings = LabStack.settings();
    try (ExecutorService switcher = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> switches = null;
      if (switching) {
        switches =
            switcher.submit(() -> {
              while (!Thread.currentThread().isInterrupted()) {
                LabStack.sqlOne(settings, settings.primaryUrl(), "SELECT pg_switch_wal()::text");
                Thread.sleep(SWITCH_EVERY_MILLIS);
              }
              return null;
            });
      }
      ConfirmLoadCli.main(
          new String[] {"config/stack.properties", CONFIRMS, PER_SECOND, manifest.toString()});
      if (switches != null) {
        switches.cancel(true);
      }
    }
    // check closes the last segment and waits until it is in the repository.
    LabStack.pgbackrest("check");
    return manifest;
  }

  private static List<Instant> commitTimes(Path manifest) {
    return ConfirmManifest.read(manifest)
        .discardError()
        .orElseThrow()
        .acknowledged()
        .values()
        .stream()
        .sorted()
        .toList();
  }

  /** Hides the segment in the middle of the load behind a delete marker (Object Lock keeps it). */
  private static void hideMiddleSegment() {
    assertThat(LabStack.s3("DELETE", "/" + middleSegmentKey()).status()).isEqualTo(204);
  }

  private static String middleSegmentKey() {
    List<String> segments =
        LabStack.archivedObjects("0000000").stream()
            .filter(key -> !key.endsWith(".backup") && !key.contains(".history"))
            .filter(key -> key.matches(".*/[0-9A-F]{24}-[0-9a-f]{40}\\.zst"))
            .sorted()
            .toList();
    // The newest few segments were written during this test's load, after its fresh backup.
    return segments.get(segments.size() - 3);
  }

  private static LabStack.Ran drill(Path manifest, String... mode) {
    List<String> command =
        new java.util.ArrayList<>(List.of("scripts/restore-drill.sh", manifest.toString()));
    command.addAll(List.of(mode));
    command.addAll(List.of("--run", "--yes"));
    return LabStack.run(command);
  }

  private static LabStack.Ran check(Path manifest, String... expectation) {
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                "java",
                "-cp",
                System.getProperty("java.class.path"),
                RestoreCheckCli.class.getName(),
                "config/stack.properties",
                manifest.toString()));
    command.addAll(List.of(expectation));
    command.add("restore");
    return LabStack.run(command);
  }
}
