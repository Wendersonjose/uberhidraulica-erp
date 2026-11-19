-- TASK-0024 etapa B / DR-0024 Q1 e Q2: parcelas do recebível e recebível avulso (sem OS).
--
-- Regras de modelagem (mantidas da V18):
--   * lançamento é imutável; saldo e situação são derivados, nunca gravados;
--   * o plano de parcelas é versionado: um plano novo não apaga o anterior, que fica como histórico;
--   * recebível sem plano explícito tem uma parcela implícita (valor original, vencimento do recebível), o que
--     preserva sem migração de dados todo recebível já existente.

-- ---------------------------------------------------------------- categoria de receita (Q2)

CREATE TABLE finance.revenue_category (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_revenue_category_name CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_revenue_category_name ON finance.revenue_category (lower(btrim(name)));

-- ---------------------------------------------------------------- recebível avulso (Q2)

-- O padrão WORK_ORDER fica: a versão anterior do aplicativo (e todo INSERT antigo) continua gravando recebível da OS.
ALTER TABLE finance.receivable ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'WORK_ORDER';
ALTER TABLE finance.receivable ADD COLUMN description VARCHAR(200);
ALTER TABLE finance.receivable ADD COLUMN revenue_category_id UUID REFERENCES finance.revenue_category(id);
ALTER TABLE finance.receivable ADD COLUMN notes VARCHAR(500);
ALTER TABLE finance.receivable ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE finance.receivable ALTER COLUMN work_order_id DROP NOT NULL;
ALTER TABLE finance.receivable ALTER COLUMN work_order_number DROP NOT NULL;
ALTER TABLE finance.receivable ALTER COLUMN billing_quote_id DROP NOT NULL;
ALTER TABLE finance.receivable
    ADD CONSTRAINT uq_receivable_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT ck_receivable_origin CHECK (origin IN ('WORK_ORDER', 'MANUAL')),
    -- Recebível da OS continua exatamente como na V18; o avulso não tem OS, orçamento nem linha aprovada.
    ADD CONSTRAINT ck_receivable_origin_fields CHECK (
        (origin = 'WORK_ORDER' AND work_order_id IS NOT NULL AND work_order_number IS NOT NULL AND billing_quote_id IS NOT NULL
            AND description IS NULL AND revenue_category_id IS NULL)
        OR (origin = 'MANUAL' AND work_order_id IS NULL AND work_order_number IS NULL AND billing_quote_id IS NULL
            AND description IS NOT NULL AND btrim(description) <> '' AND revenue_category_id IS NOT NULL
            AND idempotency_key IS NOT NULL)),
    ADD CONSTRAINT ck_receivable_notes CHECK (notes IS NULL OR btrim(notes) <> '');
CREATE INDEX ix_receivable_origin_issued ON finance.receivable (origin, issued_on);

-- Origem do ajuste: manual ou juros e multa por atraso confirmados pelo operador (Q3, V29).
ALTER TABLE finance.receivable_adjustment ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE finance.receivable_adjustment
    ADD CONSTRAINT ck_receivable_adjustment_origin CHECK (origin IN ('MANUAL', 'LATE_CHARGE')),
    ADD CONSTRAINT ck_receivable_adjustment_late_charge CHECK (origin <> 'LATE_CHARGE' OR adjustment_type = 'SURCHARGE');

-- ---------------------------------------------------------------- plano de parcelas (Q1)

CREATE TABLE finance.receivable_installment_plan (
    receivable_id UUID NOT NULL REFERENCES finance.receivable(id),
    version INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (receivable_id, version),
    CONSTRAINT uq_receivable_installment_plan_key UNIQUE (idempotency_key),
    CONSTRAINT ck_receivable_installment_plan_version CHECK (version >= 1),
    CONSTRAINT ck_receivable_installment_plan_reason CHECK (btrim(reason) <> '')
);

CREATE TABLE finance.receivable_installment (
    id UUID PRIMARY KEY,
    receivable_id UUID NOT NULL,
    plan_version INTEGER NOT NULL,
    number INTEGER NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    -- Único campo alterável, sempre com histórico em receivable_due_date_change.
    due_date DATE NOT NULL,
    CONSTRAINT fk_receivable_installment_plan
        FOREIGN KEY (receivable_id, plan_version) REFERENCES finance.receivable_installment_plan(receivable_id, version),
    CONSTRAINT uq_receivable_installment_number UNIQUE (receivable_id, plan_version, number),
    CONSTRAINT ck_receivable_installment_number CHECK (number >= 1),
    CONSTRAINT ck_receivable_installment_amount CHECK (amount > 0)
);

-- A soma das parcelas de cada plano é o valor original. Conferida no fim da transação, quando o plano inteiro
-- já foi gravado; o banco recusa plano incompleto mesmo que a aplicação erre.
CREATE FUNCTION finance.check_installment_plan_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    plan_total NUMERIC(19,2);
    original NUMERIC(19,2);
    numbers INTEGER;
    highest INTEGER;
BEGIN
    SELECT coalesce(sum(amount), 0), count(*), coalesce(max(number), 0) INTO plan_total, numbers, highest
    FROM finance.receivable_installment WHERE receivable_id = NEW.receivable_id AND plan_version = NEW.plan_version;
    SELECT original_amount INTO original FROM finance.receivable WHERE id = NEW.receivable_id;
    IF plan_total <> original THEN
        RAISE EXCEPTION 'installment_plan_total_mismatch' USING ERRCODE = 'check_violation';
    END IF;
    IF numbers <> highest THEN
        RAISE EXCEPTION 'installment_plan_numbering_gap' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER tg_receivable_installment_plan_total
    AFTER INSERT ON finance.receivable_installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION finance.check_installment_plan_total();

-- Plano gravado sem nenhuma parcela também é recusado no fim da transação.
CREATE FUNCTION finance.check_installment_plan_not_empty() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM finance.receivable_installment
                   WHERE receivable_id = NEW.receivable_id AND plan_version = NEW.version) THEN
        RAISE EXCEPTION 'installment_plan_empty' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER tg_receivable_installment_plan_not_empty
    AFTER INSERT ON finance.receivable_installment_plan
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION finance.check_installment_plan_not_empty();

-- Valor, número e plano da parcela nunca mudam; só o vencimento, pelo caso de uso com histórico.
CREATE FUNCTION finance.forbid_installment_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.amount IS DISTINCT FROM OLD.amount OR NEW.number IS DISTINCT FROM OLD.number
        OR NEW.plan_version IS DISTINCT FROM OLD.plan_version OR NEW.receivable_id IS DISTINCT FROM OLD.receivable_id THEN
        RAISE EXCEPTION 'installment_immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_receivable_installment_immutable
    BEFORE UPDATE ON finance.receivable_installment
    FOR EACH ROW EXECUTE FUNCTION finance.forbid_installment_mutation();

-- Histórico de vencimento por parcela. Nulo nas linhas anteriores: era o vencimento único do recebível.
ALTER TABLE finance.receivable_due_date_change ADD COLUMN installment_number INTEGER;
ALTER TABLE finance.receivable_due_date_change
    ADD CONSTRAINT ck_receivable_due_date_change_installment CHECK (installment_number IS NULL OR installment_number >= 1);

-- ---------------------------------------------------------------- permissão (Q2)

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000040', 'FINANCE_RECEIVABLE_MANUAL', 'Lançar e cancelar conta a receber avulsa (sem OS)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO') AND permission.code = 'FINANCE_RECEIVABLE_MANUAL'
ON CONFLICT DO NOTHING;
