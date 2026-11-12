package io.plainround.seatwave.recovery.labs;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Lab 1's solution: RPO and RTO for four SeatWave stores, each with the business reason, set
 * against what the store's mechanism can achieve after losing region-a. An objective with no
 * reason, or one the mechanism cannot meet, is a finding. Takes no arguments.
 */
public final class Lab1StoreObjectives {

  private static final long SECONDS_PER_MINUTE = 60;

  /**
   * One store's objective and what its mechanism achieves, in seconds.
   *
   * @param store the store, as Module 34 and Module 39 name it
   * @param objectiveRpoSeconds data the business may lose
   * @param objectiveRtoSeconds time the business may be without it
   * @param reason why the business chose these numbers
   * @param mechanism how the store is protected
   * @param achievedRpoSeconds what that mechanism loses at worst (0 when nothing can be lost)
   * @param achievedRtoSeconds how long that mechanism takes to bring it back
   */
  record StoreObjective(
      String store,
      long objectiveRpoSeconds,
      long objectiveRtoSeconds,
      String reason,
      String mechanism,
      long achievedRpoSeconds,
      long achievedRtoSeconds) {
    StoreObjective {
      Objects.requireNonNull(store, "store");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(mechanism, "mechanism");
    }
  }

  private Lab1StoreObjectives() {}

  /** Prints the four objectives and every finding. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints the four objectives and every finding to {@code out}. */
  static void run(PrintStream out) {
    List<StoreObjective> objectives =
        List.of(
            new StoreObjective(
                "bookings (seatwave-db-a, seatwave-db-b)",
                300,
                3_600,
                "a lost booking is a fan with a ticket e-mail and no seat; the CFO accepts 5"
                    + " minutes because the payment provider's records rebuild the rest",
                "pilot light: a logical-replication copy per cluster (RecoveryObjectivesCli)",
                0,
                3_110),
            new StoreObjective(
                "vault (transit keys seatwave-tickets, seatwave-contacts)",
                0,
                3_600,
                "a key version lost is every ticket and e-mail address it sealed, lost for good"
                    + " (Module 39)",
                "snapshot after every key change and hourly, copied to region-b; unseal keys"
                    + " offline",
                0,
                1_500),
            new StoreObjective(
                "ticket PDFs (Module 29's bucket)",
                86_400,
                14_400,
                "every ticket can be rendered again from its booking; fans can show the booking"
                    + " reference meanwhile",
                "versioning and replication to region-b; re-render from bookings if behind",
                900,
                7_200),
            new StoreObjective(
                "seat-map cache (seatwave-cache)",
                0,
                600,
                "",
                "none: start empty and fill from the database's readers (Module 16)",
                0,
                300));
    out.printf(
        Locale.ROOT,
        "%-58s %7s %7s %8s %8s  %s%n",
        "store",
        "RPO",
        "RTO",
        "got RPO",
        "got RTO",
        "findings");
    for (StoreObjective objective : objectives) {
      StringBuilder findings = new StringBuilder();
      if (objective.reason().isBlank()) {
        findings.append("no business reason; ");
      }
      if (objective.achievedRpoSeconds() > objective.objectiveRpoSeconds()) {
        findings.append("RPO not met; ");
      }
      if (objective.achievedRtoSeconds() > objective.objectiveRtoSeconds()) {
        findings.append("RTO not met; ");
      }
      out.printf(
          Locale.ROOT,
          "%-58s %7s %7s %8s %8s  %s%n",
          objective.store(),
          minutes(objective.objectiveRpoSeconds()),
          minutes(objective.objectiveRtoSeconds()),
          minutes(objective.achievedRpoSeconds()),
          minutes(objective.achievedRtoSeconds()),
          findings.isEmpty() ? "none" : findings.toString().trim());
    }
  }

  private static String minutes(long seconds) {
    return seconds / SECONDS_PER_MINUTE + " min";
  }
}
