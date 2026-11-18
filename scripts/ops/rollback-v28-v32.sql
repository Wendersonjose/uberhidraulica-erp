-- ROLLBACK MANUAL DAS MIGRATIONS DA ETAPA B DO FINANCEIRO (V28 a V32, TASK-0024 / DR-0024). NÃO é uma migration do Flyway.
--
-- Quando usar: SÓ se a promoção deu errado e os recursos novos AINDA NÃO foram usados. Devolve o banco ao formato da V27.
-- Se já houve uso real, NÃO use: restaure o backup ou corrija para frente.
--
-- Trava de segurança: o script se recusa a rodar (e desfaz tudo) se existir qualquer dado que só a etapa B cria:
--   recebível avulso, plano de parcelas, juros e multa decididos, ajuste de origem LATE_CHARGE, categoria de receita,
--   tabela de taxas, recebimento com taxa, grupo de contas a pagar, conta com fornecedor vinculado, aprovação, conta que
--   exige aprovação, conta financeira, conciliação, exceção de usuário nas permissões novas, ou configuração diferente
--   do padrão.
--
-- Ordem: o mais novo primeiro. Para voltar até a V21, rode este script e depois scripts/ops/rollback-v22-v25.sql.
-- O histórico do Flyway do Supabase está em erp_meta. Em outro ambiente, troque erp_meta pelo schema do histórico.
--   psql "<conexão>" -v ON_ERROR_STOP=1 -f scripts/ops/rollback-v28-v32.sql
BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM finance.receivable WHERE origin <> 'WORK_ORDER')
       OR EXISTS (SELECT 1 FROM finance.receivable_installment_plan)
       OR EXISTS (SELECT 1 FROM finance.receivable_late_charge)
       OR EXISTS (SELECT 1 FROM finance.receivable_adjustment WHERE origin <> 'MANUAL')
       OR EXISTS (SELECT 1 FROM finance.receivable_due_date_change WHERE installment_number IS NOT NULL)
       OR EXISTS (SELECT 1 FROM finance.revenue_category)
       OR EXISTS (SELECT 1 FROM finance.payment_method_fee_schedule)
       OR EXISTS (SELECT 1 FROM finance.receipt WHERE card_installments IS NOT NULL OR fee_amount <> 0)
       OR EXISTS (SELECT 1 FROM finance.payable_group)
       OR EXISTS (SELECT 1 FROM finance.payable WHERE supplier_id IS NOT NULL OR approval_required)
       OR EXISTS (SELECT 1 FROM finance.payable_approval)
       OR EXISTS (SELECT 1 FROM finance.financial_account)
       OR EXISTS (SELECT 1 FROM finance.reconciliation)
       OR EXISTS (SELECT 1 FROM iam.user_permission_exception e JOIN iam.permission p ON p.id = e.permission_id
                  WHERE p.code IN ('FINANCE_RECEIVABLE_MANUAL', 'FINANCE_PAYABLE_APPROVE', 'FINANCE_PAYABLE_APPROVE_OWN', 'FINANCE_RECONCILE'))
       OR EXISTS (SELECT 1 FROM finance.settings WHERE (key = 'LATE_FEE_PERCENT' AND value <> '2.00')
                  OR (key = 'LATE_INTEREST_MONTHLY_PERCENT' AND value <> '1.00')
                  OR (key = 'PAYABLE_APPROVAL_THRESHOLD' AND value <> '0.00')) THEN
        RAISE EXCEPTION 'Rollback recusado: a etapa B do Financeiro já foi usada. Restaure o backup anterior à promoção.';
    END IF;
END $$;

-- V32: conciliação e contas financeiras.
DROP TRIGGER IF EXISTS tg_receipt_reversal_not_reconciled ON finance.receipt_reversal;
DROP TRIGGER IF EXISTS tg_payable_payment_reversal_not_reconciled ON finance.payable_payment_reversal;
DROP TABLE IF EXISTS finance.reconciliation;
DROP FUNCTION IF EXISTS finance.reconciliation_write_once();
DROP FUNCTION IF EXISTS finance.forbid_reconciled_receipt_reversal();
DROP FUNCTION IF EXISTS finance.forbid_reconciled_payment_reversal();
DROP TABLE IF EXISTS finance.financial_account;

