package io.plainround.seatwave.recovery.labs;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.support.Printed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabProgramsTest {

  @TempDir Path folder;

  @Test
  void lab1PrintsTheObjectivesAndTheirFindings() throws Exception {
    assertThat(Printed.by(Lab1StoreObjectives::run)).isEqualTo(Printed.golden("lab1Run.txt"));
  }

  @Test
  void lab2BringsTheRowsBackOnlyFromATargetBeforeTheMistake() throws Exception {
    assertThat(Printed.by(out -> Lab2ChosenMoment.run(out, folder)))
        .isEqualTo(Printed.golden("lab2Run.txt"));
  }

  @Test
  void lab3VerifiesTheScaledRestoreAndConfirmsTheFixedExperiment() throws Exception {
    String printed = Printed.by(out -> Lab3ScaledDrill.run(out, folder));

    assertThat(printed)
        .contains("restore + verify of 200 MB: VERIFIED")
        .contains("planning NO")
        .contains("verdict: hypothesis CONFIRMED");
  }
}
