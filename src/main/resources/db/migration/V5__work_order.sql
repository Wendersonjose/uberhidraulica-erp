CREATE SCHEMA IF NOT EXISTS workorder;
CREATE SEQUENCE workorder.work_order_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE workorder.work_order (
    id UUID PRIMARY KEY,
    number BIGINT NOT NULL DEFAULT nextval('workorder.work_order_number_seq'),
    customer_id UUID NOT NULL REFERENCES crm.customer(id),
    vehicle_id UUID NOT NULL,
    entry_mileage BIGINT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ABERTA',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_work_order_number UNIQUE (number),
    CONSTRAINT fk_work_order_vehicle_customer FOREIGN KEY (vehicle_id, customer_id)
        REFERENCES crm.vehicle(id, customer_id),
    CONSTRAINT ck_work_order_entry_mileage CHECK (entry_mileage >= 0),
    CONSTRAINT ck_work_order_status_initial CHECK (status = 'ABERTA')
);

CREATE INDEX ix_work_order_opened_at ON workorder.work_order(opened_at DESC, id);
CREATE INDEX ix_work_order_customer ON workorder.work_order(customer_id, opened_at DESC);
CREATE INDEX ix_work_order_vehicle ON workorder.work_order(vehicle_id, opened_at DESC);

CREATE TABLE workorder.work_order_service (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES workorder.work_order(id),
    service_id UUID NOT NULL REFERENCES servicecatalog.service(id),
    service_name VARCHAR(160) NOT NULL,
    service_description VARCHAR(1000) NOT NULL,
    base_price NUMERIC(15,2) NOT NULL,
    warranty_days INTEGER NOT NULL,
    added_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_work_order_service_name CHECK (btrim(service_name) <> ''),
    CONSTRAINT ck_work_order_service_description CHECK (btrim(service_description) <> ''),
    CONSTRAINT ck_work_order_service_price CHECK (base_price >= 0),
    CONSTRAINT ck_work_order_service_warranty CHECK (warranty_days >= 0)
);

CREATE INDEX ix_work_order_service_order ON workorder.work_order_service(work_order_id, added_at, id);
CREATE INDEX ix_work_order_service_catalog ON workorder.work_order_service(service_id);
