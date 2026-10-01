-- TASK-0019: primeira vertical de Compras & Fornecedores.
-- Compras preserva o fato comercial; Estoque continua proprietário do saldo/custo médio
-- e Financeiro continua proprietário da obrigação e quitação.

CREATE SCHEMA IF NOT EXISTS purchasing;

CREATE TABLE purchasing.supplier (
    id UUID PRIMARY KEY,
    person_type VARCHAR(2) NOT NULL,
    legal_name VARCHAR(160) NOT NULL,
    trade_name VARCHAR(160),
    document VARCHAR(24),
    phone VARCHAR(24),
    email VARCHAR(254),
    address_zip_code VARCHAR(10),
    address_street VARCHAR(160),
    address_number VARCHAR(20),
    address_complement VARCHAR(80),
    address_district VARCHAR(80),
    address_city VARCHAR(80),
    address_state VARCHAR(2),
    payment_terms VARCHAR(160),
    preferred_payment_method VARCHAR(80),
    usual_due_day SMALLINT,
    credit_limit NUMERIC(19,2),
    commercial_notes VARCHAR(1000),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_supplier_person_type CHECK (person_type IN ('PF','PJ')),
    CONSTRAINT ck_supplier_legal_name CHECK (btrim(legal_name) <> ''),
    CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT ck_supplier_due_day CHECK (usual_due_day IS NULL OR usual_due_day BETWEEN 1 AND 31),
    CONSTRAINT ck_supplier_credit_limit CHECK (credit_limit IS NULL OR credit_limit >= 0)
);
CREATE UNIQUE INDEX uq_supplier_document ON purchasing.supplier (document) WHERE document IS NOT NULL;
CREATE INDEX ix_supplier_name ON purchasing.supplier (lower(legal_name));
CREATE INDEX ix_supplier_status ON purchasing.supplier (status, lower(legal_name));

CREATE TABLE purchasing.supplier_contact (
    id UUID PRIMARY KEY,
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(id),
    name VARCHAR(160) NOT NULL,
    role VARCHAR(80),
    phone VARCHAR(24),
    email VARCHAR(254),
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_supplier_contact_name CHECK (btrim(name) <> '')
);
CREATE INDEX ix_supplier_contact_supplier ON purchasing.supplier_contact (supplier_id, active DESC, name);
CREATE UNIQUE INDEX uq_supplier_primary_contact ON purchasing.supplier_contact (supplier_id)
    WHERE primary_contact = TRUE AND active = TRUE;

