-- Демооперация: никаких реквизитов карты или связи с платёжным шлюзом.
CREATE TABLE payments (
 id UUID PRIMARY KEY, booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id),
 user_id UUID NOT NULL REFERENCES users(id), idempotency_key UUID NOT NULL,
 transaction_id TEXT NOT NULL UNIQUE, amount NUMERIC(12,2) NOT NULL,
 currency CHAR(3) NOT NULL, paid_at TIMESTAMPTZ NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PAID','REVERSED')), UNIQUE(user_id,idempotency_key)
);
CREATE TABLE receipts (
 id UUID PRIMARY KEY, payment_id UUID NOT NULL UNIQUE REFERENCES payments(id),
 booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id), document_snapshot JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL
);
