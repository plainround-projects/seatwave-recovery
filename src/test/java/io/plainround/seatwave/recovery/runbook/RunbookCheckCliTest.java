package io.plainround.seatwave.recovery.runbook;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.recovery.support.Printed;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each kind of finding, broken one at a time on a plan that is otherwise ready. */
class RunbookCheckCliTest {

  private static final RunbookCheckCli.Step DECLARE =
      new RunbookCheckCli.Step(
          "declare",
          "Declare",
          "incident-commander",
          "channel open",
          List.of("runbook@git-host"),
          List.of());
  private static final RunbookCheckCli.Step PROMOTE =
      new RunbookCheckCli.Step(
          "promote",
          "Promote",
          "database-on-call",
          "writes succeed",
          List.of("replicas@region-b"),
          List.of("declare"));

  @Test
  void findsNothingInAPlanThatIsReady() {
    assertThat(RunbookCheckCli.check(List.of(DECLARE, PROMOTE), "region-a")).isEmpty();
  }

  @Test
  void reportsAStepThatWaitsForALaterStep() {
    assertThat(RunbookCheckCli.check(List.of(PROMOTE, DECLARE), "region-a"))
        .containsExactly("step 1 (promote): waits for 'declare', which comes later in the plan");
  }

  @Test
  void reportsAStepThatWaitsForAStepThePlanDoesNotHave() {
    assertThat(RunbookCheckCli.check(List.of(PROMOTE), "region-a"))
        .containsExactly("step 1 (promote): waits for 'declare', which is not in the plan");
  }

  @Test
  void reportsAStepWithNoOwnerOrNoVerification() {
    RunbookCheckCli.Step nobody =
        new RunbookCheckCli.Step("declare", "Declare", "-", "-", List.of(), List.of());

    assertThat(RunbookCheckCli.check(List.of(nobody), "region-a"))
        .containsExactly(
            "step 1 (declare): NO OWNER for 'Declare'",
            "step 1 (declare): NO VERIFICATION; nobody can say it worked");
  }

  @Test
  void reportsANeedHeldOnlyInTheLostSite() {
    RunbookCheckCli.Step stranded =
        new RunbookCheckCli.Step(
            "declare",
            "Declare",
            "incident-commander",
            "channel open",
            List.of("runbook@region-a"),
            List.of());

    assertThat(RunbookCheckCli.check(List.of(stranded), "region-a"))
        .containsExactly(
            "step 1 (declare): NEEDS runbook@region-a, which is only in the lost site");
  }

  @Test
  void printsTheDraftAndTheFixedPlanAsTheModuleQuotes() throws Exception {
    assertThat(
            Printed.by(out ->
                RunbookCheckCli.run(
                    out,
                    "region-a",
                    List.of("dr/region-b-recovery-draft.tsv", "dr/region-b-recovery.tsv"))))
        .isEqualTo(Printed.golden("runbookCheck.txt"));
    assertThat(
            Printed.by(
                out -> RunbookCheckCli.run(out, "region-b", List.of("dr/region-b-recovery.tsv"))))
        .isEqualTo(Printed.golden("runbookCheckRegionB.txt"));
  }
}
