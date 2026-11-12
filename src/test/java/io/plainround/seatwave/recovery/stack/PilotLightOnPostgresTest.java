package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pilot light's mechanism between two PostgreSQL servers on this machine (a same-machine
 * stand-in for two regions): the copy receives every acknowledged confirm by logical replication,
 * and after promotion it accepts new confirms, which needs its sequences advanced first.
 */
@Tag("stack")
class PilotLightOnPostgresTest {

  private static final long SYNC_POLL_NANOS = 200_000_000L;
  private static final int SYNC_POLLS = 300;

  @TempDir Path folder;

  @BeforeEach
  void startCopy() throws SQLException {
    LabStack.Ran up = LabStack.compose("up", "-d", "--wait", "region-b-copy");
    assertThat(up.status()).as(up.output()).isZero();
    PilotLightCli.subscribe(LabStack.settings());
  }

  @AfterEach
  void removeCopy() throws SQLException {
    LabStack.compose("rm", "-sfv", "region-b-copy");
    LabStack.run(List.of("docker", "volume", "rm", "-f", LabStack.PROJECT + "_region-b-data"));
    // The copy's slot stays on the primary after a promotion or a removal; drop it so the primary
    // does not keep log for a subscriber that is gone.
    LabStack.dropSlot(PilotLightPromotion.SUBSCRIPTION);
  }

  @Test
  void theCopyHoldsEveryAcknowledgedConfirm() throws Exception {
    Path manifest = folder.resolve("manifest.txt");
    ConfirmLoadCli.main(
        new String[] {"config/stack.properties", "500", "200", manifest.toString()});
    waitUntilCopied(manifest);

    LabStack.Ran check = checkCopy(manifest);

    assertThat(check.status()).as(check.output()).isZero();
    assertThat(check.output())
        .contains("checker: DECLARED GOOD, 500 acknowledged confirms present");
  }

  @Test
  void thePromotedCopyAcceptsANewConfirm() throws Exception {
    StackSettings settings = LabStack.settings();
    Path manifest = folder.resolve("manifest.txt");
    ConfirmLoadCli.main(
        new String[] {"config/stack.properties", "300", "200", manifest.toString()});
    waitUntilCopied(manifest);

    try (java.sql.Connection copy =
        LabDatabase.connect(settings, settings.copyUrl(), settings.admin())) {
      new PilotLightPromotion(settings.sequenceMargin()).promote(copy);
    }

    // Without the sequences advanced, this insert takes customer_id and outbox_id values the copy
    // already holds and fails with a duplicate key.
    assertThat(PilotLightCli.confirmOnCopy(settings)).isNotNull();
  }

  private static void waitUntilCopied(Path manifest) {
    for (int poll = 0; poll < SYNC_POLLS; poll++) {
      if (checkCopy(manifest).status() == 0) {
        return;
      }
      LockSupport.parkNanos(SYNC_POLL_NANOS);
    }
    throw new IllegalStateException("the copy did not catch up within a minute");
  }

  private static LabStack.Ran checkCopy(Path manifest) {
    return LabStack.run(
        List.of(
            "java",
            "-cp",
            System.getProperty("java.class.path"),
            RestoreCheckCli.class.getName(),
            "config/stack.properties",
            manifest.toString(),
            "--everything",
            "copy"));
  }
}
