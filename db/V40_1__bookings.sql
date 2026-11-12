-- Module 40's lab tables: the part of SeatWave's booking schema (Modules 13, 28 and 42) that a
-- restore drill and the pilot-light copy need. Run once as seatwave_owner on the primary and on
-- the region-b copy, which must have the same tables before it subscribes.
CREATE SCHEMA booking AUTHORIZATION seatwave_owner;
GRANT USAGE ON SCHEMA booking TO seatwave_app, seatwave_replicator;

-- As in Module 13: identity-numbered customers, UUID bookings, one booking per seat.
CREATE TABLE booking.customers (
  customer_id BIGINT GENERATED ALWAYS AS IDENTITY,
  customer_ref VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT customers_pkey PRIMARY KEY (customer_id),
  CONSTRAINT one_customer_per_ref UNIQUE (customer_ref)
);
CREATE TABLE booking.bookings (
  booking_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL REFERENCES booking.customers (customer_id),
  price_paid NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  confirmed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bookings_pkey PRIMARY KEY (booking_id),
  CONSTRAINT one_booking_per_seat UNIQUE (event_id, seat_label)
);
-- As in Module 28: each confirm writes its event in the same transaction. outbox_id comes from a
-- sequence, which logical replication does not carry: the pilot light's promotion must advance it.
CREATE TABLE booking.outbox (
  outbox_id BIGINT GENERATED ALWAYS AS IDENTITY,
  booking_id UUID NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT outbox_pkey PRIMARY KEY (outbox_id)
);
-- Written once a second by the load generator: after a restore its newest row says how far the
-- restored data reaches (Section 6c), and after a promotion a write to it proves writes work.
CREATE TABLE booking.drill_heartbeat (
  beat_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT drill_heartbeat_pkey PRIMARY KEY (beat_at)
);

GRANT SELECT, INSERT ON booking.customers, booking.bookings, booking.outbox, booking.drill_heartbeat
  TO seatwave_app;
GRANT USAGE ON SEQUENCE booking.customers_customer_id_seq, booking.outbox_outbox_id_seq
  TO seatwave_app;
GRANT SELECT ON booking.customers, booking.bookings, booking.outbox, booking.drill_heartbeat
  TO seatwave_replicator;
