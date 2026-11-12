package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lab 3's step 5 on the lab: the primary is killed while confirms arrive and started again. Crash
 * recovery from its own log must keep every acknowledged confirm, and the load generator's pool
 * must find the database again without a restart.
 */
@Tag("stack")
class PrimaryKillOnPostgresTest {

  private static final long KILL_AFTER_MILLIS = 4_000;

  @TempDir Path folder;

  @Test
  void keepsEveryAcknowledgedConfirmWhenThePrimaryIsKilledUnderLoad() throws Exception {
    Path manifest = folder.resolve("manifest.txt");
    LabStack.Ran load;
    try (ExecutorService loader = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<LabStack.Ran> running =
          loader.submit(() ->
              LabStack.run(
                  List.of(
                      "java",
                      "-cp",
                      System.getProperty("java.class.path"),
                      ConfirmLoadCli.class.getName(),
                      "config/stack.properties",
                      "3000",
                      "200",
                      manifest.toString())));
      Thread.sleep(KILL_AFTER_MILLIS);
      assertThat(LabStack.compose("kill", "primary").status()).isZero();
      assertThat(LabStack.compose("start", "primary").status()).isZero();
      load = running.get();
    }

    LabStack.Ran check =
        LabStack.run(
            List.of(
                "java",
                "-cp",
                System.getProperty("java.class.path"),
                RestoreCheckCli.class.getName(),
                "config/stack.properties",
                manifest.toString(),
                "--everything",
                "primary"));

    assertThat(load.output())
        .as(load.output())
        .contains("failures: ")
        .contains("the first confirm acknowledged after them");
    assertThat(check.status()).as(check.output()).isZero();
    assertThat(check.output()).contains("checker: DECLARED GOOD");
  }
}
