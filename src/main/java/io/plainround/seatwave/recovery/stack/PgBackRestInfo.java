package io.plainround.seatwave.recovery.stack;

import io.plainround.seatwave.recovery.backup.BackupHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backups of one stanza as {@code pgbackrest info --output=json} lists them, read into records.
 * Only the fields the drill uses are read; anything else in pgBackRest's document is ignored.
 * Stateless and thread-safe.
 */
public final class PgBackRestInfo {

  /**
   * One backup set.
   *
   * @param label pgBackRest's label
   * @param stoppedAt when the backup stopped
   * @param startLsn the log position a restore of it replays from
   * @param databaseBytes the size of the database it holds
   * @param repositoryBytes its size in the repository, compressed and encrypted
   */
  public record BackupSet(
      String label, Instant stoppedAt, String startLsn, long databaseBytes, long repositoryBytes) {
    /** Refuses missing fields. */
    public BackupSet {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(stoppedAt, "stoppedAt");
      Objects.requireNonNull(startLsn, "startLsn");
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  // One line per backup in "verify --output=text --verbose".
  private static final Pattern VERIFIED_BACKUP =
      Pattern.compile("(?m)^\\s*backup: ([0-9]{8}-[0-9]{6}F[^,]*), status: ([a-z ]+),");
  private static final String VALID = "valid";

  private PgBackRestInfo() {}

  /**
   * Reads the backup sets of {@code stanza}, oldest first.
   *
   * @throws IllegalArgumentException if the document has no such stanza or is not pgBackRest's
   */
  public static List<BackupSet> backups(String infoJson, String stanza) {
    JsonNode document = JSON.readTree(infoJson);
    for (JsonNode entry : document) {
      if (!stanza.equals(entry.path("name").asString())) {
        continue;
      }
      List<BackupSet> sets = new ArrayList<>();
      for (JsonNode backup : entry.path("backup")) {
        sets.add(
            new BackupSet(
                backup.path("label").asString(),
                Instant.ofEpochSecond(backup.path("timestamp").path("stop").asLong()),
                backup.path("lsn").path("start").asString(),
                backup.path("info").path("size").asLong(),
                backup.path("info").path("repository").path("size").asLong()));
      }
      return List.copyOf(sets);
    }
    throw new IllegalArgumentException("pgBackRest's info lists no stanza " + stanza);
  }

  /**
   * Reads what {@code pgbackrest verify --output=text --verbose} found for each backup. The command
   * exits 0 even when it finds a damaged file (pgBackRest 2.60.0, seen on this lab), so its exit
   * status says only that it ran; this reads the status it printed for each backup.
   *
   * @return each backup label with PASSED when verify called it valid, FAILED otherwise
   */
  public static Map<String, BackupHealth.Verification> verified(String verboseText) {
    Map<String, BackupHealth.Verification> found = new LinkedHashMap<>();
    Matcher backup = VERIFIED_BACKUP.matcher(verboseText);
    while (backup.find()) {
      found.put(
          backup.group(1),
          backup.group(2).equals(VALID)
              ? BackupHealth.Verification.PASSED
              : BackupHealth.Verification.FAILED);
    }
    return Map.copyOf(found);
  }

  /**
   * Joins the backup sets with what verification found for each label; a label with no result was
   * never verified.
   */
  public static List<BackupHealth.BackupRun> runs(
      List<BackupSet> sets, Map<String, BackupHealth.Verification> verified) {
    return sets.stream()
        .map(set ->
            new BackupHealth.BackupRun(
                set.label(),
                set.stoppedAt(),
                verified.getOrDefault(set.label(), BackupHealth.Verification.NOT_RUN)))
        .toList();
  }
}
