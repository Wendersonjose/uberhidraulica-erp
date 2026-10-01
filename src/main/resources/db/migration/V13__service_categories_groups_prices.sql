-- TASK-0011 / DR-0011: categorias cadastradas, grupos de veículos, preços por veículo/grupo,
-- descrição e preço base opcionais.

CREATE TABLE servicecatalog.service_category (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_service_category_name CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_service_category_name ON servicecatalog.service_category(lower(name));

-- Categorias em texto livre viram cadastro; variações de maiúsculas são unificadas.
INSERT INTO servicecatalog.service_category (id, name, active, created_at, updated_at)
SELECT gen_random_uuid(), min(btrim(category)), TRUE, now(), now()
FROM servicecatalog.service
WHERE category IS NOT NULL AND btrim(category) <> ''
GROUP BY lower(btrim(category));

ALTER TABLE servicecatalog.service ADD COLUMN category_id UUID REFERENCES servicecatalog.service_category(id);
UPDATE servicecatalog.service s SET category_id = c.id
FROM servicecatalog.service_category c
WHERE s.category IS NOT NULL AND lower(btrim(s.category)) = lower(c.name);
ALTER TABLE servicecatalog.service DROP COLUMN category;
CREATE INDEX ix_service_category ON servicecatalog.service(category_id);

ALTER TABLE servicecatalog.service ALTER COLUMN description DROP NOT NULL;
ALTER TABLE servicecatalog.service DROP CONSTRAINT ck_service_description_not_blank;
ALTER TABLE servicecatalog.service ADD CONSTRAINT ck_service_description_not_blank CHECK (description IS NULL OR btrim(description) <> '');
ALTER TABLE servicecatalog.service ALTER COLUMN base_price DROP NOT NULL;
CREATE INDEX ix_service_lower_name ON servicecatalog.service(lower(name), id);

CREATE TABLE servicecatalog.vehicle_group (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_vehicle_group_name CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_vehicle_group_name ON servicecatalog.vehicle_group(lower(name));

-- A chave primária em vehicle_id garante que cada veículo pertença a no máximo um grupo.
CREATE TABLE servicecatalog.vehicle_group_member (
    vehicle_id UUID PRIMARY KEY REFERENCES crm.vehicle(id),
    group_id UUID NOT NULL REFERENCES servicecatalog.vehicle_group(id),
    added_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_vehicle_group_member_group ON servicecatalog.vehicle_group_member(group_id, vehicle_id);

CREATE TABLE servicecatalog.service_price (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES servicecatalog.service(id),
    vehicle_id UUID REFERENCES crm.vehicle(id),
    vehicle_group_id UUID REFERENCES servicecatalog.vehicle_group(id),
    price NUMERIC(15,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_service_price_nonnegative CHECK (price >= 0),
    CONSTRAINT ck_service_price_single_target CHECK ((vehicle_id IS NULL) <> (vehicle_group_id IS NULL))
);
CREATE UNIQUE INDEX uq_service_price_vehicle ON servicecatalog.service_price(service_id, vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE UNIQUE INDEX uq_service_price_group ON servicecatalog.service_price(service_id, vehicle_group_id) WHERE vehicle_group_id IS NOT NULL;

-- A OS guarda o preço praticado e de onde veio a sugestão; NULL identifica lançamentos anteriores.
ALTER TABLE workorder.work_order_service
    ADD COLUMN price_source VARCHAR(8),
    ADD CONSTRAINT ck_work_order_service_price_source CHECK (price_source IS NULL OR price_source IN ('VEHICLE', 'GROUP', 'BASE', 'MANUAL'));

-- Serviço sem descrição gera snapshot sem descrição, em vez de inventar texto.
ALTER TABLE workorder.work_order_service ALTER COLUMN service_description DROP NOT NULL;
ALTER TABLE workorder.work_order_service DROP CONSTRAINT ck_work_order_service_description;
ALTER TABLE workorder.work_order_service ADD CONSTRAINT ck_work_order_service_description CHECK (service_description IS NULL OR btrim(service_description) <> '');
