-- TASK-0024 etapa B / DR-0024 Q7: contas financeiras e conciliação manual, sem importar OFX ou CSV.
--
-- Uma conciliação liga um recebimento ou pagamento a uma linha de extrato. Desfazer grava o estorno na própria linha
-- (uma única vez, como o cancelamento do recebível); o registro original não é apagado. Os índices parciais
-- garantem, no banco, que um lançamento e uma referência de extrato da conta tenham no máximo uma conciliação ativa.

CREATE TABLE finance.financial_account (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    account_type VARCHAR(8) NOT NULL,
    bank_name VARCHAR(80),
    branch VARCHAR(20),
    account_number VARCHAR(30),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_financial_account_type CHECK (account_type IN ('CASH', 'BANK', 'CARD')),
    CONSTRAINT ck_financial_account_name CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_financial_account_name ON finance.financial_account (lower(btrim(name)));

CREATE TABLE finance.reconciliation (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES finance.financial_account(id),
    entry_type VARCHAR(8) NOT NULL,
    receipt_id UUID REFERENCES finance.receipt(id),
    payable_payment_id UUID REFERENCES finance.payable_payment(id),
    statement_date DATE NOT NULL,
    statement_amount NUMERIC(19,2) NOT NULL,
    statement_reference VARCHAR(100) NOT NULL,
    -- Valor esperado no extrato: líquido da taxa de cartão no recebimento, valor do pagamento.
    expected_amount NUMERIC(19,2) NOT NULL,
    difference_reason VARCHAR(500),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    reversed_at TIMESTAMPTZ,
    reversed_by UUID,
    reversal_reason VARCHAR(500),
    reversal_idempotency_key VARCHAR(100),
    CONSTRAINT uq_reconciliation_key UNIQUE (idempotency_key),
    CONSTRAINT uq_reconciliation_reversal_key UNIQUE (reversal_idempotency_key),
    CONSTRAINT ck_reconciliation_entry CHECK ((entry_type = 'RECEIPT' AND receipt_id IS NOT NULL AND payable_payment_id IS NULL)
        OR (entry_type = 'PAYMENT' AND payable_payment_id IS NOT NULL AND receipt_id IS NULL)),
    CONSTRAINT ck_reconciliation_amount CHECK (statement_amount > 0 AND expected_amount >= 0),
    CONSTRAINT ck_reconciliation_reference CHECK (btrim(statement_reference) <> ''),
    CONSTRAINT ck_reconciliation_difference CHECK (statement_amount = expected_amount
        OR (difference_reason IS NOT NULL AND btrim(difference_reason) <> '')),
    CONSTRAINT ck_reconciliation_reversal CHECK ((reversed_at IS NULL AND reversed_by IS NULL AND reversal_reason IS NULL
            AND reversal_idempotency_key IS NULL)
        OR (reversed_at IS NOT NULL AND reversed_by IS NOT NULL AND btrim(reversal_reason) <> '' AND reversal_idempotency_key IS NOT NULL))
);
CREATE UNIQUE INDEX uq_reconciliation_active_receipt ON finance.reconciliation (receipt_id) WHERE reversed_at IS NULL AND receipt_id IS NOT NULL;
CREATE UNIQUE INDEX uq_reconciliation_active_payment ON finance.reconciliation (payable_payment_id)
    WHERE reversed_at IS NULL AND payable_payment_id IS NOT NULL;
CREATE UNIQUE INDEX uq_reconciliation_active_reference ON finance.reconciliation (account_id, lower(btrim(statement_reference)))
    WHERE reversed_at IS NULL;
CREATE INDEX ix_reconciliation_account ON finance.reconciliation (account_id, statement_date);

-- Só o estorno altera a linha, e uma única vez; o conteúdo conciliado nunca muda.
CREATE FUNCTION finance.reconciliation_write_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.reversed_at IS NOT NULL
        OR (NEW.account_id, NEW.entry_type, NEW.receipt_id, NEW.payable_payment_id, NEW.statement_date, NEW.statement_amount,
            NEW.statement_reference, NEW.expected_amount, NEW.difference_reason, NEW.recorded_at, NEW.recorded_by, NEW.idempotency_key)
           IS DISTINCT FROM
           (OLD.account_id, OLD.entry_type, OLD.receipt_id, OLD.payable_payment_id, OLD.statement_date, OLD.statement_amount,
            OLD.statement_reference, OLD.expected_amount, OLD.difference_reason, OLD.recorded_at, OLD.recorded_by, OLD.idempotency_key) THEN
        RAISE EXCEPTION 'reconciliation_immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_reconciliation_write_once
    BEFORE UPDATE ON finance.reconciliation
    FOR EACH ROW EXECUTE FUNCTION finance.reconciliation_write_once();

-- Lançamento conciliado não é estornado: primeiro se desfaz a conciliação (o extrato continua mostrando o valor).
-- Uma função por tabela: cada uma enxerga só as colunas da própria linha.
CREATE FUNCTION finance.forbid_reconciled_receipt_reversal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM finance.reconciliation c WHERE c.receipt_id = NEW.receipt_id AND c.reversed_at IS NULL) THEN
        RAISE EXCEPTION 'entry_reconciled' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION finance.forbid_reconciled_payment_reversal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM finance.reconciliation c WHERE c.payable_payment_id = NEW.payment_id AND c.reversed_at IS NULL) THEN
        RAISE EXCEPTION 'entry_reconciled' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_receipt_reversal_not_reconciled
    BEFORE INSERT ON finance.receipt_reversal
    FOR EACH ROW EXECUTE FUNCTION finance.forbid_reconciled_receipt_reversal();

CREATE TRIGGER tg_payable_payment_reversal_not_reconciled
    BEFORE INSERT ON finance.payable_payment_reversal
    FOR EACH ROW EXECUTE FUNCTION finance.forbid_reconciled_payment_reversal();

-- ---------------------------------------------------------------- permissão

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000043', 'FINANCE_RECONCILE', 'Conciliar e desfazer conciliação de lançamentos com o extrato')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO') AND permission.code = 'FINANCE_RECONCILE'
ON CONFLICT DO NOTHING;
