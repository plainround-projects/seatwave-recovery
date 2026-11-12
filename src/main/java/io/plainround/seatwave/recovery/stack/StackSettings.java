package io.plainround.seatwave.recovery.stack;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * The lab stack's addresses, roles and limits, read from config/stack.properties. Every key is
 * required and every problem is reported together, so a reader fixing the file sees them all at
 * once. Immutable and thread-safe; {@link #toString()} never prints a password.
 *
 * @param primaryUrl JDBC URL of the primary (loopback only)
 * @param restoreUrl JDBC URL of the restore target
 * @param copyUrl JDBC URL of the region-b copy
 * @param app the role of the confirms and the checker's reads
 * @param admin the superuser, for the subscription and the archiver's functions
 * @param owner the role that owns the bookings tables, for the publication
 * @param replicator the role the subscription connects as
 * @param replicationHost the primary's name inside the Compose network, which the copy connects to
 * @param connectTimeoutMillis how long a connection attempt may take
 * @param poolSize the load generator's connections
 * @param poolMaxLifetimeMillis how long the pool keeps a connection before replacing it
 * @param maxInFlight confirms in flight at most; one that finds no room is not sent
 * @param sequenceMargin how far past the copy's highest id a promotion moves each sequence
 */
public record StackSettings(
    String primaryUrl,
    String restoreUrl,
    String copyUrl,
    Login app,
    Login admin,
    Login owner,
    Login replicator,
    String replicationHost,
    int connectTimeoutMillis,
    int poolSize,
    long poolMaxLifetimeMillis,
    int maxInFlight,
    long sequenceMargin) {

  /**
   * A role and its password, read from a file in the ignored .secrets folder.
   *
   * @param user the role
   * @param password its password
   */
  public record Login(String user, String password) {
    /** Refuses missing values. */
    public Login {
      Objects.requireNonNull(user, "user");
      Objects.requireNonNull(password, "password");
    }

    /** Never prints the password. */
    @Override
    public String toString() {
      return "Login[user=" + user + "]";
    }
  }

  // Ranges a lab on one laptop can serve; outside them a run would measure the laptop's limits.
  private static final int MIN_CONNECT_MILLIS = 250;
  private static final int MAX_CONNECT_MILLIS = 60_000;
  private static final int MAX_POOL = 64;
  private static final long MIN_LIFETIME_MILLIS = 30_000;
  private static final long MAX_LIFETIME_MILLIS = 86_400_000;
  private static final int MAX_IN_FLIGHT = 10_000;
  private static final long MAX_SEQUENCE_MARGIN = 1_000_000_000L;
  private static final String LOOPBACK_URL = "jdbc:postgresql://127.0.0.1:";

  /** Rejects missing values; ranges are checked by {@link #load(Path)}. */
  public StackSettings {
    Objects.requireNonNull(primaryUrl, "primaryUrl");
    Objects.requireNonNull(restoreUrl, "restoreUrl");
    Objects.requireNonNull(copyUrl, "copyUrl");
    Objects.requireNonNull(app, "app");
    Objects.requireNonNull(admin, "admin");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(replicator, "replicator");
    Objects.requireNonNull(replicationHost, "replicationHost");
  }

  /**
   * Reads and checks the settings file. The password files it names are read relative to the folder
   * the program runs in (the project folder).
   *
   * @return the settings, or every problem found, each naming its key
   */
  public static Result<StackSettings, List<String>> load(Path settingsPath) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException failure) {
      return Result.error(List.of("cannot read " + settingsPath + ": " + failure.getMessage()));
    }
    Reading reading = new Reading(properties);
    String primaryUrl = reading.loopbackUrl("db.primaryUrl");
    String restoreUrl = reading.loopbackUrl("db.restoreUrl");
    String copyUrl = reading.loopbackUrl("db.copyUrl");
    Login app = reading.login("db.appUser", "db.appPasswordFile");
    Login admin = reading.login("db.adminUser", "db.adminPasswordFile");
    Login owner = reading.login("db.ownerUser", "db.ownerPasswordFile");
    Login replicator = reading.login("db.replicatorUser", "db.replicatorPasswordFile");
    String replicationHost = reading.text("db.replicationHost");
    int connectTimeoutMillis =
        (int) reading.whole("db.connectTimeoutMillis", MIN_CONNECT_MILLIS, MAX_CONNECT_MILLIS);
    int poolSize = (int) reading.whole("pool.size", 1, MAX_POOL);
    long poolMaxLifetimeMillis =
        reading.whole("pool.maxLifetimeMillis", MIN_LIFETIME_MILLIS, MAX_LIFETIME_MILLIS);
    int maxInFlight = (int) reading.whole("load.maxInFlight", 1, MAX_IN_FLIGHT);
    long sequenceMargin = reading.whole("promotion.sequenceMargin", 1, MAX_SEQUENCE_MARGIN);
    if (!reading.problems.isEmpty()) {
      return Result.error(List.copyOf(reading.problems));
    }
    return Result.success(
        new StackSettings(
            primaryUrl,
            restoreUrl,
            copyUrl,
            app,
            admin,
            owner,
            replicator,
            replicationHost,
            connectTimeoutMillis,
            poolSize,
            poolMaxLifetimeMillis,
            maxInFlight,
            sequenceMargin));
  }

  /** Reads keys and collects every problem instead of stopping at the first. */
  private static final class Reading {
    private final Properties properties;
    private final List<String> problems = new ArrayList<>();

    Reading(Properties properties) {
      this.properties = properties;
    }

    String text(String key) {
      String value = properties.getProperty(key, "").strip();
      if (value.isEmpty()) {
        problems.add(key + " is missing");
      }
      return value;
    }

    String loopbackUrl(String key) {
      String url = text(key);
      if (!url.isEmpty() && !url.startsWith(LOOPBACK_URL)) {
        problems.add(key + " must name the loopback address 127.0.0.1, found " + url);
      }
      return url;
    }

    Login login(String userKey, String passwordFileKey) {
      return new Login(text(userKey), secret(passwordFileKey));
    }

    String secret(String key) {
      String file = text(key);
      if (file.isEmpty()) {
        return "";
      }
      try {
        String value = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
          problems.add(key + " names an empty file: " + file);
        }
        return value;
      } catch (IOException failure) {
        problems.add(key + " names a file that cannot be read (run scripts/stack.sh up): " + file);
        return "";
      }
    }

    long whole(String key, long minimum, long maximum) {
      String value = text(key);
      if (value.isEmpty()) {
        return minimum;
      }
      try {
        long parsed = Long.parseLong(value);
        if (parsed < minimum || parsed > maximum) {
          problems.add(
              String.format(
                  Locale.ROOT, "%s must be %d to %d, found %d", key, minimum, maximum, parsed));
        }
        return parsed;
      } catch (NumberFormatException notANumber) {
        problems.add(key + " must be a whole number, found " + value);
        return minimum;
      }
    }
  }
}
