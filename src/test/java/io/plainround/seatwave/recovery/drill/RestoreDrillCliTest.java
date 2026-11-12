package io.plainround.seatwave.recovery.drill;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.support.Printed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreDrillCliTest {

  @TempDir Path folder;

  @Test
  void logsABackupAndAVerifiedRestoreForEachSize() throws Exception {
    String printed = Printed.by(out -> RestoreDrillCli.measuredDrill(out, folder));

    assertThat(printed.lines().filter(line -> line.contains("restore+verify"))).hasSize(3);
    assertThat(printed.lines().filter(line -> line.contains(" backup "))).hasSize(3);
  }

  @Test
  void modelsTodaysClusterAsFittingTheObjectiveOnlyWithFourStreams() throws Exception {
    String printed = Printed.by(RestoreDrillCli::model);

    assertThat(printed)
        .contains("one cluster today, Module 34 (200 GB)            0:13     0:03     0:03")
        .contains("1 stream(s) at 250 MB/s: 61,667 MB")
        .contains("4 stream(s) at 1,000 MB/s: 246,667 MB");
  }
}
