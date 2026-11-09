-- ROLLBACK MANUAL DAS MIGRATIONS V22–V25 (Compras e Fornecedores). NÃO é uma migration do Flyway.
--
-- Quando usar: SÓ se a promoção deu errado e Compras AINDA NÃO foi usada de verdade. Este script devolve o banco ao
-- formato da V21. Se já houve uso real, NÃO use: restaure o backup ou corrija para frente (ver o runbook).
--
-- Trava de segurança: o script se recusa a rodar (e desfaz tudo) se existir
--   * entrada de estoque originada de compra (apagar a compra deixaria saldo e custo médio sem origem), ou
--   * conta a pagar criada por compra (a conta é do Financeiro e ficaria sem origem).
-- Nesses casos a única volta segura é restaurar o backup tirado ANTES da promoção.
--
-- O histórico do Flyway do Supabase está em erp_meta (FLYWAY_DEFAULT_SCHEMA). Em outro ambiente, troque erp_meta pelo schema
-- do histórico (no compose é public). Rode em UMA transação, conectado como o mesmo usuário da aplicação:
--   psql "<conexão>" -v ON_ERROR_STOP=1 -f scripts/ops/rollback-v22-v25.sql
--
-- Depois de rodar, volte o aplicativo para a versão anterior (release/supabase-cloud no commit antigo). A versão antiga só
-- conhece V1–V21 e sobe normalmente.
BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.stock_movement WHERE source_type = 'PURCHASE') THEN
        RAISE EXCEPTION 'Rollback recusado: há entradas de estoque originadas de compra. Restaure o backup anterior à promoção.';
    END IF;
    IF to_regclass('purchasing.purchase_payable') IS NOT NULL
       AND EXISTS (SELECT 1 FROM purchasing.purchase_payable) THEN
        RAISE EXCEPTION 'Rollback recusado: há contas a pagar criadas por compra. Restaure o backup anterior à promoção.';
    END IF;
END $$;

-- V22–V25: todo o schema de Compras (tabelas, view, triggers, funções).
DROP SCHEMA IF EXISTS purchasing CASCADE;

-- V24: origem PURCHASE no estoque. Remover a coluna leva junto os dois índices e a restrição ck_stock_movement_purchase.
ALTER TABLE inventory.stock_movement DROP COLUMN IF EXISTS purchase_receipt_item_id;
ALTER TABLE inventory.stock_movement DROP CONSTRAINT IF EXISTS ck_stock_movement_source;
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT ck_stock_movement_source CHECK (source_type IN ('MANUAL', 'WORK_ORDER'));

-- V23: identidade fiscal do produto. Remover as colunas leva junto o índice e as restrições de formato.
ALTER TABLE productcatalog.product DROP COLUMN IF EXISTS gtin;
ALTER TABLE productcatalog.product DROP COLUMN IF EXISTS ncm;

-- V22–V25: permissões de Compras e as concessões e exceções que apontam para elas.
DELETE FROM iam.user_permission_exception WHERE permission_id IN (SELECT id FROM iam.permission WHERE code LIKE 'PURCHASE\_%');
DELETE FROM iam.profile_permission WHERE permission_id IN (SELECT id FROM iam.permission WHERE code LIKE 'PURCHASE\_%');
DELETE FROM iam.permission WHERE code LIKE 'PURCHASE\_%';

-- O Flyway volta a considerar V22–V25 pendentes.
DELETE FROM erp_meta.flyway_schema_history WHERE version IN ('22', '23', '24', '25');

COMMIT;