-- V31: grupos, aprovação e fornecedor nas contas a pagar.
DROP TRIGGER IF EXISTS tg_payable_payment_requires_approval ON finance.payable_payment;
DROP FUNCTION IF EXISTS finance.require_payable_approval();
DROP TABLE IF EXISTS finance.payable_approval;
ALTER TABLE finance.payable DROP COLUMN IF EXISTS group_id, DROP COLUMN IF EXISTS installment_number,
    DROP COLUMN IF EXISTS supplier_id, DROP COLUMN IF EXISTS origin, DROP COLUMN IF EXISTS approval_required;
DROP TABLE IF EXISTS finance.payable_group;

-- V30: taxas de cartão.
ALTER TABLE finance.receipt DROP CONSTRAINT IF EXISTS ck_receipt_card_fee;
ALTER TABLE finance.receipt DROP COLUMN IF EXISTS card_installments, DROP COLUMN IF EXISTS fee_percent,
    DROP COLUMN IF EXISTS fee_amount, DROP COLUMN IF EXISTS expected_settlement_on;
DROP TABLE IF EXISTS finance.payment_method_fee;
DROP TABLE IF EXISTS finance.payment_method_fee_schedule;

-- V29: juros e multa; configurações novas.
DROP TABLE IF EXISTS finance.receivable_late_charge_line;
DROP TABLE IF EXISTS finance.receivable_late_charge;
DELETE FROM finance.settings WHERE key IN ('LATE_FEE_PERCENT', 'LATE_INTEREST_MONTHLY_PERCENT', 'PAYABLE_APPROVAL_THRESHOLD');
ALTER TABLE finance.settings DROP CONSTRAINT IF EXISTS ck_finance_settings_key,
    DROP CONSTRAINT IF EXISTS ck_finance_settings_late_fee, DROP CONSTRAINT IF EXISTS ck_finance_settings_late_interest,
    DROP CONSTRAINT IF EXISTS ck_finance_settings_approval_threshold;
ALTER TABLE finance.settings ADD CONSTRAINT ck_finance_settings_key CHECK (key IN ('DEFAULT_RECEIVABLE_DUE_DAYS'));

-- V28: parcelas, recebível avulso e origem do ajuste.
DROP TABLE IF EXISTS finance.receivable_installment;
DROP TABLE IF EXISTS finance.receivable_installment_plan;
DROP FUNCTION IF EXISTS finance.check_installment_plan_total();
DROP FUNCTION IF EXISTS finance.check_installment_plan_not_empty();
DROP FUNCTION IF EXISTS finance.forbid_installment_mutation();
ALTER TABLE finance.receivable_due_date_change DROP COLUMN IF EXISTS installment_number;
ALTER TABLE finance.receivable_adjustment DROP COLUMN IF EXISTS origin;
DROP INDEX IF EXISTS finance.ix_receivable_origin_issued;
ALTER TABLE finance.receivable DROP COLUMN IF EXISTS origin, DROP COLUMN IF EXISTS description,
    DROP COLUMN IF EXISTS revenue_category_id, DROP COLUMN IF EXISTS notes, DROP COLUMN IF EXISTS idempotency_key;
ALTER TABLE finance.receivable ALTER COLUMN work_order_id SET NOT NULL, ALTER COLUMN work_order_number SET NOT NULL,
    ALTER COLUMN billing_quote_id SET NOT NULL;
DROP TABLE IF EXISTS finance.revenue_category;

-- Permissões V28, V31 e V32: concessões do seed primeiro, depois o catálogo.
DELETE FROM iam.profile_permission WHERE permission_id IN (SELECT id FROM iam.permission WHERE code IN
    ('FINANCE_RECEIVABLE_MANUAL', 'FINANCE_PAYABLE_APPROVE', 'FINANCE_PAYABLE_APPROVE_OWN', 'FINANCE_RECONCILE'));
DELETE FROM iam.permission WHERE code IN ('FINANCE_RECEIVABLE_MANUAL', 'FINANCE_PAYABLE_APPROVE', 'FINANCE_PAYABLE_APPROVE_OWN',
    'FINANCE_RECONCILE');

DELETE FROM erp_meta.flyway_schema_history WHERE version IS NOT NULL AND version::int BETWEEN 28 AND 32;

COMMIT;
