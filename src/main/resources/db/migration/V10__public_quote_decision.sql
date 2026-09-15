-- Acesso público ao orçamento e decisão do cliente.
-- Modelo conforme docs/architecture/database/TASK-0001-modelo-dados-aprovacao-orcamento.md (REVISION 2),
-- seções 38 a 65. As FKs compostas continuam sendo o que impede associação cross-quote no banco.

CREATE TABLE workshop.public_quote_access (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    quote_revision_id UUID NOT NULL,
    -- Somente o digest SHA-256 do token é persistido; o token bruto é entregue uma única vez.
    token_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_by UUID NOT NULL,

    CONSTRAINT fk_public_quote_access_revision_quote
        FOREIGN KEY (quote_revision_id, quote_id) REFERENCES workshop.quote_revision(id, quote_id),
    CONSTRAINT uq_public_quote_access_token_digest UNIQUE (token_digest),
    CONSTRAINT uq_public_quote_access_scope UNIQUE (id, quote_revision_id, quote_id),
    CONSTRAINT ck_public_quote_access_validity CHECK (valid_until >= created_at),
    CONSTRAINT ck_public_quote_access_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_public_quote_access_digest_length CHECK (octet_length(token_digest) = 32)
);

CREATE INDEX idx_public_quote_access_revision ON workshop.public_quote_access(quote_revision_id);

CREATE TABLE workshop.quote_decision_submission (
    id UUID PRIMARY KEY,
    public_quote_access_id UUID NOT NULL,
    quote_revision_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    request_payload_digest BYTEA NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    document_type VARCHAR(10) NOT NULL,
    document_number VARCHAR(14) NOT NULL,
    explicit_acceptance BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    ip_address INET NOT NULL,
    user_agent VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,

    -- O triplo garante que a submissão declare exatamente o acesso, a revisão e o orçamento autorizados.
    CONSTRAINT fk_quote_submission_access_scope
        FOREIGN KEY (public_quote_access_id, quote_revision_id, quote_id)
        REFERENCES workshop.public_quote_access(id, quote_revision_id, quote_id),
    CONSTRAINT uq_quote_submission_request UNIQUE (public_quote_access_id, request_id),
    CONSTRAINT uq_quote_submission_scope UNIQUE (id, quote_revision_id, quote_id),
    CONSTRAINT ck_quote_submission_document_type CHECK (document_type IN ('CPF', 'CNPJ')),
    CONSTRAINT ck_quote_submission_document_length CHECK (
        (document_type = 'CPF' AND char_length(document_number) = 11)
        OR (document_type = 'CNPJ' AND char_length(document_number) = 14)
    ),
    CONSTRAINT ck_quote_submission_document_digits CHECK (document_number ~ '^[0-9]+$'),
    CONSTRAINT ck_quote_submission_name CHECK (btrim(customer_name) <> ''),
    -- Abrir o link não decide: sem aceite explícito não existe submissão.
    CONSTRAINT ck_quote_submission_acceptance CHECK (explicit_acceptance = TRUE)
);

CREATE INDEX idx_quote_submission_quote ON workshop.quote_decision_submission(quote_id, occurred_at);

CREATE TABLE workshop.quote_decision (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL,
    quote_revision_id UUID NOT NULL,
    quote_item_revision_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    decision_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_quote_decision_submission_scope
        FOREIGN KEY (submission_id, quote_revision_id, quote_id)
        REFERENCES workshop.quote_decision_submission(id, quote_revision_id, quote_id),
    -- Garante que o item realmente fazia parte da revisão daquela submissão.
    CONSTRAINT fk_quote_decision_presented_item
        FOREIGN KEY (quote_revision_id, quote_item_revision_id, quote_id)
        REFERENCES workshop.quote_revision_item(quote_revision_id, quote_item_revision_id, quote_id),
    -- Uma versão comercial possui no máximo uma decisão efetiva; ausência significa PENDENTE.
    CONSTRAINT uq_quote_decision_item_revision UNIQUE (quote_item_revision_id),
    CONSTRAINT ck_quote_decision_type CHECK (decision_type IN ('APPROVE', 'REJECT'))
);

CREATE INDEX idx_quote_decision_submission ON workshop.quote_decision(submission_id);
CREATE INDEX idx_quote_decision_quote ON workshop.quote_decision(quote_id, occurred_at);
