package io.plainround.seatwave.recovery.runbook;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Checks a recovery plan written as data, one step a line, and lists every step that would fail on
 * the day: a step with no owner, a step with no verification, a step that needs something kept only
 * in the site that was lost (credentials, state, images, the runbook itself), a step that waits for
 * a step the plan does not have, and a step that waits for a later one. A plan is ready only with
 * no finding. Usage: {@code RunbookCheckCli <lost site> <plan.tsv>...}.
 */
public final class RunbookCheckCli {

  /**
   * One step of the plan.
   *
   * @param id short name other steps refer to
   * @param action what is done
   * @param owner the role that does it, or {@code -}
   * @param verification how the owner proves it worked, or {@code -}
   * @param needs each resource as {@code name@site+site}, the sites that hold a usable copy
   * @param after the ids of the steps that must be done first
   */
  record Step(
      String id,
      String action,
      String owner,
      String verification,
      List<String> needs,
      List<String> after) {
    Step {
      Objects.requireNonNull(id, "id");
      needs = List.copyOf(needs);
      after = List.copyOf(after);
    }
  }

  private static final String COMMENT = "#";
  private static final String NONE = "-";
  private static final String SITE_MARK = "@";
  private static final String SITE_SEPARATOR = "\\+";
  private static final String LIST_SEPARATOR = ",";
  private static final int COLUMNS = 6;

  private RunbookCheckCli() {}

  /** Checks each plan against the loss of {@code arguments[0]}. */
  public static void main(String[] arguments) {
    if (arguments.length < 2) {
      throw new IllegalArgumentException("usage: RunbookCheckCli <lost site> <plan.tsv>...");
    }
    run(System.out, arguments[0], Arrays.asList(arguments).subList(1, arguments.length));
  }

  /** Checks each plan file against the loss of {@code lostSite}, printing to {@code out}. */
  static void run(PrintStream out, String lostSite, List<String> planFiles) {
    for (String planFile : planFiles) {
      List<Step> steps = read(Path.of(planFile));
      List<String> findings = check(steps, lostSite);
      out.printf(
          Locale.ROOT,
          "%s, %d steps, if %s is lost:%n",
          Path.of(planFile).getFileName(),
          steps.size(),
          lostSite);
      findings.forEach(finding -> out.println("  " + finding));
      out.println(
          findings.isEmpty()
              ? "  plan READY: every step has an owner and a check, and nothing needs " + lostSite
              : "  plan NOT READY: " + findings.size() + " findings\n");
    }
  }

  /** Returns every finding, in plan order. */
  static List<String> check(List<Step> steps, String lostSite) {
    Map<String, Integer> position = new HashMap<>();
    for (int index = 0; index < steps.size(); index++) {
      position.put(steps.get(index).id(), index);
    }
    List<String> findings = new ArrayList<>();
    for (int index = 0; index < steps.size(); index++) {
      Step step = steps.get(index);
      String label = "step " + (index + 1) + " (" + step.id() + ")";
      if (step.owner().equals(NONE)) {
        findings.add(label + ": NO OWNER for '" + step.action() + "'");
      }
      if (step.verification().equals(NONE)) {
        findings.add(label + ": NO VERIFICATION; nobody can say it worked");
      }
      for (String need : step.needs()) {
        List<String> sites =
            Arrays.asList(need.substring(need.indexOf(SITE_MARK) + 1).split(SITE_SEPARATOR));
        // A copy that exists only where the disaster is does not exist for the recovery.
        if (sites.stream().allMatch(site -> site.equals(lostSite))) {
          findings.add(label + ": NEEDS " + need + ", which is only in the lost site");
        }
      }
      for (String earlier : step.after()) {
        Integer earlierIndex = position.get(earlier);
        if (earlierIndex == null) {
          findings.add(label + ": waits for '" + earlier + "', which is not in the plan");
        } else if (earlierIndex > index) {
          findings.add(label + ": waits for '" + earlier + "', which comes later in the plan");
        }
      }
    }
    return findings;
  }

  private static List<Step> read(Path planFile) {
    List<String> lines;
    try {
      lines = Files.readAllLines(planFile, StandardCharsets.UTF_8);
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read plan " + planFile, readFailure);
    }
    List<Step> steps = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank() || line.startsWith(COMMENT)) {
        continue;
      }
      String[] columns = line.split("\t", -1);
      if (columns.length != COLUMNS) {
        throw new IllegalArgumentException(
            planFile.getFileName()
                + ": a step has "
                + COLUMNS
                + " columns, found "
                + columns.length
                + " in: "
                + line);
      }
      steps.add(
          new Step(
              columns[0], columns[1], columns[2], columns[3], list(columns[4]), list(columns[5])));
    }
    return steps;
  }

  private static List<String> list(String column) {
    return column.equals(NONE) ? List.of() : Arrays.asList(column.split(LIST_SEPARATOR));
  }
}
