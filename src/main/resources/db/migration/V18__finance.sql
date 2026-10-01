-- TASK-0015 / DR-0015: recebível da OS, recebimentos, ajustes, contas a pagar, formas de pagamento,
-- categorias, configuração e permissões do Financeiro.
--
-- Regras de modelagem:
--   * lançamento é imutável: nenhuma tabela de lançamento sofre UPDATE ou DELETE pela aplicação;
--   * correção é estorno em tabela própria, com UNIQUE no lançamento estornado;
--   * saldos e situação são derivados das tabelas de lançamento, nunca gravados;
--   * valores cobrados em NUMERIC(19,2), conforme DR-0007;
--   * idempotency_key UNIQUE em toda operação que um retry HTTP poderia duplicar.
CREATE SCHEMA IF NOT EXISTS finance;

-- ---------------------------------------------------------------- configuração

CREATE TABLE finance.settings (
    key VARCHAR(64) PRIMARY KEY,
    value VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    CONSTRAINT ck_finance_settings_key CHECK (key IN ('DEFAULT_RECEIVABLE_DUE_DAYS')),
    CONSTRAINT ck_finance_settings_due_days CHECK (key <> 'DEFAULT_RECEIVABLE_DUE_DAYS'
        OR (value ~ '^[0-9]{1,3}$' AND value::INTEGER BETWEEN 0 AND 365))
);
INSERT INTO finance.settings (key, value, updated_at) VALUES ('DEFAULT_RECEIVABLE_DUE_DAYS', '0', now());

