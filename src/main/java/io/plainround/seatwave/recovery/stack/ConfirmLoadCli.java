package io.plainround.seatwave.recovery.stack;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.recovery.restore.ConfirmManifest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The drill's load generator. It sends exactly the planned number of confirms to the primary at a
 * fixed rate, open loop (a confirm is due at its time whether or not earlier ones have answered),
 * each in one transaction that writes a customer, a booking and its outbox row, as SeatWave's
 * confirm does. For every acknowledged confirm it reads the commit timestamp PostgreSQL recorded; a
 * confirm whose answer never came is uncertain. It writes a heartbeat row every second, and at the
 * end the drill manifest and a summary of what it ran, including when confirms failed and when they
 * succeeded again. Usage: {@code ConfirmLoadCli <settings> <confirms> <per second> <manifest
 * file>}.
 */
public final class ConfirmLoadCli {

  private static final int MAX_CONFIRMS = 2_000_000;
  private static final int MAX_RATE = 2_000;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double SECONDS_PER_NANO = 1e-9;
  private static final Duration HEARTBEAT_EVERY = Duration.ofSeconds(1);
  private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(2);
  private static final BigDecimal PRICE_PAID = new BigDecimal("95.00");
  private static final String CURRENCY = "EUR";
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  private static final int ARGUMENTS = 4;
  private static final int USAGE_STATUS = 2;
  private static final int ERRORS_KEPT = 3;

  private static final String INSERT_CUSTOMER =
      """
      INSERT INTO booking.customers (customer_ref, created_at) VALUES (?, now())
      RETURNING customer_id\
      """;
  private static final String INSERT_BOOKING =
      """
      INSERT INTO booking.bookings
        (booking_id, event_id, seat_label, customer_id, price_paid, currency, confirmed_at)
      VALUES (?, ?, ?, ?, ?, ?, now())\
      """;
  private static final String INSERT_OUTBOX =
      """
      INSERT INTO booking.outbox (booking_id, payload, created_at) VALUES (?, ?, now())\
      """;
  // The commit time of the transaction that wrote the row: the clock recovery targets use.
  private static final String COMMIT_TIME =
      "SELECT pg_xact_commit_timestamp(xmin) FROM booking.bookings WHERE booking_id = ?";
  private static final String INSERT_HEARTBEAT =
      "INSERT INTO booking.drill_heartbeat (beat_at) VALUES (clock_timestamp())";

  /** One answer, by when it came after the start. */
  private record Answer(long atNanos, boolean isAcknowledged, long latencyNanos) {}

  private ConfirmLoadCli() {}

