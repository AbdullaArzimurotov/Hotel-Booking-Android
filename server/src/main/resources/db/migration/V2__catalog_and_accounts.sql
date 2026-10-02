-- Новые сущности третьего этапа. V1 сохраняется без изменения контрольной суммы.
CREATE TABLE users (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash TEXT NOT NULL, full_name VARCHAR(120) NOT NULL, phone VARCHAR(30),
 role VARCHAR(10) NOT NULL CHECK(role IN ('USER','ADMIN')),
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(email = lower(trim(email)))
);
CREATE TABLE countries (
 id UUID PRIMARY KEY, catalog_key TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
 currency CHAR(3) NOT NULL CHECK(currency IN ('UZS','RUB','TRY')), active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE cities (
 id UUID PRIMARY KEY, catalog_key TEXT NOT NULL UNIQUE, country_id UUID NOT NULL REFERENCES countries(id),
 name TEXT NOT NULL, caption TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX cities_country_idx ON cities(country_id);
CREATE TABLE hotels (
 id UUID PRIMARY KEY, catalog_key TEXT NOT NULL UNIQUE, city_id UUID NOT NULL REFERENCES cities(id),
 name TEXT NOT NULL, stars SMALLINT NOT NULL CHECK(stars BETWEEN 3 AND 5),
 rating NUMERIC(3,1) NOT NULL CHECK(rating BETWEEN 0 AND 10),
 distance_km NUMERIC(6,2) NOT NULL CHECK(distance_km >= 0),
 address TEXT NOT NULL, description TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX hotels_city_idx ON hotels(city_id);
CREATE TABLE hotel_photos (
 id UUID PRIMARY KEY, hotel_id UUID NOT NULL REFERENCES hotels(id), photo_key TEXT NOT NULL,
 position INT NOT NULL CHECK(position >= 0), active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(hotel_id,position)
);
CREATE TABLE room_types (
 id UUID PRIMARY KEY, hotel_id UUID NOT NULL REFERENCES hotels(id),
 kind TEXT NOT NULL CHECK(kind IN ('STANDARD','SUPERIOR','SUITE')),
 capacity SMALLINT NOT NULL CHECK(capacity BETWEEN 1 AND 8), area INT NOT NULL CHECK(area > 0),
 active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(hotel_id,kind)
);
CREATE TABLE room_type_photos (
 id UUID PRIMARY KEY, room_type_id UUID NOT NULL REFERENCES room_types(id), photo_key TEXT NOT NULL,
 position INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(room_type_id,position)
);
CREATE TABLE rooms (
 id UUID PRIMARY KEY, room_type_id UUID NOT NULL REFERENCES room_types(id),
 number TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(room_type_id,number)
);
CREATE INDEX rooms_type_idx ON rooms(room_type_id);
CREATE TABLE rates (
 id UUID PRIMARY KEY, room_type_id UUID NOT NULL REFERENCES room_types(id),
 nightly_amount NUMERIC(12,2) NOT NULL CHECK(nightly_amount >= 0),
 currency CHAR(3) NOT NULL, valid_from DATE NOT NULL, valid_to DATE NOT NULL,
 allow_demo BOOLEAN NOT NULL DEFAULT TRUE, allow_at_hotel BOOLEAN NOT NULL DEFAULT TRUE,
 free_cancel_hours INT NOT NULL DEFAULT 24 CHECK(free_cancel_hours >= 0),
 active BOOLEAN NOT NULL DEFAULT TRUE, CHECK(valid_to > valid_from),
 UNIQUE(room_type_id,valid_from,valid_to)
);
CREATE INDEX rates_type_idx ON rates(room_type_id);
CREATE TABLE amenities (
 id UUID PRIMARY KEY, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE room_type_amenities (
 room_type_id UUID NOT NULL REFERENCES room_types(id), amenity_id UUID NOT NULL REFERENCES amenities(id),
 PRIMARY KEY(room_type_id,amenity_id)
);
CREATE TABLE services (
 id UUID PRIMARY KEY, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, description TEXT NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE hotel_services (
 hotel_id UUID NOT NULL REFERENCES hotels(id), service_id UUID NOT NULL REFERENCES services(id),
 amount NUMERIC(12,2) NOT NULL CHECK(amount >= 0), charge_unit TEXT NOT NULL CHECK(charge_unit IN ('PER_NIGHT_ROOM','ONCE')),
 active BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(hotel_id,service_id)
);
CREATE TABLE places (
 id UUID PRIMARY KEY, catalog_key TEXT NOT NULL UNIQUE, city_id UUID NOT NULL REFERENCES cities(id),
 name TEXT NOT NULL, category TEXT NOT NULL CHECK(category IN ('RESTAURANT','CAFE','PARK','MUSEUM','LEISURE')),
 description TEXT NOT NULL, address TEXT NOT NULL, photo_key TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX places_city_idx ON places(city_id);
CREATE TABLE hotel_places (
 hotel_id UUID NOT NULL REFERENCES hotels(id), place_id UUID NOT NULL REFERENCES places(id),
 PRIMARY KEY(hotel_id,place_id)
);
