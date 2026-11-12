package io.plainround.seatwave.recovery.stack;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * The pilot light's mechanism on one machine: a second PostgreSQL (the {@code region-b-copy}
 * service) subscribes to the primary's bookings tables by logical replication, the lag is measured,
 * and the copy is promoted with its sequences advanced. Both servers share one disk and one virtual
 * network, so the lag is a same-machine figure, never a cross-region one. Usage: {@code
 * PilotLightCli <settings> subscribe | lag <samples> | promote | confirm}.
 */
public final class PilotLightCli {

  static final String PUBLICATION = "seatwave_bookings";
  private static final String TABLES =
      "booking.customers, booking.bookings, booking.outbox, booking.drill_heartbeat";
  // The lab's passwords are hex from scripts/stack.sh; anything else is refused before it is put
  // into the subscription's connection string, which takes no parameters.
  private static final Pattern HEX_PASSWORD = Pattern.compile("[0-9a-f]{16,128}");
  private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration POLL = Duration.ofMillis(1);
  private static final Duration SYNC_POLL = Duration.ofMillis(200);
  private static final Duration SAMPLE_GAP = Duration.ofMillis(200);
  private static final Duration LAG_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_SAMPLES = 10_000;
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double SECONDS_PER_NANO = 1e-9;
  private static final double BYTES_PER_MEGABYTE = 1e6;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  private static final int USAGE_STATUS = 2;
  private static final BigDecimal PRICE_PAID = new BigDecimal("95.00");
  // The event the post-promotion confirm books a seat of; the drill's own events use clock values.
  private static final long PROMOTION_CHECK_EVENT = 40;

  private PilotLightCli() {}

  /** Runs one subcommand; see the class comment. */
  public static void main(String[] arguments) throws SQLException {
    if (arguments.length < 2) {
      usage();
    }
    StackSettings settings =
        StackSettings.load(Path.of(arguments[0]))
            .orOnErrorThrow(problems -> new IllegalArgumentException("settings: " + problems));
    switch (arguments[1]) {
      case "subscribe" -> subscribe(settings).forEach(System.out::println);
      case "lag" -> System.out.println(lag(settings, samples(arguments)));
      case "promote" -> {
        long started = System.nanoTime();
        try (Connection copy =
            LabDatabase.connect(settings, settings.copyUrl(), settings.admin())) {
          new PilotLightPromotion(settings.sequenceMargin())
              .promote(copy)
              .forEach(System.out::println);
        }
        System.out.printf(
            Locale.ROOT, "promoted in %.3f s%n", (System.nanoTime() - started) * SECONDS_PER_NANO);
      }
      case "confirm" ->
          System.out.println("confirm on the copy: booking " + confirmOnCopy(settings));
      default -> usage();
    }
  }

  /**
   * Publishes the bookings tables on the primary, subscribes the copy, and waits until the initial
   * copy is done.
   *
   * @return what was done, with the initial copy's size and rate
   */
  static List<String> subscribe(StackSettings settings) throws SQLException {
    List<String> done = new ArrayList<>();
    try (Connection owner = LabDatabase.connect(settings, settings.primaryUrl(), settings.owner());
        Statement statement = owner.createStatement();
        ResultSet existing =
            statement.executeQuery(
                "SELECT count(*) FROM pg_publication WHERE pubname = '" + PUBLICATION + "'")) {
      existing.next();
      if (existing.getLong(1) == 0) {
        statement.execute("CREATE PUBLICATION " + PUBLICATION + " FOR TABLE " + TABLES);
      }
      done.add("primary: publication " + PUBLICATION + " of " + TABLES);
    }
    String password = settings.replicator().password();
    if (!HEX_PASSWORD.matcher(password).matches()) {
      throw new IllegalArgumentException(
          "the replicator's password must be the lab's hex password");
    }
    long started = System.nanoTime();
    try (Connection copy = LabDatabase.connect(settings, settings.copyUrl(), settings.admin());
        Statement statement = copy.createStatement()) {
      statement.execute(
          "CREATE SUBSCRIPTION "
              + PilotLightPromotion.SUBSCRIPTION
              + " CONNECTION 'host="
              + settings.replicationHost()
              + " port=5432 dbname=seatwave user="
              + settings.replicator().user()
              + " password="
              + password
              + "' PUBLICATION "
              + PUBLICATION);
      waitForInitialCopy(statement);
      double seconds = (System.nanoTime() - started) * SECONDS_PER_NANO;
      try (ResultSet size =
          statement.executeQuery(
              "SELECT sum(pg_table_size(relid)) FROM pg_stat_user_tables WHERE schemaname = 'booking'")) {
        size.next();
        long bytes = size.getLong(1);
        done.add(
            String.format(
                Locale.ROOT,
                "copy: subscription %s ready; initial copy of %,.1f MB of table data in %.1f s,"
                    + " %.1f MB a second (same machine)",
                PilotLightPromotion.SUBSCRIPTION,
                bytes / BYTES_PER_MEGABYTE,
                seconds,
                bytes / BYTES_PER_MEGABYTE / seconds));
      }
    }
    return done;
  }

