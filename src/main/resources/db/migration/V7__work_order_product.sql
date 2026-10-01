CREATE TABLE workorder.work_order_product (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES workorder.work_order(id),
    product_id UUID NOT NULL REFERENCES productcatalog.product(id),
    product_description VARCHAR(200) NOT NULL,
    product_internal_code VARCHAR(60),
    unit VARCHAR(16) NOT NULL,
    quantity NUMERIC(15,3) NOT NULL,
    unit_price NUMERIC(15,2) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_work_order_product_description CHECK (btrim(product_description) <> ''),
    -- A unidade é snapshot: não se prende ao domínio atual do catálogo, que pode crescer.
    CONSTRAINT ck_work_order_product_unit CHECK (btrim(unit) <> ''),
    CONSTRAINT ck_work_order_product_quantity CHECK (quantity > 0),
    CONSTRAINT ck_work_order_product_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX ix_work_order_product_order ON workorder.work_order_product(work_order_id, added_at, id);
CREATE INDEX ix_work_order_product_catalog ON workorder.work_order_product(product_id);
