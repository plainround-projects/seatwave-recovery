package io.plainround.seatwave.recovery.stack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stack tests' access to the lab of compose.yaml: its settings, Compose and pgBackRest
 * commands, signed requests to the store, and SQL as the superuser. Every command has a timeout and
 * is scoped to the Compose project seatwave-recovery. Not thread-safe; one test at a time.
 */
final class LabStack {

  static final String PROJECT = "seatwave-recovery";
  private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration SLOT_POLL = Duration.ofMillis(200);
  private static final int SLOT_ATTEMPTS = 50;
  private static final String STORE = "https://127.0.0.1:24000/seatwave-backups";
  private static final Pattern KEY = Pattern.compile("<Key>([^<]+)</Key>");
  private static final Pattern MARKER =
      Pattern.compile("<DeleteMarker>.*?<Key>([^<]+)</Key>.*?<VersionId>([^<]+)</VersionId>");

  /** A finished command: its exit status and everything it printed. */
  record Ran(int status, String output) {}

  private LabStack() {}

  static StackSettings settings() {
    String path = System.getProperty("seatwave.stackSettings", "config/stack.properties");
    return StackSettings.load(Path.of(path))
        .orOnErrorThrow(problems -> new IllegalStateException("stack settings: " + problems));
  }

  /** Runs a command from the project folder and waits for it. */
  static Ran run(List<String> command) {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    try {
      Process process = builder.start();
      String output;
      try (InputStream printed = process.getInputStream()) {
        output = new String(printed.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(command + " did not finish within " + COMMAND_TIMEOUT);
      }
      return new Ran(process.exitValue(), output);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot run " + command, failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running " + command, interrupted);
    }
  }

  /** Runs {@code docker compose -p seatwave-recovery} with every profile and the arguments. */
  static Ran compose(String... arguments) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "docker",
                "compose",
                "-p",
                PROJECT,
                "--profile",
                "drill",
                "--profile",
                "pilot-light"));
    command.addAll(List.of(arguments));
    return run(command);
  }

  /** Runs pgBackRest in the primary as the postgres user; fails the test on a non-zero status. */
  static String pgbackrest(String... arguments) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "exec", "-T", "--user", "postgres", "primary", "pgbackrest", "--stanza=seatwave"));
    command.addAll(List.of(arguments));
    Ran ran = compose(command.toArray(String[]::new));
    if (ran.status() != 0) {
      throw new IllegalStateException(
          "pgbackrest " + List.of(arguments) + " failed:\n" + ran.output());
    }
    return ran.output();
  }

  /** One signed S3 request to the store; returns the HTTP status and the body. */
  static Ran s3(String method, String pathAndQuery, String... extra) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "curl",
                "-s",
                "-w",
                "\n%{http_code}",
                "-K",
                ".secrets/curl.cfg",
                "--aws-sigv4",
                "aws:amz:us-east-1:s3",
                "--cacert",
                ".secrets/tls/ca.pem",
                "-X",
                method));
    command.addAll(List.of(extra));
    command.add(STORE + pathAndQuery);
    Ran ran = run(command);
    String body = ran.output();
    int split = body.lastIndexOf('\n');
    return new Ran(Integer.parseInt(body.substring(split + 1).strip()), body.substring(0, split));
  }

  /** Returns the archived log objects of the stanza whose names contain {@code segment}. */
  static List<String> archivedObjects(String segment) {
    Ran listing = s3("GET", "?list-type=2&prefix=seatwave/archive/seatwave/");
    List<String> keys = new ArrayList<>();
    Matcher found = KEY.matcher(listing.output());
    while (found.find()) {
      if (found.group(1).contains(segment)) {
        keys.add(found.group(1));
      }
    }
    return keys;
  }

  /** Removes every delete marker the tests left, so the hidden versions are visible again. */
  static void removeDeleteMarkers() {
    Ran versions = s3("GET", "?versions&prefix=seatwave/archive/");
    Matcher marker = MARKER.matcher(versions.output());
    while (marker.find()) {
      s3("DELETE", "/" + marker.group(1) + "?versionId=" + marker.group(2));
    }
  }

  /** Ends the walsender of a replication slot on the primary, if any, and drops the slot. */
  static void dropSlot(String slot) throws SQLException {
    StackSettings settings = settings();
    String where = " FROM pg_replication_slots WHERE slot_name = '" + slot + "'";
    for (int attempt = 0; attempt < SLOT_ATTEMPTS; attempt++) {
      sqlOne(
          settings,
          settings.primaryUrl(),
          "SELECT count(pg_terminate_backend(active_pid))" + where + " AND active");
      sqlOne(
          settings,
          settings.primaryUrl(),
          "SELECT count(pg_drop_replication_slot(slot_name))" + where + " AND NOT active");
      if ("0".equals(sqlOne(settings, settings.primaryUrl(), "SELECT count(*)" + where))) {
        return;
      }
      LockSupport.parkNanos(SLOT_POLL.toNanos());
    }
    throw new IllegalStateException("replication slot " + slot + " could not be dropped");
  }

  /** Runs one SQL statement as the superuser on {@code url} and returns its first column. */
  static String sqlOne(StackSettings settings, String url, String sql) throws SQLException {
    try (Connection admin = LabDatabase.connect(settings, url, settings.admin());
        Statement statement = admin.createStatement();
        ResultSet row = statement.executeQuery(sql)) {
      row.next();
      return row.getString(1);
    }
  }
}
