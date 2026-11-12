package io.plainround.seatwave.recovery.restore;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The JDBC checker's rule on sets of booking ids: each test breaks one part of it. */
class ConfirmCheckerTest {

  private static final Instant FIRST = Instant.parse("2026-11-12T19:39:25.001000Z");
  private static final Instant SECOND = Instant.parse("2026-11-12T19:39:25.002000Z");
  private static final Instant THIRD = Instant.parse("2026-11-12T19:39:25.003000Z");
  private static final String ID_1 = "00000000-0000-4000-8000-000000000001";
  private static final String ID_2 = "00000000-0000-4000-8000-000000000002";
  private static final String ID_3 = "00000000-0000-4000-8000-000000000003";
  private static final String UNCERTAIN = "00000000-0000-4000-8000-000000000009";
  private static final String STRANGER = "00000000-0000-4000-8000-0000000000ff";
  private static final ConfirmManifest MANIFEST =
      new ConfirmManifest(40, Map.of(ID_1, FIRST, ID_2, SECOND, ID_3, THIRD), Set.of(UNCERTAIN));
  private static final ConfirmChecker.Expectation BEFORE_THIRD = new ConfirmChecker.Before(THIRD);

  @Test
  void declaresACopyWithEveryConfirmBeforeTheTargetAndNoneAfter() {
    Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict =
        ConfirmChecker.check(MANIFEST, Set.of(ID_1, ID_2), BEFORE_THIRD);

    assertThat(verdict.discardError()).hasValue(new ConfirmChecker.Match(2, 1, 0));
  }

  @Test
  void refusesACopyThatHoldsAConfirmCommittedExactlyAtTheTarget() {
    // The target is exclusive: a restore that applied the change at the target stopped late.
    Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict =
        ConfirmChecker.check(MANIFEST, Set.of(ID_1, ID_2, ID_3), BEFORE_THIRD);

    assertThat(reasonsOf(verdict))
        .isEqualTo("1 confirms committed at or after the target are present");
  }

  @Test
  void refusesACopyThatStoppedShortAndSaysTheLossIsATail() {
    Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict =
        ConfirmChecker.check(MANIFEST, Set.of(ID_1), new ConfirmChecker.Everything());

    ConfirmChecker.Mismatch mismatch =
        verdict.map(match -> (ConfirmChecker.Mismatch) null).recoverError(found -> found);
    assertThat(mismatch.reasons()).containsExactly("2 acknowledged confirms are missing");
    assertThat(mismatch.oldestMissingCommit()).hasValue(SECOND);
    assertThat(mismatch.isTailLoss()).isTrue();
  }

  @Test
  void refusesACopyWithAHoleAndSaysTheLossIsNotATail() {
    Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict =
        ConfirmChecker.check(MANIFEST, Set.of(ID_1, ID_3), new ConfirmChecker.Everything());

    ConfirmChecker.Mismatch mismatch =
        verdict.map(match -> (ConfirmChecker.Mismatch) null).recoverError(found -> found);
    assertThat(mismatch.missingCommits()).containsExactly(SECOND);
    assertThat(mismatch.isTailLoss()).isFalse();
  }

  @Test
  void refusesACopyWithARowTheLoadGeneratorNeverSent() {
    Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict =
        ConfirmChecker.check(MANIFEST, Set.of(ID_1, ID_2, STRANGER), BEFORE_THIRD);

    assertThat(reasonsOf(verdict))
        .isEqualTo("1 rows are present that the load generator never sent");
  }

  @Test
  void acceptsAnUncertainConfirmWhetherItCommittedOrNot() {
    assertThat(
            ConfirmChecker.check(MANIFEST, Set.of(ID_1, ID_2, UNCERTAIN), BEFORE_THIRD)
                .discardError())
        .hasValue(new ConfirmChecker.Match(2, 1, 1));
    assertThat(ConfirmChecker.check(MANIFEST, Set.of(ID_1, ID_2), BEFORE_THIRD).isError())
        .isFalse();
  }

  @Test
  void reportsHowFarTheCopyReaches() {
    assertThat(ConfirmChecker.newestPresentCommit(MANIFEST, Set.of(ID_1, ID_2))).hasValue(SECOND);
    assertThat(ConfirmChecker.newestPresentCommit(MANIFEST, Set.of())).isEmpty();
  }

  private static String reasonsOf(Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict) {
    return verdict
        .map(match -> "declared")
        .recoverError(mismatch -> String.join("; ", mismatch.reasons()));
  }
}
