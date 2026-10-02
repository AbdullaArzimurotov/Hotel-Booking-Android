-- Один заказ содержит несколько физических номеров; удалить историю каскадом нельзя.
CREATE TABLE bookings (
 id UUID PRIMARY KEY, number TEXT NOT NULL UNIQUE, user_id UUID NOT NULL REFERENCES users(id),
 hotel_id UUID NOT NULL REFERENCES hotels(id), room_type_id UUID NOT NULL REFERENCES room_types(id),
 rate_id UUID NOT NULL REFERENCES rates(id), check_in DATE NOT NULL, check_out DATE NOT NULL,
 adults INT NOT NULL CHECK(adults BETWEEN 1 AND 8), children INT NOT NULL CHECK(children BETWEEN 0 AND 4),
 room_count INT NOT NULL CHECK(room_count BETWEEN 1 AND 4),
 status TEXT NOT NULL CHECK(status IN ('PENDING_PAYMENT','CONFIRMED','CANCELLED','COMPLETED')),
 payment_method TEXT NOT NULL CHECK(payment_method IN ('ONLINE_DEMO','PAY_AT_HOTEL')),
 payment_status TEXT NOT NULL CHECK(payment_status IN ('UNPAID','PAID','REVERSED')),
 total_amount NUMERIC(12,2) NOT NULL CHECK(total_amount>=0), currency CHAR(3) NOT NULL,
 expires_at TIMESTAMPTZ, cancel_until TIMESTAMPTZ NOT NULL, checkout_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, cancellation_reason TEXT,
 idempotency_key UUID NOT NULL, request_hash TEXT NOT NULL, details_snapshot JSONB NOT NULL,
 UNIQUE(user_id,idempotency_key), CHECK(check_out>check_in), CHECK(adults>=room_count),
 CHECK(status<>'PENDING_PAYMENT' OR expires_at IS NOT NULL)
);
CREATE TABLE booking_rooms (
 booking_id UUID NOT NULL REFERENCES bookings(id), room_id UUID NOT NULL REFERENCES rooms(id),
 PRIMARY KEY(booking_id,room_id)
);
CREATE INDEX booking_rooms_room ON booking_rooms(room_id,booking_id);
CREATE INDEX bookings_dates ON bookings(check_in,check_out,status);
CREATE INDEX bookings_user ON bookings(user_id,created_at DESC);
CREATE TABLE booking_services (
 booking_id UUID NOT NULL REFERENCES bookings(id), service_id UUID NOT NULL REFERENCES services(id),
 amount NUMERIC(12,2) NOT NULL CHECK(amount>=0), details JSONB NOT NULL,
 PRIMARY KEY(booking_id,service_id)
);
