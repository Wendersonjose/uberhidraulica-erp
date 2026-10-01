-- TASK-0014 / DR-0014: saldo por produto, movimentações imutáveis, custo médio ponderado e
-- integração configurável com a baixa pela Ordem de Serviço.

-- Unidades de embalagem fechada, para quem controla fluido por galão ou balde.
ALTER TABLE productcatalog.product DROP CONSTRAINT ck_product_unit;
ALTER TABLE productcatalog.product ADD CONSTRAINT ck_product_unit
    CHECK (unit IN ('UNIDADE', 'LITRO', 'METRO', 'QUILOGRAMA', 'GALAO_5L', 'BALDE_20L'));

CREATE SCHEMA IF NOT EXISTS inventory;

CREATE TABLE inventory.stock_balance (
    product_id UUID PRIMARY KEY REFERENCES productcatalog.product(id),
    quantity NUMERIC(15,3) NOT NULL DEFAULT 0,
    average_cost NUMERIC(19,4),
    updated_at TIMESTAMPTZ NOT NULL,
    -- Estoque nunca negativo: a regra vive no banco, não só na aplicação.
    CONSTRAINT ck_stock_balance_nonnegative CHECK (quantity >= 0),
    CONSTRAINT ck_stock_balance_average_cost CHECK (average_cost IS NULL OR average_cost >= 0)
);

CREATE TABLE inventory.stock_movement (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES productcatalog.product(id),
    movement_type VARCHAR(24) NOT NULL,
    direction VARCHAR(3) NOT NULL,
    quantity NUMERIC(15,3) NOT NULL,
    unit_cost NUMERIC(19,4),
    balance_after NUMERIC(15,3) NOT NULL,
    average_cost_after NUMERIC(19,4),
    reason VARCHAR(500),
    source_type VARCHAR(16) NOT NULL,
    work_order_id UUID,
    work_order_item_id UUID,
    reverses_movement_id UUID REFERENCES inventory.stock_movement(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID,
    CONSTRAINT ck_stock_movement_type CHECK (movement_type IN ('ENTRY', 'EXIT', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT',
        'WORK_ORDER_OUT', 'WORK_ORDER_RETURN', 'REVERSAL')),
    CONSTRAINT ck_stock_movement_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_movement_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT ck_stock_movement_balance CHECK (balance_after >= 0),
    -- Ajuste sem motivo é correção sem explicação: o banco recusa.
    CONSTRAINT ck_stock_movement_adjustment_reason CHECK (movement_type NOT IN ('ADJUSTMENT_IN', 'ADJUSTMENT_OUT') OR btrim(coalesce(reason, '')) <> ''),
    CONSTRAINT ck_stock_movement_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT ck_stock_movement_source CHECK (source_type IN ('MANUAL', 'WORK_ORDER')),
    CONSTRAINT ck_stock_movement_work_order CHECK ((source_type = 'WORK_ORDER') = (work_order_id IS NOT NULL))
);

-- Cada movimentação é estornada no máximo uma vez; a correção é sempre um novo movimento.
CREATE UNIQUE INDEX uq_stock_movement_reversal ON inventory.stock_movement(reverses_movement_id) WHERE reverses_movement_id IS NOT NULL;
-- Um item físico da OS gera no máximo uma baixa.
CREATE UNIQUE INDEX uq_stock_movement_work_order_item ON inventory.stock_movement(work_order_item_id) WHERE movement_type = 'WORK_ORDER_OUT';
CREATE INDEX ix_stock_movement_product ON inventory.stock_movement(product_id, occurred_at DESC, id);
CREATE INDEX ix_stock_movement_work_order ON inventory.stock_movement(work_order_id) WHERE work_order_id IS NOT NULL;

CREATE TABLE inventory.settings (
    key VARCHAR(32) PRIMARY KEY,
    value VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inventory_settings_write_off CHECK (key <> 'WORK_ORDER_WRITE_OFF' OR value IN ('ITEM_LAUNCH', 'WORK_ORDER_FINISH', 'DISABLED'))
);
INSERT INTO inventory.settings (key, value, updated_at) VALUES ('WORK_ORDER_WRITE_OFF', 'ITEM_LAUNCH', now());

-- Todo produto já cadastrado passa a ter saldo zero explícito.
INSERT INTO inventory.stock_balance (product_id, quantity, average_cost, updated_at)
SELECT id, 0, NULL, now() FROM productcatalog.product;
