-- Revisão externa da TASK-0015 (CHANGES_REQUIRED) e DR-0017. V17 e V18 não são alteradas.

-- ---------------------------------------------------------------- F3: permissão de faturamento

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000015', 'FINANCE_BILL', 'Gerar faturamento/recebível na finalização da Ordem de Serviço')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO', 'GERENTE_ADMINISTRATIVO') AND permission.code = 'FINANCE_BILL'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- F2: natureza da forma de pagamento

-- A classificação "movimenta dinheiro físico" é decidida na criação e nunca muda: renomear, inativar
-- ou reativar não alteram a natureza. A aplicação não atualiza a coluna; o banco recusa quem tentar.
CREATE FUNCTION finance.forbid_cash_session_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.cash_session_required IS DISTINCT FROM OLD.cash_session_required THEN
        RAISE EXCEPTION 'cash_session_required_immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_payment_method_cash_session_immutable
    BEFORE UPDATE OF cash_session_required ON finance.payment_method
    FOR EACH ROW EXECUTE FUNCTION finance.forbid_cash_session_change();

-- ---------------------------------------------------------------- DR-0017: estorno de ajuste

-- Estorno total, próprio e único por ajuste. O ajuste original permanece intacto.
CREATE TABLE finance.receivable_adjustment_reversal (
    id UUID PRIMARY KEY,
    adjustment_id UUID NOT NULL REFERENCES finance.receivable_adjustment(id),
    reason VARCHAR(500) NOT NULL,
    reversed_at TIMESTAMPTZ NOT NULL,
    reversed_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_receivable_adjustment_reversal_adjustment UNIQUE (adjustment_id),
    CONSTRAINT uq_receivable_adjustment_reversal_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_receivable_adjustment_reversal_reason CHECK (btrim(reason) <> '')
);

-- ---------------------------------------------------------------- hardening: idempotência do vencimento

-- Linhas eventualmente gravadas antes desta versão recebem uma chave técnica derivada do próprio id,
-- para que a coluna seja obrigatória e única sem apagar nem reescrever o conteúdo do histórico.
ALTER TABLE finance.receivable_due_date_change ADD COLUMN idempotency_key VARCHAR(100);
UPDATE finance.receivable_due_date_change SET idempotency_key = 'legacy-' || id::text WHERE idempotency_key IS NULL;
ALTER TABLE finance.receivable_due_date_change
    ALTER COLUMN idempotency_key SET NOT NULL,
    ADD CONSTRAINT uq_receivable_due_date_change_idempotency_key UNIQUE (idempotency_key);
