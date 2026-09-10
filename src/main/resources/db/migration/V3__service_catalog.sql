CREATE SCHEMA IF NOT EXISTS servicecatalog;

CREATE TABLE servicecatalog.service (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    category VARCHAR(100),
    base_price NUMERIC(15,2) NOT NULL,
    default_warranty_days INTEGER NOT NULL DEFAULT 90,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_service_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_service_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT ck_service_base_price_nonnegative CHECK (base_price >= 0),
    CONSTRAINT ck_service_warranty_nonnegative CHECK (default_warranty_days >= 0)
);

CREATE INDEX ix_service_active_name ON servicecatalog.service(active, name, id);
