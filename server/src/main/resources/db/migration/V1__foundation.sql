-- Stage one only. Hotel/user/booking tables are introduced in stage three.
CREATE TABLE app_metadata (
    key VARCHAR(64) PRIMARY KEY,
    value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app_metadata (key, value) VALUES
    ('project_name', 'Гостиница'),
    ('schema_stage', '1');
