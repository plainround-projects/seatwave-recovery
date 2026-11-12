package io.plainround.seatwave.recovery.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.recovery.support.Printed;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChaosExperimentCliTest {

  @Test
  void printsTheExperimentsTheModuleQuotes() throws Exception {
    assertThat(Printed.by(ChaosExperimentCli::run)).isEqualTo(Printed.golden("chaos.txt"));
  }

  @Test
  void refusesABlastRadiusOutsideTheShards() {
    assertThatThrownBy(() ->
            new ChaosExperimentCli.Experiment(
                "kill every writer twice",
                "nothing moves",
                65,
                35,
                77,
                List.of(ChaosExperimentCli.AbortCondition.FAST_BURN_PAGE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blast radius");
  }
}