  /** Runs the load; exits with status 2 on bad arguments or settings. */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != ARGUMENTS) {
      System.err.println(
          "usage: ConfirmLoadCli <settings> <confirms> <per second> <manifest file>");
      System.exit(USAGE_STATUS);
    }
    StackSettings settings =
        StackSettings.load(Path.of(arguments[0]))
            .orOnErrorThrow(problems -> new IllegalArgumentException("settings: " + problems));
    int confirms = boundedWhole(arguments[1], "confirms", MAX_CONFIRMS);
    int perSecond = boundedWhole(arguments[2], "per second", MAX_RATE);
    new ConfirmLoadCli.Run(settings, confirms, perSecond, Clock.systemUTC())
        .execute(Path.of(arguments[3]));
  }

  private static int boundedWhole(String text, String name, int maximum) {
    try {
      int value = Integer.parseInt(text);
      if (value < 1 || value > maximum) {
        throw new IllegalArgumentException(name + " must be 1 to " + maximum + ", was " + value);
      }
      return value;
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException(name + " must be a whole number, was " + text, notANumber);
    }
  }

  /** One load run; confined to the thread that calls {@link #execute}, its workers share maps. */
  private static final class Run {
    private final StackSettings settings;
    private final int confirms;
    private final int perSecond;
    private final long eventId;
    private final Map<String, Instant> acknowledged = new ConcurrentHashMap<>();
    private final Set<String> uncertain = ConcurrentHashMap.newKeySet();
    private final AtomicInteger notSent = new AtomicInteger();
    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> firstErrors = new ConcurrentLinkedQueue<>();
    private long startNanos;

    Run(StackSettings settings, int confirms, int perSecond, Clock clock) {
      this.settings = settings;
      this.confirms = confirms;
      this.perSecond = perSecond;
      // A new event per run, so the checker reads this run's rows and no other.
      this.eventId = clock.millis();
    }

    void execute(Path manifestFile) throws InterruptedException {
      Semaphore room = new Semaphore(settings.maxInFlight());
      AtomicBoolean isRunning = new AtomicBoolean(true);
      try (HikariDataSource pool = LabDatabase.confirmPool(settings);
          ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
        startNanos = System.nanoTime();
        workers.submit(() -> beat(pool, isRunning));
        long gapNanos = NANOS_PER_SECOND / perSecond;
        for (int index = 0; index < confirms; index++) {
          // Sleep to the confirm's due time, never spin: the machine is shared.
          long dueNanos = startNanos + index * gapNanos;
          for (long wait = dueNanos - System.nanoTime();
              wait > 0;
              wait = dueNanos - System.nanoTime()) {
            LockSupport.parkNanos(wait);
          }
          if (!room.tryAcquire()) {
            notSent.incrementAndGet();
            answers.add(new Answer(System.nanoTime() - startNanos, false, 0));
            continue;
          }
          int seatNumber = index + 1;
          workers.submit(() -> {
            try {
              confirm(pool, seatNumber);
            } finally {
              room.release();
            }
          });
        }
        isRunning.set(false);
        workers.shutdown();
        if (!workers.awaitTermination(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
          throw new IllegalStateException("confirms still in flight after " + DRAIN_TIMEOUT);
        }
      }
      new ConfirmManifest(eventId, acknowledged, uncertain).write(manifestFile);
      summarize(manifestFile);
    }

    private void confirm(HikariDataSource pool, int seatNumber) {
      UUID bookingId = UUID.randomUUID();
      long sentNanos = System.nanoTime();
      Connection connection;
      try {
        connection = pool.getConnection();
      } catch (SQLException noConnection) {
        // Never sent: the pool had no working connection within its wait.
        notSent.incrementAndGet();
        remember(noConnection);
        answers.add(new Answer(System.nanoTime() - startNanos, false, 0));
        return;
      }
      try (connection) {
        Instant committedAt = write(connection, bookingId, seatNumber);
        acknowledged.put(bookingId.toString(), committedAt);
        long now = System.nanoTime();
        answers.add(new Answer(now - startNanos, true, now - sentNanos));
      } catch (SQLException lostAnswer) {
        // Sent, but the answer never came: the commit may or may not have happened.
        uncertain.add(bookingId.toString());
        remember(lostAnswer);
        answers.add(new Answer(System.nanoTime() - startNanos, false, 0));
      }
    }

    private Instant write(Connection connection, UUID bookingId, int seatNumber)
        throws SQLException {
      long customerId;
      try (PreparedStatement customer = connection.prepareStatement(INSERT_CUSTOMER)) {
        customer.setString(1, "drill-" + bookingId);
        try (ResultSet created = customer.executeQuery()) {
          created.next();
          customerId = created.getLong(1);
        }
      }
      try (PreparedStatement booking = connection.prepareStatement(INSERT_BOOKING)) {
        booking.setObject(1, bookingId);
        booking.setLong(2, eventId);
        booking.setString(3, "S-" + seatNumber);
        booking.setLong(4, customerId);
        booking.setBigDecimal(5, PRICE_PAID);
        booking.setString(6, CURRENCY);
        booking.executeUpdate();
      }
      try (PreparedStatement outbox = connection.prepareStatement(INSERT_OUTBOX)) {
        outbox.setObject(1, bookingId);
        outbox.setString(2, "{\"type\":\"BookingConfirmed\",\"seat\":\"S-" + seatNumber + "\"}");
        outbox.executeUpdate();
      }
      connection.commit();
      try (PreparedStatement commitTime = connection.prepareStatement(COMMIT_TIME)) {
        commitTime.setObject(1, bookingId);
        try (ResultSet time = commitTime.executeQuery()) {
          time.next();
          Timestamp committedAt = time.getTimestamp(1);
          connection.commit();
          return committedAt.toInstant();
        }
      }
    }

    private void beat(HikariDataSource pool, AtomicBoolean isRunning) {
      while (isRunning.get()) {
        try (Connection connection = pool.getConnection();
            PreparedStatement heartbeat = connection.prepareStatement(INSERT_HEARTBEAT)) {
          heartbeat.executeUpdate();
          connection.commit();
        } catch (SQLException missedBeat) {
          // A missed heartbeat is what an outage looks like; the confirms count the outage.
          remember(missedBeat);
        }
        LockSupport.parkNanos(HEARTBEAT_EVERY.toNanos());
      }
    }

    private void remember(SQLException failure) {
      String kind =
          failure instanceof SQLTransientConnectionException
              ? "no connection from the pool"
              : failure.getSQLState() + " " + failure.getMessage();
      if (firstErrors.size() < ERRORS_KEPT && !firstErrors.contains(kind)) {
        firstErrors.add(kind);
      }
    }

    private void summarize(Path manifestFile) {
      List<Answer> inOrder = new ArrayList<>(answers);
      inOrder.sort((first, second) -> Long.compare(first.atNanos(), second.atNanos()));
      System.out.printf(
          Locale.ROOT,
          "planned %,d confirms at %d a second for event %d: sent %,d; acknowledged %,d,"
              + " uncertain %,d, not sent %,d%n",
          confirms,
          perSecond,
          eventId,
          confirms - notSent.get(),
          acknowledged.size(),
          uncertain.size(),
          notSent.get());
      List<Long> latencies =
          inOrder.stream()
              .filter(Answer::isAcknowledged)
              .map(Answer::latencyNanos)
              .sorted()
              .toList();
      if (!latencies.isEmpty()) {
        System.out.printf(
            Locale.ROOT,
            "acknowledged confirm latency: p50 %.1f ms, p99 %.1f ms, max %.1f ms%n",
            latencies.get((int) (PERCENTILE_50 * (latencies.size() - 1))) / NANOS_PER_MILLI,
            latencies.get((int) (PERCENTILE_99 * (latencies.size() - 1))) / NANOS_PER_MILLI,
            latencies.getLast() / NANOS_PER_MILLI);
      }
      List<Answer> failures = inOrder.stream().filter(answer -> !answer.isAcknowledged()).toList();
      if (failures.isEmpty()) {
        System.out.println("failures: none (every confirm sent was acknowledged)");
      } else {
        long lastFailure = failures.getLast().atNanos();
        String recovered =
            inOrder.stream()
                .filter(answer -> answer.isAcknowledged() && answer.atNanos() > lastFailure)
                .findFirst()
                .map(answer ->
                    String.format(
                        Locale.ROOT,
                        "the first confirm acknowledged after them answered at +%.3f s, %.3f s"
                            + " after the first failure",
                        answer.atNanos() * SECONDS_PER_NANO,
                        (answer.atNanos() - failures.getFirst().atNanos()) * SECONDS_PER_NANO))
                .orElse("no confirm was acknowledged after them");
        System.out.printf(
            Locale.ROOT,
            "failures: %,d, from +%.3f s to +%.3f s after the start; %s%n",
            failures.size(),
            failures.getFirst().atNanos() * SECONDS_PER_NANO,
            lastFailure * SECONDS_PER_NANO,
            recovered);
        System.out.println("first errors seen: " + String.join(" | ", firstErrors));
      }
      System.out.println("manifest written: " + manifestFile.getFileName());
    }
  }
}
