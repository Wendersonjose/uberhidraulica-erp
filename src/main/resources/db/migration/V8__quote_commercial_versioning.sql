-- Orçamento com versionamento comercial.
-- Modelo conforme docs/architecture/database/TASK-0001-modelo-dados-aprovacao-orcamento.md (REVISION 2).
-- As FKs compostas existem para que o PostgreSQL rejeite associações cross-quote mesmo com bug no Java
-- (finding HIGH-01 da revisão da TASK-0001).
CREATE SCHEMA IF NOT EXISTS workshop;

CREATE TABLE workshop.quote (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES workorder.work_order(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    -- created_by é referência de auditoria ao usuário autenticado, não relação de propriedade:
    -- uma FK para o schema iam acoplaria Orçamento ao IAM no nível do banco.
    created_by UUID NOT NULL
);

CREATE INDEX idx_quote_work_order ON workshop.quote(work_order_id);

CREATE TABLE workshop.quote_revision (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    presented_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_quote_revision_quote
        FOREIGN KEY (quote_id) REFERENCES workshop.quote(id),
    CONSTRAINT uq_quote_revision_number UNIQUE (quote_id, revision_number),
    CONSTRAINT uq_quote_revision_id_quote UNIQUE (id, quote_id),
    CONSTRAINT ck_quote_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_quote_revision_status CHECK (status IN ('DRAFT', 'PRESENTED')),
    -- EXPIRED não é estado persistido: a expiração é derivada de valid_until com o Clock do servidor.
    CONSTRAINT ck_quote_revision_presentation CHECK (
        status = 'DRAFT'
        OR (status = 'PRESENTED'
            AND presented_at IS NOT NULL
            AND valid_until IS NOT NULL
            AND valid_until >= presented_at)
    )
);

CREATE INDEX idx_quote_revision_quote ON workshop.quote_revision(quote_id, revision_number);

CREATE TABLE workshop.quote_item (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    work_order_service_id UUID REFERENCES workorder.work_order_service(id),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,

    CONSTRAINT fk_quote_item_quote
        FOREIGN KEY (quote_id) REFERENCES workshop.quote(id),
    CONSTRAINT uq_quote_item_id_quote UNIQUE (id, quote_id)
);

CREATE INDEX idx_quote_item_quote ON workshop.quote_item(quote_id);
CREATE INDEX idx_quote_item_work_order_service ON workshop.quote_item(work_order_service_id);

CREATE TABLE workshop.quote_item_revision (
    id UUID PRIMARY KEY,
    -- quote_id é redundante logicamente e mantido de propósito: habilita a FK composta abaixo.
    quote_id UUID NOT NULL,
    quote_item_id UUID NOT NULL,
    revision_sequence INTEGER NOT NULL,
    description VARCHAR(1000) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    total_price NUMERIC(19,4) NOT NULL,
    revision_reason VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,

    CONSTRAINT fk_quote_item_revision_item_quote
        FOREIGN KEY (quote_item_id, quote_id) REFERENCES workshop.quote_item(id, quote_id),
    CONSTRAINT uq_quote_item_revision_sequence UNIQUE (quote_item_id, revision_sequence),
    CONSTRAINT uq_quote_item_revision_id_quote UNIQUE (id, quote_id),
    CONSTRAINT ck_quote_item_revision_sequence CHECK (revision_sequence > 0),
    CONSTRAINT ck_quote_item_revision_description CHECK (btrim(description) <> ''),
    CONSTRAINT ck_quote_item_revision_quantity CHECK (quantity > 0),
    CONSTRAINT ck_quote_item_revision_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_quote_item_revision_total_price CHECK (total_price >= 0)
);

CREATE INDEX idx_quote_item_revision_item ON workshop.quote_item_revision(quote_item_id, revision_sequence);

CREATE TABLE workshop.quote_revision_item (
    quote_revision_id UUID NOT NULL,
    quote_item_revision_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    display_order INTEGER NOT NULL,

    PRIMARY KEY (quote_revision_id, quote_item_revision_id),
    CONSTRAINT fk_quote_revision_item_revision_quote
        FOREIGN KEY (quote_revision_id, quote_id) REFERENCES workshop.quote_revision(id, quote_id),
    CONSTRAINT fk_quote_revision_item_item_revision_quote
        FOREIGN KEY (quote_item_revision_id, quote_id) REFERENCES workshop.quote_item_revision(id, quote_id),
    CONSTRAINT uq_quote_revision_item_identity
        UNIQUE (quote_revision_id, quote_item_revision_id, quote_id),
    CONSTRAINT uq_quote_revision_display_order UNIQUE (quote_revision_id, display_order),
    CONSTRAINT ck_quote_revision_display_order CHECK (display_order > 0)
);

CREATE INDEX idx_quote_revision_item_item_revision ON workshop.quote_revision_item(quote_item_revision_id);
