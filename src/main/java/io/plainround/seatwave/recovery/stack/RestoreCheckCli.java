package io.plainround.seatwave.recovery.stack;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.recovery.restore.ConfirmChecker;
import io.plainround.seatwave.recovery.restore.ConfirmManifest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The checker over JDBC: reads the booking ids of the manifest's event from a database (the restore
 * target by default) and declares the copy good only if it matches the drill manifest, the load
 * generator's independent record. Prints DECLARED GOOD and exits 0, or REFUSED with every reason
 * and exits 1. Usage: {@code RestoreCheckCli <settings> <manifest> --before <ISO-8601 UTC time> |
 * --everything [restore|primary|copy]}.
 */
public final class RestoreCheckCli {

  private static final int REFUSED_STATUS = 1;
  private static final int USAGE_STATUS = 2;
  private static final int FETCH_ROWS = 10_000;
  private static final String BOOKING_IDS =
      "SELECT booking_id::text FROM booking.bookings WHERE event_id = ?";
  private static final String NEWEST_HEARTBEAT = "SELECT max(beat_at) FROM booking.drill_heartbeat";

  private RestoreCheckCli() {}

  /** Checks the copy; see the class comment for the arguments. */
  public static void main(String[] arguments) throws SQLException {
    if (arguments.length < 3 || arguments.length > 5) {
      usage();
    }
    StackSettings settings =
        StackSettings.load(Path.of(arguments[0]))
            .orOnErrorThrow(problems -> new IllegalArgumentException("settings: " + problems));
    ConfirmManifest manifest =
        ConfirmManifest.read(Path.of(arguments[1]))
            .orOnErrorThrow(problems -> new IllegalArgumentException("manifest: " + problems));
    ConfirmChecker.Expectation expectation = expectation(arguments);
    int databaseArgument = expectation instanceof ConfirmChecker.Before ? 4 : 3;
    String database = arguments.length > databaseArgument ? arguments[databaseArgument] : "restore";
    String url =
        switch (database) {
          case "restore" -> settings.restoreUrl();
          case "primary" -> settings.primaryUrl();
          case "copy" -> settings.copyUrl();
          default ->
              throw new IllegalArgumentException(
                  "the database must be restore, primary or copy, was " + database);
        };
    Set<String> present = new HashSet<>();
    String heartbeat;
    try (Connection connection = LabDatabase.connect(settings, url, settings.app())) {
      // A cursor, not one result set in memory: a restored event can hold many rows.
      connection.setAutoCommit(false);
      try (PreparedStatement ids = connection.prepareStatement(BOOKING_IDS)) {
        ids.setFetchSize(FETCH_ROWS);
        ids.setLong(1, manifest.eventId());
        try (ResultSet rows = ids.executeQuery()) {
          while (rows.next()) {
            present.add(rows.getString(1));
          }
        }
      }
      try (PreparedStatement beat = connection.prepareStatement(NEWEST_HEARTBEAT);
          ResultSet newest = beat.executeQuery()) {
        newest.next();
        heartbeat =
            newest.getObject(1) == null ? "none" : newest.getTimestamp(1).toInstant().toString();
      }
      connection.commit();
    }
    Result<ConfirmChecker.Match, ConfirmChecker.Mismatch> verdict =
        ConfirmChecker.check(manifest, present, expectation);
    System.out.printf(
        Locale.ROOT,
        "%s holds %,d rows of event %d; the manifest lists %,d acknowledged and %,d uncertain"
            + " confirms; newest heartbeat %s%n",
        database,
        present.size(),
        manifest.eventId(),
        manifest.acknowledged().size(),
        manifest.uncertain().size(),
        heartbeat);
    ConfirmChecker.newestPresentCommit(manifest, present)
        .ifPresent(newest ->
            System.out.println("newest acknowledged confirm present committed at " + newest));
    boolean isGood =
        verdict
            .map(match -> {
              System.out.printf(
                  Locale.ROOT,
                  "checker: DECLARED GOOD, %,d acknowledged confirms present and %,d absent as"
                      + " expected, %,d uncertain ones committed%n",
                  match.present(),
                  match.absent(),
                  match.uncertainPresent());
              return true;
            })
            .recoverError(mismatch -> {
              System.out.println("checker: REFUSED, " + String.join("; ", mismatch.reasons()));
              mismatch
                  .oldestMissingCommit()
                  .ifPresent(oldest ->
                      System.out.printf(
                          Locale.ROOT,
                          "missing: %,d acknowledged confirms committed from %s; %s%n",
                          mismatch.missingCommits().size(),
                          oldest,
                          mismatch.isTailLoss()
                              ? "all after the newest one present (the copy stops early)"
                              : "some between confirms that are present (a hole)"));
              return false;
            });
    if (!isGood) {
      System.exit(REFUSED_STATUS);
    }
  }

  private static ConfirmChecker.Expectation expectation(String[] arguments) {
    return switch (arguments[2]) {
      case "--everything" -> new ConfirmChecker.Everything();
      case "--before" -> {
        if (arguments.length < 4) {
          usage();
        }
        try {
          yield new ConfirmChecker.Before(Instant.parse(arguments[3]));
        } catch (DateTimeParseException notATime) {
          throw new IllegalArgumentException(
              "--before takes an ISO-8601 UTC time such as 2026-11-12T19:14:20.225407Z, was "
                  + arguments[3],
              notATime);
        }
      }
      default -> {
        usage();
        yield new ConfirmChecker.Everything();
      }
    };
  }

  private static void usage() {
    System.err.println(
        "usage: RestoreCheckCli <settings> <manifest> --before <ISO-8601 UTC time> | --everything"
            + " [restore|primary|copy]");
    System.exit(USAGE_STATUS);
  }
}