  private static void waitForInitialCopy(Statement copy) throws SQLException {
    long deadline = System.nanoTime() + SYNC_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      try (ResultSet waiting =
          copy.executeQuery("SELECT count(*) FROM pg_subscription_rel WHERE srsubstate <> 'r'")) {
        waiting.next();
        if (waiting.getLong(1) == 0) {
          return;
        }
      }
      LockSupport.parkNanos(SYNC_POLL.toNanos());
    }
    throw new IllegalStateException("the initial copy was not done after " + SYNC_TIMEOUT);
  }

  /**
   * Writes {@code samples} heartbeat rows on the primary, one every 200 ms, and times how long each
   * takes to appear on the copy.
   *
   * @return the lag's median, 99th percentile and maximum
   */
  static String lag(StackSettings settings, int samples) throws SQLException {
    List<Long> lagsNanos = new ArrayList<>();
    try (Connection primary = LabDatabase.connect(settings, settings.primaryUrl(), settings.app());
        Connection copy = LabDatabase.connect(settings, settings.copyUrl(), settings.app());
        PreparedStatement beat =
            primary.prepareStatement(
                "INSERT INTO booking.drill_heartbeat (beat_at) VALUES (clock_timestamp())"
                    + " RETURNING beat_at");
        PreparedStatement seen =
            copy.prepareStatement("SELECT 1 FROM booking.drill_heartbeat WHERE beat_at = ?")) {
      for (int sample = 0; sample < samples; sample++) {
        Timestamp beatAt;
        try (ResultSet written = beat.executeQuery()) {
          written.next();
          beatAt = written.getTimestamp(1);
        }
        long committedNanos = System.nanoTime();
        seen.setTimestamp(1, beatAt);
        lagsNanos.add(waitUntilSeen(seen, committedNanos) - committedNanos);
        LockSupport.parkNanos(SAMPLE_GAP.toNanos());
      }
    }
    List<Long> sorted = lagsNanos.stream().sorted().toList();
    return String.format(
        Locale.ROOT,
        "replication lag over %d samples (commit on the primary to visible on the copy, same"
            + " machine): p50 %.1f ms, p99 %.1f ms, max %.1f ms",
        samples,
        sorted.get((int) (PERCENTILE_50 * (samples - 1))) / NANOS_PER_MILLI,
        sorted.get((int) (PERCENTILE_99 * (samples - 1))) / NANOS_PER_MILLI,
        sorted.getLast() / NANOS_PER_MILLI);
  }

  private static long waitUntilSeen(PreparedStatement seen, long since) throws SQLException {
    while (System.nanoTime() - since < LAG_TIMEOUT.toNanos()) {
      try (ResultSet found = seen.executeQuery()) {
        if (found.next()) {
          return System.nanoTime();
        }
      }
      LockSupport.parkNanos(POLL.toNanos());
    }
    throw new IllegalStateException("a heartbeat did not reach the copy within " + LAG_TIMEOUT);
  }

  /**
   * Writes one confirm on the copy as the application role, as the plan's {@code smoke} step does
   * after promotion: a customer, a booking and its outbox row, with the ids the sequences give.
   *
   * @return the booking id written
   * @throws SQLException if the write fails, for example on a duplicate key from a sequence that
   *     was not advanced
   */
  static UUID confirmOnCopy(StackSettings settings) throws SQLException {
    UUID bookingId = UUID.randomUUID();
    try (Connection copy = LabDatabase.connect(settings, settings.copyUrl(), settings.app())) {
      copy.setAutoCommit(false);
      long customerId;
      try (PreparedStatement customer =
          copy.prepareStatement(
              "INSERT INTO booking.customers (customer_ref, created_at) VALUES (?, now())"
                  + " RETURNING customer_id")) {
        customer.setString(1, "promotion-check-" + bookingId);
        try (ResultSet created = customer.executeQuery()) {
          created.next();
          customerId = created.getLong(1);
        }
      }
      try (PreparedStatement booking =
          copy.prepareStatement(
              "INSERT INTO booking.bookings (booking_id, event_id, seat_label, customer_id,"
                  + " price_paid, currency, confirmed_at) VALUES (?, ?, ?, ?, ?, 'EUR', now())")) {
        booking.setObject(1, bookingId);
        booking.setLong(2, PROMOTION_CHECK_EVENT);
        booking.setString(3, "P-" + bookingId.toString().substring(0, 8));
        booking.setLong(4, customerId);
        booking.setBigDecimal(5, PRICE_PAID);
        booking.executeUpdate();
      }
      try (PreparedStatement outbox =
          copy.prepareStatement(
              "INSERT INTO booking.outbox (booking_id, payload, created_at)"
                  + " VALUES (?, '{\"type\":\"BookingConfirmed\"}', now())")) {
        outbox.setObject(1, bookingId);
        outbox.executeUpdate();
      }
      copy.commit();
    }
    return bookingId;
  }

  private static int samples(String[] arguments) {
    if (arguments.length != 3) {
      usage();
    }
    int samples = Integer.parseInt(arguments[2]);
    if (samples < 1 || samples > MAX_SAMPLES) {
      throw new IllegalArgumentException("samples must be 1 to " + MAX_SAMPLES);
    }
    return samples;
  }

  private static void usage() {
    System.err.println(
        "usage: PilotLightCli <settings> subscribe | lag <samples> | promote | confirm");
    System.exit(USAGE_STATUS);
  }
}
