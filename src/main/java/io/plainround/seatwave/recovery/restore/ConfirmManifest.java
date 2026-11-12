package io.plainround.seatwave.recovery.restore;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The drill manifest of the real lab: every confirm the load generator sent, written by the load
 * generator and never by the database it checks. An acknowledged confirm carries the commit
 * timestamp PostgreSQL recorded for it (track_commit_timestamp), the same clock a recovery target
 * is compared with. A confirm whose answer never arrived (the primary was killed while it was in
 * flight) is uncertain: it may or may not have committed, so a restored copy may hold it or not.
 * Immutable and thread-safe.
 *
 * @param eventId the event the load generator booked seats of; the checker reads only its rows
 * @param acknowledged booking id to commit time, for every confirm the client saw commit
 * @param uncertain booking ids whose commit the client never saw answered
 */
public record ConfirmManifest(
    long eventId, Map<String, Instant> acknowledged, Set<String> uncertain) {

  /** The first line of a manifest file, so a file of another kind is refused. */
  public static final String HEADER = "# seatwave-recovery drill manifest 1";

  private static final String EVENT = "EVENT";
  private static final String ACKNOWLEDGED = "ACKNOWLEDGED";
  private static final String UNCERTAIN = "UNCERTAIN";
  private static final String NO_TIME = "-";
  private static final String SEPARATOR = "\t";
  private static final int FIELDS = 3;
  private static final Pattern BOOKING_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** Copies both collections and refuses an id that is both acknowledged and uncertain. */
  public ConfirmManifest {
    acknowledged = Map.copyOf(Objects.requireNonNull(acknowledged, "acknowledged"));
    uncertain = Set.copyOf(Objects.requireNonNull(uncertain, "uncertain"));
    for (String bookingId : uncertain) {
      if (acknowledged.containsKey(bookingId)) {
        throw new IllegalArgumentException(bookingId + " cannot be acknowledged and uncertain");
      }
    }
  }

  /**
   * Writes the manifest as text, acknowledged confirms in commit order, then the uncertain ones.
   */
  public void write(Path file) {
    List<String> lines = new ArrayList<>();
    lines.add(HEADER);
    lines.add(EVENT + SEPARATOR + eventId);
    acknowledged.entrySet().stream()
        .sorted(
            Map.Entry.<String, Instant>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
        .forEach(entry ->
            lines.add(
                String.join(SEPARATOR, ACKNOWLEDGED, entry.getKey(), entry.getValue().toString())));
    new TreeSet<>(uncertain)
        .forEach(bookingId -> lines.add(String.join(SEPARATOR, UNCERTAIN, bookingId, NO_TIME)));
    try {
      Files.write(file, lines, StandardCharsets.UTF_8);
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot write the drill manifest " + file, writeFailure);
    }
  }

  /**
   * Reads a manifest written by {@link #write}.
   *
   * @return the manifest, or every problem found, each naming its line
   */
  public static Result<ConfirmManifest, List<String>> read(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException readFailure) {
      return Result.error(
          List.of("cannot read the drill manifest " + file + ": " + readFailure.getMessage()));
    }
    if (lines.size() < 2
        || !lines.getFirst().equals(HEADER)
        || !lines.get(1).startsWith(EVENT + SEPARATOR)) {
      return Result.error(
          List.of(file + " does not start with '" + HEADER + "' and an EVENT line"));
    }
    long eventId;
    try {
      eventId = Long.parseLong(lines.get(1).substring(EVENT.length() + SEPARATOR.length()));
    } catch (NumberFormatException notANumber) {
      return Result.error(List.of(file + ": line 2 must be 'EVENT TAB <event id>'"));
    }
    Map<String, Instant> acknowledged = new TreeMap<>();
    Set<String> uncertain = new TreeSet<>();
    List<String> problems = new ArrayList<>();
    for (int lineNumber = 3; lineNumber <= lines.size(); lineNumber++) {
      String[] fields = lines.get(lineNumber - 1).split(SEPARATOR, -1);
      String where = "line " + lineNumber + ": ";
      if (fields.length != FIELDS || !BOOKING_ID.matcher(fields[1]).matches()) {
        problems.add(where + "expected '<ACKNOWLEDGED|UNCERTAIN> TAB <booking uuid> TAB <time>'");
        continue;
      }
      if (acknowledged.containsKey(fields[1]) || uncertain.contains(fields[1])) {
        problems.add(where + "booking " + fields[1] + " appears twice");
        continue;
      }
      switch (fields[0]) {
        case ACKNOWLEDGED -> {
          try {
            acknowledged.put(fields[1], Instant.parse(fields[2]));
          } catch (DateTimeParseException notATime) {
            problems.add(where + "'" + fields[2] + "' is not an ISO-8601 UTC time");
          }
        }
        case UNCERTAIN -> uncertain.add(fields[1]);
        default ->
            problems.add(where + "the status '" + fields[0] + "' is not ACKNOWLEDGED or UNCERTAIN");
      }
    }
    return problems.isEmpty()
        ? Result.success(new ConfirmManifest(eventId, acknowledged, uncertain))
        : Result.error(List.copyOf(problems));
  }
}
