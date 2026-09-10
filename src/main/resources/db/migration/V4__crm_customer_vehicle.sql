CREATE SCHEMA IF NOT EXISTS crm;

CREATE TABLE crm.customer (
    id UUID PRIMARY KEY,
    person_type VARCHAR(2) NOT NULL,
    name VARCHAR(160) NOT NULL,
    document VARCHAR(14) NOT NULL,
    status VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_crm_customer_document UNIQUE (document),
    CONSTRAINT ck_crm_customer_person_type CHECK (person_type IN ('PF', 'PJ')),
    CONSTRAINT ck_crm_customer_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_crm_customer_document CHECK (
        (person_type = 'PF' AND document ~ '^[0-9]{11}$') OR
        (person_type = 'PJ' AND document ~ '^[0-9]{14}$')
    ),
    CONSTRAINT ck_crm_customer_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_crm_customer_status_name ON crm.customer(status, name, id);

CREATE TABLE crm.vehicle (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES crm.customer(id),
    plate VARCHAR(10) NOT NULL,
    manufacturer VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    model_year INTEGER NOT NULL,
    mileage BIGINT,
    steering_gear_manufacturer VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_crm_vehicle_plate UNIQUE (plate),
    CONSTRAINT uq_crm_vehicle_id_customer UNIQUE (id, customer_id),
    CONSTRAINT ck_crm_vehicle_plate CHECK (plate ~ '^[A-Z0-9]{1,10}$'),
    CONSTRAINT ck_crm_vehicle_manufacturer CHECK (btrim(manufacturer) <> ''),
    CONSTRAINT ck_crm_vehicle_model CHECK (btrim(model) <> ''),
    CONSTRAINT ck_crm_vehicle_year CHECK (model_year > 0),
    CONSTRAINT ck_crm_vehicle_mileage CHECK (mileage IS NULL OR mileage >= 0)
);

CREATE INDEX ix_crm_vehicle_customer ON crm.vehicle(customer_id, model, id);
