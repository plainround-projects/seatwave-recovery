package io.plainround.seatwave.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.support.Printed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deterministic programs print exactly what the module quotes; a change to any rule they show
 * changes their output and fails here.
 */
class ProgramOutputTest {

  @TempDir Path folder;

  @Test
  void pitrDrillPrintsTheFourRestoresTheModuleQuotes() throws Exception {
    assertThat(Printed.by(out -> PitrDrillCli.run(out, folder)))
        .isEqualTo(Printed.golden("pitrDrill.txt"));
  }
}