CREATE TABLE finance.payment_method (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL,
    -- DR-0015, F-10: dinheiro exige sessão de caixa, que ainda não existe; a forma é recusada no uso.
    cash_session_required BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_method_code UNIQUE (code),
    CONSTRAINT ck_payment_method_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_payment_method_name CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_payment_method_name ON finance.payment_method (lower(btrim(name)));

INSERT INTO finance.payment_method (id, code, name, active, cash_session_required, created_at, updated_at) VALUES
    ('20000000-0000-0000-0000-000000000001', 'DINHEIRO', 'Dinheiro', TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000002', 'PIX', 'PIX', TRUE, FALSE, now(), now()),
    ('20000000-0000-0000-0000-000000000003', 'CARTAO_DEBITO', 'Cartão de débito', TRUE, FALSE, now(), now()),
    ('20000000-0000-0000-0000-000000000004', 'CARTAO_CREDITO', 'Cartão de crédito', TRUE, FALSE, now(), now()),
    ('20000000-0000-0000-0000-000000000005', 'BOLETO', 'Boleto', TRUE, FALSE, now(), now()),
    ('20000000-0000-0000-0000-000000000006', 'TRANSFERENCIA', 'Transferência', TRUE, FALSE, now(), now()),
    ('20000000-0000-0000-0000-000000000007', 'OUTRO', 'Outro', TRUE, FALSE, now(), now());

CREATE TABLE finance.expense_category (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_expense_category_name CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_expense_category_name ON finance.expense_category (lower(btrim(name)));

-- ---------------------------------------------------------------- recebível da OS

-- Alvo da FK que torna impossível, no banco, cobrar linha de orçamento sem decisão APPROVE.
-- Redundante com uq_quote_decision_item_revision (V10), que continua garantindo uma decisão por versão.
ALTER TABLE workshop.quote_decision
    ADD CONSTRAINT uq_quote_decision_item_revision_type UNIQUE (quote_item_revision_id, decision_type);

CREATE TABLE finance.receivable (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES workorder.work_order(id),
    work_order_number BIGINT NOT NULL,
    customer_id UUID NOT NULL,
    -- Orçamento que serviu de fonte comercial; a FK composta exige que seja da mesma OS (V17).
    billing_quote_id UUID NOT NULL,
    original_amount NUMERIC(19,2) NOT NULL,
    issued_on DATE NOT NULL,
    due_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    cancelled_at TIMESTAMPTZ,
    cancelled_by UUID,
    cancellation_reason VARCHAR(500),

    -- DR-0015, F-03: exatamente um recebível principal por OS.
    CONSTRAINT uq_receivable_work_order UNIQUE (work_order_id),
    CONSTRAINT uq_receivable_id_quote UNIQUE (id, billing_quote_id),
    CONSTRAINT fk_receivable_billing_quote
        FOREIGN KEY (billing_quote_id, work_order_id) REFERENCES workshop.quote(id, work_order_id),
    CONSTRAINT ck_receivable_original_amount CHECK (original_amount >= 0),
    CONSTRAINT ck_receivable_cancellation CHECK (
        (cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND btrim(cancellation_reason) <> ''))
);
CREATE INDEX ix_receivable_due_date ON finance.receivable (due_date);
CREATE INDEX ix_receivable_customer ON finance.receivable (customer_id);

-- Snapshot das linhas aprovadas no instante da geração; decisão comercial posterior não o altera.
CREATE TABLE finance.receivable_line (
    id UUID PRIMARY KEY,
    receivable_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    quote_item_revision_id UUID NOT NULL,
    decision_type VARCHAR(20) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    discount_amount NUMERIC(19,4) NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL,
    display_order INTEGER NOT NULL,

    -- A linha pertence ao orçamento de faturamento do seu recebível.
    CONSTRAINT fk_receivable_line_receivable_quote
        FOREIGN KEY (receivable_id, quote_id) REFERENCES finance.receivable(id, billing_quote_id),
    -- A versão comercial pertence àquele orçamento.
    CONSTRAINT fk_receivable_line_item_revision
        FOREIGN KEY (quote_item_revision_id, quote_id) REFERENCES workshop.quote_item_revision(id, quote_id),
    -- E foi aprovada: linha rejeitada ou sem decisão não encontra alvo para esta FK.
    CONSTRAINT fk_receivable_line_approved_decision
        FOREIGN KEY (quote_item_revision_id, decision_type) REFERENCES workshop.quote_decision(quote_item_revision_id, decision_type),
    CONSTRAINT ck_receivable_line_decision CHECK (decision_type = 'APPROVE'),
    CONSTRAINT uq_receivable_line_item_revision UNIQUE (receivable_id, quote_item_revision_id),
    CONSTRAINT uq_receivable_line_order UNIQUE (receivable_id, display_order),
    CONSTRAINT ck_receivable_line_total CHECK (total_amount >= 0),
    CONSTRAINT ck_receivable_line_order CHECK (display_order > 0)
);

CREATE TABLE finance.receivable_adjustment (
    id UUID PRIMARY KEY,
    receivable_id UUID NOT NULL REFERENCES finance.receivable(id),
    adjustment_type VARCHAR(16) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_receivable_adjustment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_receivable_adjustment_type CHECK (adjustment_type IN ('DISCOUNT', 'SURCHARGE')),
    CONSTRAINT ck_receivable_adjustment_amount CHECK (amount > 0),
    CONSTRAINT ck_receivable_adjustment_reason CHECK (btrim(reason) <> '')
);
CREATE INDEX ix_receivable_adjustment_receivable ON finance.receivable_adjustment (receivable_id);

CREATE TABLE finance.receivable_due_date_change (
    id UUID PRIMARY KEY,
    receivable_id UUID NOT NULL REFERENCES finance.receivable(id),
    previous_due_date DATE NOT NULL,
    new_due_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    changed_by UUID NOT NULL,
    CONSTRAINT ck_receivable_due_date_change_dates CHECK (previous_due_date <> new_due_date),
    CONSTRAINT ck_receivable_due_date_change_reason CHECK (btrim(reason) <> '')
);
CREATE INDEX ix_receivable_due_date_change_receivable ON finance.receivable_due_date_change (receivable_id);

CREATE TABLE finance.receipt (
    id UUID PRIMARY KEY,
    receivable_id UUID NOT NULL REFERENCES finance.receivable(id),
    amount NUMERIC(19,2) NOT NULL,
    payment_method_id UUID NOT NULL REFERENCES finance.payment_method(id),
    -- Snapshot: renomear a forma depois não reescreve o que foi recebido.
    payment_method_name VARCHAR(80) NOT NULL,
    received_on DATE NOT NULL,
    notes VARCHAR(500),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_receipt_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_receipt_amount CHECK (amount > 0),
    CONSTRAINT ck_receipt_notes CHECK (notes IS NULL OR btrim(notes) <> '')
);
CREATE INDEX ix_receipt_receivable ON finance.receipt (receivable_id);
CREATE INDEX ix_receipt_received_on ON finance.receipt (received_on);

CREATE TABLE finance.receipt_reversal (
    id UUID PRIMARY KEY,
    receipt_id UUID NOT NULL REFERENCES finance.receipt(id),
    reason VARCHAR(500) NOT NULL,
    reversed_at TIMESTAMPTZ NOT NULL,
    reversed_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    -- DR-0015, F-07: estorno é total e único por recebimento.
    CONSTRAINT uq_receipt_reversal_receipt UNIQUE (receipt_id),
    CONSTRAINT uq_receipt_reversal_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_receipt_reversal_reason CHECK (btrim(reason) <> '')
);

-- ---------------------------------------------------------------- contas a pagar

CREATE TABLE finance.payable (
    id UUID PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    supplier VARCHAR(200),
    category_id UUID NOT NULL REFERENCES finance.expense_category(id),
    amount NUMERIC(19,2) NOT NULL,
    due_date DATE NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    cancelled_at TIMESTAMPTZ,
    cancelled_by UUID,
    cancellation_reason VARCHAR(500),
    CONSTRAINT uq_payable_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payable_description CHECK (btrim(description) <> ''),
    CONSTRAINT ck_payable_supplier CHECK (supplier IS NULL OR btrim(supplier) <> ''),
    CONSTRAINT ck_payable_amount CHECK (amount > 0),
    CONSTRAINT ck_payable_cancellation CHECK (
        (cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND btrim(cancellation_reason) <> ''))
);
CREATE INDEX ix_payable_due_date ON finance.payable (due_date);
CREATE INDEX ix_payable_category ON finance.payable (category_id);

CREATE TABLE finance.payable_payment (
    id UUID PRIMARY KEY,
    payable_id UUID NOT NULL REFERENCES finance.payable(id),
    amount NUMERIC(19,2) NOT NULL,
    payment_method_id UUID NOT NULL REFERENCES finance.payment_method(id),
    payment_method_name VARCHAR(80) NOT NULL,
    paid_on DATE NOT NULL,
    notes VARCHAR(500),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_payable_payment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payable_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payable_payment_notes CHECK (notes IS NULL OR btrim(notes) <> '')
);
CREATE INDEX ix_payable_payment_payable ON finance.payable_payment (payable_id);
CREATE INDEX ix_payable_payment_paid_on ON finance.payable_payment (paid_on);

CREATE TABLE finance.payable_payment_reversal (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES finance.payable_payment(id),
    reason VARCHAR(500) NOT NULL,
    reversed_at TIMESTAMPTZ NOT NULL,
    reversed_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_payable_payment_reversal_payment UNIQUE (payment_id),
    CONSTRAINT uq_payable_payment_reversal_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payable_payment_reversal_reason CHECK (btrim(reason) <> '')
);

-- ---------------------------------------------------------------- permissões (DR-0015, F-13)

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000009', 'FINANCE_VIEW', 'Consultar o Financeiro'),
    ('10000000-0000-0000-0000-000000000010', 'FINANCE_RECEIVE', 'Registrar recebimento'),
    ('10000000-0000-0000-0000-000000000011', 'FINANCE_REVERSE', 'Estornar recebimento ou pagamento'),
    ('10000000-0000-0000-0000-000000000012', 'FINANCE_ADJUST', 'Conceder desconto, acréscimo e alterar vencimento'),
    ('10000000-0000-0000-0000-000000000013', 'FINANCE_PAYABLE', 'Lançar, pagar e cancelar contas a pagar'),
    ('10000000-0000-0000-0000-000000000014', 'FINANCE_CONFIG', 'Configurar formas de pagamento, categorias e prazos')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO') AND permission.code LIKE 'FINANCE\_%'
ON CONFLICT DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code = 'GERENTE_ADMINISTRATIVO' AND permission.code IN ('FINANCE_VIEW', 'FINANCE_RECEIVE')
ON CONFLICT DO NOTHING;
