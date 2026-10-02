-- Дополняем каталог без изменения применённых V1/V2. Исключительная верхняя граница дат.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE rates ADD CONSTRAINT rates_no_active_overlap
 EXCLUDE USING gist (room_type_id WITH =, daterange(valid_from,valid_to,'[)') WITH &&) WHERE (active);
ALTER TABLE cities ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
UPDATE cities SET timezone=CASE
 WHEN catalog_key IN ('tashkent','samarkand') THEN 'Asia/Tashkent'
 WHEN catalog_key IN ('moscow','petersburg','saint-petersburg','spb') THEN 'Europe/Moscow'
 WHEN catalog_key IN ('istanbul','antalya') THEN 'Europe/Istanbul' ELSE timezone END;
CREATE TABLE room_blocks (
 id UUID PRIMARY KEY, room_id UUID NOT NULL REFERENCES rooms(id),
 start_date DATE NOT NULL, end_date DATE NOT NULL, reason TEXT NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, CHECK(end_date>start_date)
);
CREATE INDEX room_blocks_lookup ON room_blocks(room_id,start_date,end_date) WHERE active;
