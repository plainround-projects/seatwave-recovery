package io.plainround.seatwave.recovery.stack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes the region-b copy writable, the plan's step {@code promote}: stop logical replication,
 * detach the subscription from the lost primary's replication slot, and advance every sequence of
 * the bookings schema past the copy's highest id. Logical replication carries rows, not sequence
 * values, so without the last step the copy's sequences still start at 1 and the first confirm
 * after promotion fails with a duplicate key. Runs as the copy's superuser. Not thread-safe: one
 * promotion at a time.
 */
final class PilotLightPromotion {

  /** The subscription the copy holds, as {@link PilotLightCli} creates it. */
  static final String SUBSCRIPTION = "seatwave_bookings";

  // Every identity column of the schema, its table and its sequence, with names quoted by the
  // database itself, so the statements below are built only from catalog identifiers.
  private static final String IDENTITY_COLUMNS =
      """
      SELECT quote_ident(table_schema) || '.' || quote_ident(table_name),
             quote_ident(column_name),
             pg_get_serial_sequence(quote_ident(table_schema) || '.' || quote_ident(table_name),
                                    column_name)
      FROM information_schema.columns
      WHERE table_schema = 'booking' AND is_identity = 'YES'
      ORDER BY table_name\
      """;
  private static final String ADVANCE = "SELECT setval(?, ?, false)";

  private final long sequenceMargin;

  /**
   * @param sequenceMargin how far past the copy's highest id each sequence moves, to cover ids the
   *     lost primary handed out but never shipped
   */
  PilotLightPromotion(long sequenceMargin) {
    if (sequenceMargin < 1) {
      throw new IllegalArgumentException("the sequence margin must be at least 1");
    }
    this.sequenceMargin = sequenceMargin;
  }

  /**
   * Promotes the copy reached through {@code copyAdmin}, an autocommit superuser connection.
   *
   * @return one line per thing done, in order
   */
  List<String> promote(Connection copyAdmin) throws SQLException {
    List<String> done = new ArrayList<>();
    try (Statement statement = copyAdmin.createStatement()) {
      statement.execute("ALTER SUBSCRIPTION " + SUBSCRIPTION + " DISABLE");
      done.add("subscription " + SUBSCRIPTION + " disabled");
      // The slot lives on the lost primary; detach it so the subscription can be dropped without
      // reaching a server that is gone.
      statement.execute("ALTER SUBSCRIPTION " + SUBSCRIPTION + " SET (slot_name = NONE)");
      statement.execute("DROP SUBSCRIPTION " + SUBSCRIPTION);
      done.add("subscription " + SUBSCRIPTION + " dropped (slot left on the source)");
    }
    done.addAll(advanceSequences(copyAdmin));
    return List.copyOf(done);
  }

  private List<String> advanceSequences(Connection copyAdmin) throws SQLException {
    List<String> done = new ArrayList<>();
    try (Statement columns = copyAdmin.createStatement();
        ResultSet identity = columns.executeQuery(IDENTITY_COLUMNS)) {
      while (identity.next()) {
        String table = identity.getString(1);
        String column = identity.getString(2);
        String sequence = identity.getString(3);
        long highest = highestId(copyAdmin, table, column);
        long next = Math.addExact(highest, sequenceMargin);
        try (PreparedStatement advance = copyAdmin.prepareStatement(ADVANCE)) {
          advance.setString(1, sequence);
          advance.setLong(2, next);
          advance.executeQuery().close();
        }
        done.add(
            String.format(
                Locale.ROOT,
                "sequence %s advanced: highest %s.%s is %,d, next value %,d",
                sequence,
                table,
                column,
                highest,
                next));
      }
    }
    return done;
  }

  private static long highestId(Connection connection, String table, String column)
      throws SQLException {
    // table and column come quoted from the catalog query above, never from input.
    try (Statement statement = connection.createStatement();
        ResultSet highest =
            statement.executeQuery("SELECT coalesce(max(" + column + "), 0) FROM " + table)) {
      highest.next();
      return highest.getLong(1);
    }
  }
}
