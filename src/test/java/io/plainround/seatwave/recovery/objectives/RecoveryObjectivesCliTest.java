package io.plainround.seatwave.recovery.objectives;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.support.Printed;
import org.junit.jupiter.api.Test;

class RecoveryObjectivesCliTest {

  @Test
  void printsTheTiersTheModuleQuotes() throws Exception {
    assertThat(Printed.by(RecoveryObjectivesCli::run)).isEqualTo(Printed.golden("objectives.txt"));
  }
}
