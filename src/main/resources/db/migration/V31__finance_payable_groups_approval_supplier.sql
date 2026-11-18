-- TASK-0024 etapa B / DR-0024 Q5 e Q6: parcelas consolidadas, aprovação segregada e fornecedor nas contas a pagar.

-- ---------------------------------------------------------------- grupo de parcelas (Q5)

CREATE TABLE finance.payable_group (
    id UUID PRIMARY KEY,
    description VARCHAR(180) NOT NULL,
    supplier VARCHAR(200),
    -- Sem FK para purchasing.supplier: a fronteira entre módulos é por id (Spring Modulith). A existência é
    -- conferida pelo contrato FinanceSupplierDirectory, implementado por Compras, na mesma transação.
    supplier_id UUID,
    category_id UUID NOT NULL REFERENCES finance.expense_category(id),
    total_amount NUMERIC(19,2) NOT NULL,
    installment_count INTEGER NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_payable_group_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payable_group_description CHECK (btrim(description) <> ''),
    CONSTRAINT ck_payable_group_amount CHECK (total_amount > 0 AND installment_count BETWEEN 2 AND 60)
);

ALTER TABLE finance.payable ADD COLUMN group_id UUID REFERENCES finance.payable_group(id);
ALTER TABLE finance.payable ADD COLUMN installment_number INTEGER;
ALTER TABLE finance.payable ADD COLUMN supplier_id UUID;
-- Padrões mantidos: a versão anterior do aplicativo continua lançando conta manual, sem aprovação.
ALTER TABLE finance.payable ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE finance.payable ADD COLUMN approval_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE finance.payable
    ADD CONSTRAINT ck_payable_origin CHECK (origin IN ('MANUAL', 'PURCHASE')),
    ADD CONSTRAINT ck_payable_group_installment CHECK ((group_id IS NULL) = (installment_number IS NULL)
        AND (installment_number IS NULL OR installment_number >= 1)),
    ADD CONSTRAINT uq_payable_group_installment UNIQUE (group_id, installment_number);
CREATE INDEX ix_payable_supplier ON finance.payable (supplier_id) WHERE supplier_id IS NOT NULL;

-- As contas já registradas por Compras (chave "PURCHASE:<compra>:<parcela>", V23) passaram pela compra.
UPDATE finance.payable SET origin = 'PURCHASE' WHERE idempotency_key LIKE 'PURCHASE:%';

-- ---------------------------------------------------------------- aprovação segregada (Q5)

CREATE TABLE finance.payable_approval (
    id UUID PRIMARY KEY,
    payable_id UUID NOT NULL REFERENCES finance.payable(id),
    approved_at TIMESTAMPTZ NOT NULL,
    approved_by UUID NOT NULL,
    notes VARCHAR(500),
    CONSTRAINT uq_payable_approval_payable UNIQUE (payable_id),
    CONSTRAINT ck_payable_approval_notes CHECK (notes IS NULL OR btrim(notes) <> '')
);

-- O banco também recusa pagar conta pendente de aprovação, mesmo que a aplicação erre.
CREATE FUNCTION finance.require_payable_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM finance.payable p WHERE p.id = NEW.payable_id AND p.approval_required
               AND NOT EXISTS (SELECT 1 FROM finance.payable_approval a WHERE a.payable_id = p.id)) THEN
        RAISE EXCEPTION 'payable_approval_required' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_payable_payment_requires_approval
    BEFORE INSERT ON finance.payable_payment
    FOR EACH ROW EXECUTE FUNCTION finance.require_payable_approval();

-- ---------------------------------------------------------------- permissões (Q5), no padrão da V25

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000041', 'FINANCE_PAYABLE_APPROVE', 'Aprovar conta a pagar acima do limite configurado'),
    ('10000000-0000-0000-0000-000000000042', 'FINANCE_PAYABLE_APPROVE_OWN',
     'Aprovar conta a pagar que o próprio usuário lançou (exceção da segregação de funções)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO') AND permission.code = 'FINANCE_PAYABLE_APPROVE'
ON CONFLICT DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code = 'DONO' AND permission.code = 'FINANCE_PAYABLE_APPROVE_OWN'
ON CONFLICT DO NOTHING;
