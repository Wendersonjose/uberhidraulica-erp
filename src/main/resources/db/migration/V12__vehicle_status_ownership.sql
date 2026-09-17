-- TASK-0010: ano opcional, cor, observações, inativação lógica e histórico de propriedade.

ALTER TABLE crm.vehicle ALTER COLUMN model_year DROP NOT NULL;
ALTER TABLE crm.vehicle DROP CONSTRAINT ck_crm_vehicle_year;
ALTER TABLE crm.vehicle
    ADD COLUMN color VARCHAR(40),
    ADD COLUMN notes VARCHAR(1000),
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT ck_crm_vehicle_year CHECK (model_year IS NULL OR model_year > 0),
    ADD CONSTRAINT ck_crm_vehicle_color CHECK (color IS NULL OR btrim(color) <> ''),
    ADD CONSTRAINT ck_crm_vehicle_notes CHECK (notes IS NULL OR btrim(notes) <> '');

CREATE INDEX ix_crm_vehicle_active_plate ON crm.vehicle(active, plate);

-- Cada período de propriedade é preservado; somente um período aberto por veículo.
CREATE TABLE crm.vehicle_ownership (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES crm.vehicle(id),
    customer_id UUID NOT NULL REFERENCES crm.customer(id),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    changed_by UUID,
    CONSTRAINT ck_crm_vehicle_ownership_period CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE UNIQUE INDEX uq_crm_vehicle_ownership_current ON crm.vehicle_ownership(vehicle_id) WHERE ended_at IS NULL;
CREATE INDEX ix_crm_vehicle_ownership_vehicle ON crm.vehicle_ownership(vehicle_id, started_at);
CREATE INDEX ix_crm_vehicle_ownership_customer ON crm.vehicle_ownership(customer_id, started_at);

INSERT INTO crm.vehicle_ownership (id, vehicle_id, customer_id, started_at)
SELECT gen_random_uuid(), id, customer_id, created_at FROM crm.vehicle;

-- A OS guarda o cliente da abertura. Com troca de proprietário, o par (veículo, cliente) deixa de
-- ser permanente no cadastro; a pertinência na abertura continua verificada pela aplicação.
ALTER TABLE workorder.work_order DROP CONSTRAINT fk_work_order_vehicle_customer;
ALTER TABLE workorder.work_order ADD CONSTRAINT fk_work_order_vehicle FOREIGN KEY (vehicle_id) REFERENCES crm.vehicle(id);