CREATE TABLE purchasing.purchase_order (
    id UUID PRIMARY KEY,
    number BIGSERIAL NOT NULL,
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(id),
    status VARCHAR(32) NOT NULL,
    origin VARCHAR(32) NOT NULL,
    work_order_id UUID REFERENCES workorder.work_order(id),
    ordered_on DATE NOT NULL,
    expected_on DATE,
    payment_terms_snapshot VARCHAR(160),
    payment_method_snapshot VARCHAR(80),
    freight_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    cancelled_at TIMESTAMPTZ,
    cancelled_by UUID,
    cancellation_reason VARCHAR(500),
    CONSTRAINT uq_purchase_order_number UNIQUE (number),
    CONSTRAINT ck_purchase_order_status CHECK (status IN ('DRAFT','OPEN','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),
    CONSTRAINT ck_purchase_order_origin CHECK (origin IN ('DIRECT','REPLENISHMENT','WORK_ORDER')),
    CONSTRAINT ck_purchase_order_work_order_origin CHECK ((origin='WORK_ORDER' AND work_order_id IS NOT NULL) OR origin<>'WORK_ORDER'),
    CONSTRAINT ck_purchase_order_amounts CHECK (freight_amount >= 0 AND discount_amount >= 0),
    CONSTRAINT ck_purchase_order_cancel CHECK (
        (status <> 'CANCELLED' AND cancelled_at IS NULL AND cancelled_by IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND cancellation_reason IS NOT NULL AND btrim(cancellation_reason) <> '')
    )
);
CREATE INDEX ix_purchase_order_supplier ON purchasing.purchase_order (supplier_id, ordered_on DESC);
CREATE INDEX ix_purchase_order_status ON purchasing.purchase_order (status, ordered_on DESC);

CREATE TABLE purchasing.purchase_order_item (
    id UUID PRIMARY KEY,
    purchase_order_id UUID NOT NULL REFERENCES purchasing.purchase_order(id),
    product_id UUID NOT NULL REFERENCES productcatalog.product(id),
    product_description_snapshot VARCHAR(200) NOT NULL,
    ordered_quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    discount_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    notes VARCHAR(500),
    display_order INTEGER NOT NULL,
    CONSTRAINT uq_purchase_order_item_order UNIQUE (purchase_order_id, display_order),
    CONSTRAINT ck_purchase_order_item_quantity CHECK (ordered_quantity > 0),
    CONSTRAINT ck_purchase_order_item_price CHECK (unit_price >= 0 AND discount_amount >= 0),
    CONSTRAINT ck_purchase_order_item_display_order CHECK (display_order > 0)
);
CREATE INDEX ix_purchase_order_item_product ON purchasing.purchase_order_item (product_id, purchase_order_id);

CREATE TABLE purchasing.goods_receipt (
    id UUID PRIMARY KEY,
    purchase_order_id UUID NOT NULL REFERENCES purchasing.purchase_order(id),
    received_at TIMESTAMPTZ NOT NULL,
    received_by UUID NOT NULL,
    document_reference VARCHAR(120),
    notes VARCHAR(1000),
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_goods_receipt_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX ix_goods_receipt_order ON purchasing.goods_receipt (purchase_order_id, received_at, id);

CREATE TABLE purchasing.goods_receipt_item (
    id UUID PRIMARY KEY,
    goods_receipt_id UUID NOT NULL REFERENCES purchasing.goods_receipt(id),
    purchase_order_item_id UUID NOT NULL REFERENCES purchasing.purchase_order_item(id),
    received_quantity NUMERIC(19,4) NOT NULL,
    received_unit_cost NUMERIC(19,4) NOT NULL,
    quantity_divergence BOOLEAN NOT NULL,
    price_divergence BOOLEAN NOT NULL,
    divergence_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    divergence_reason VARCHAR(500),
    CONSTRAINT uq_goods_receipt_item UNIQUE (goods_receipt_id, purchase_order_item_id),
    CONSTRAINT ck_goods_receipt_item_quantity CHECK (received_quantity > 0),
    CONSTRAINT ck_goods_receipt_item_cost CHECK (received_unit_cost >= 0),
    CONSTRAINT ck_goods_receipt_item_divergence CHECK (
        (NOT quantity_divergence AND NOT price_divergence AND NOT divergence_accepted AND divergence_reason IS NULL)
        OR ((quantity_divergence OR price_divergence) AND divergence_accepted AND divergence_reason IS NOT NULL AND btrim(divergence_reason) <> '')
    )
);
CREATE INDEX ix_goods_receipt_item_order_item ON purchasing.goods_receipt_item (purchase_order_item_id);

CREATE TABLE purchasing.supplier_price_history (
    id UUID PRIMARY KEY,
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(id),
    product_id UUID NOT NULL REFERENCES productcatalog.product(id),
    purchase_order_id UUID NOT NULL REFERENCES purchasing.purchase_order(id),
    goods_receipt_item_id UUID NOT NULL REFERENCES purchasing.goods_receipt_item(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_cost NUMERIC(19,4) NOT NULL,
    CONSTRAINT uq_supplier_price_history_receipt_item UNIQUE (goods_receipt_item_id),
    CONSTRAINT ck_supplier_price_history_quantity CHECK (quantity > 0),
    CONSTRAINT ck_supplier_price_history_cost CHECK (unit_cost >= 0)
);
CREATE INDEX ix_supplier_price_history_lookup ON purchasing.supplier_price_history (supplier_id, product_id, recorded_at DESC);

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000031', 'PURCHASE_VIEW', 'Consultar Compras e Fornecedores'),
    ('10000000-0000-0000-0000-000000000032', 'PURCHASE_SUPPLIER_MANAGE', 'Cadastrar e alterar fornecedores'),
    ('10000000-0000-0000-0000-000000000033', 'PURCHASE_ORDER_MANAGE', 'Criar e alterar pedidos de compra'),
    ('10000000-0000-0000-0000-000000000034', 'PURCHASE_RECEIVE', 'Registrar recebimentos de compra'),
    ('10000000-0000-0000-0000-000000000035', 'PURCHASE_DIVERGENCE_ACCEPT', 'Aceitar divergências de recebimento'),
    ('10000000-0000-0000-0000-000000000036', 'PURCHASE_CANCEL', 'Cancelar pedido de compra')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO','GERENTE_ADMINISTRATIVO')
  AND permission.code LIKE 'PURCHASE\_%'
ON CONFLICT DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code = 'GERENTE_FINANCEIRO' AND permission.code = 'PURCHASE_VIEW'
ON CONFLICT DO NOTHING;
