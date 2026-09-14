CREATE SCHEMA IF NOT EXISTS productcatalog;

CREATE TABLE productcatalog.product (
    id UUID PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    internal_code VARCHAR(60),
    category VARCHAR(100),
    item_type VARCHAR(24) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    reference_cost NUMERIC(15,2),
    sale_price NUMERIC(15,2),
    minimum_stock NUMERIC(15,3),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_product_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT ck_product_internal_code CHECK (internal_code IS NULL OR internal_code ~ '^[A-Z0-9][A-Z0-9._-]*$'),
    CONSTRAINT ck_product_item_type CHECK (item_type IN ('PART', 'SUPPLY', 'COMPONENT', 'KIT', 'INTERNAL_USE_MATERIAL')),
    CONSTRAINT ck_product_unit CHECK (unit IN ('UNIDADE', 'LITRO', 'METRO', 'QUILOGRAMA')),
    CONSTRAINT ck_product_reference_cost_nonnegative CHECK (reference_cost IS NULL OR reference_cost >= 0),
    CONSTRAINT ck_product_sale_price_nonnegative CHECK (sale_price IS NULL OR sale_price >= 0),
    CONSTRAINT ck_product_minimum_stock_nonnegative CHECK (minimum_stock IS NULL OR minimum_stock >= 0)
);

-- Código interno é opcional, mas quando informado identifica o item de forma única no catálogo.
CREATE UNIQUE INDEX uq_product_internal_code ON productcatalog.product(internal_code) WHERE internal_code IS NOT NULL;

CREATE INDEX ix_product_active_description ON productcatalog.product(active, description, id);
