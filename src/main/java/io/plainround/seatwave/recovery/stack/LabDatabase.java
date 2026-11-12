package io.plainround.seatwave.recovery.stack;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * Opens connections to the lab's databases with the timeouts of the settings. Stateless and
 * thread-safe; every connection and pool it returns belongs to the caller, who closes it.
 */
final class LabDatabase {

  // The pool waits this long for a free connection before a confirm is counted as not sent.
  private static final Duration POOL_WAIT = Duration.ofSeconds(2);
  // A borrowed connection is checked with this timeout, so a connection to a dead writer is
  // replaced instead of failing the confirm that borrowed it (the pool fix of Lab 3).
  private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(1);
  private static final int SOCKET_TIMEOUT_SECONDS = 10;
  private static final int MILLIS_PER_SECOND = 1_000;
  // Hikari's value for "start even if the database is down and keep trying".
  private static final long KEEP_TRYING = -1;

  private LabDatabase() {}

  /** Opens one connection to {@code url} as {@code login}. */
  static Connection connect(StackSettings settings, String url, StackSettings.Login login)
      throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", login.user());
    properties.setProperty("password", login.password());
    properties.setProperty(
        "connectTimeout", Integer.toString(settings.connectTimeoutMillis() / MILLIS_PER_SECOND));
    properties.setProperty("socketTimeout", Integer.toString(SOCKET_TIMEOUT_SECONDS));
    return DriverManager.getConnection(url, properties);
  }

  /** Opens the load generator's bounded pool to the primary as the application role. */
  static HikariDataSource confirmPool(StackSettings settings) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.primaryUrl());
    config.setUsername(settings.app().user());
    config.setPassword(settings.app().password());
    config.setMaximumPoolSize(settings.poolSize());
    config.setMinimumIdle(settings.poolSize());
    config.setConnectionTimeout(POOL_WAIT.toMillis());
    config.setValidationTimeout(VALIDATION_TIMEOUT.toMillis());
    config.setMaxLifetime(settings.poolMaxLifetimeMillis());
    config.setAutoCommit(false);
    // Start even if the primary is down: the pool keeps trying, which is what a service does.
    config.setInitializationFailTimeout(KEEP_TRYING);
    config.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
    config.addDataSourceProperty(
        "connectTimeout", settings.connectTimeoutMillis() / MILLIS_PER_SECOND);
    config.setPoolName("confirms");
    return new HikariDataSource(config);
  }
}
