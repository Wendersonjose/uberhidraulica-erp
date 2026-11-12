-- ROLLBACK MANUAL DAS MIGRATIONS DE COMPRAS (V22 em diante). NÃO é uma migration do Flyway.
--
-- O nome do arquivo guarda "v22-v25" por compatibilidade com os runbooks; o script cobre também as migrations
-- posteriores que vivem só no schema purchasing (V26: vínculo pedido × NF-e).
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

-- V22 em diante: todo o schema de Compras (tabelas, view, triggers, funções), inclusive o vínculo pedido × NF-e da V26.
DROP SCHEMA IF EXISTS purchasing CASCADE;

-- V24: origem PURCHASE no estoque. Remover a coluna leva junto os dois índices e a restrição ck_stock_movement_purchase.
ALTER TABLE inventory.stock_movement DROP COLUMN IF EXISTS purchase_receipt_item_id;
ALTER TABLE inventory.stock_movement DROP CONSTRAINT IF EXISTS ck_stock_movement_source;
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT ck_stock_movement_source CHECK (source_type IN ('MANUAL', 'WORK_ORDER'));

-- V23: identidade fiscal do produto. Remover as colunas leva junto o índice e as restrições de formato.
ALTER TABLE productcatalog.product DROP COLUMN IF EXISTS gtin;
ALTER TABLE productcatalog.product DROP COLUMN IF EXISTS ncm;

-- V27 (DR-0019): baixa por item e por ciclo, decisão do cliente e remoção no item físico da OS. Se algum item já foi
-- baixado mais de uma vez (rejeitado, reaprovado e finalizado), o índice antigo não pode ser recriado e o rollback é
-- recusado: nesse ponto a única volta segura é restaurar o backup.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'inventory' AND table_name = 'stock_movement' AND column_name = 'work_order_cycle')
       AND EXISTS (SELECT 1 FROM inventory.stock_movement WHERE work_order_cycle > 1) THEN
        RAISE EXCEPTION 'Rollback recusado: há item de OS baixado em mais de um ciclo (DR-0019). Restaure o backup anterior à promoção.';
    END IF;
END $$;
DROP INDEX IF EXISTS inventory.uq_stock_movement_work_order_item;
ALTER TABLE inventory.stock_movement DROP COLUMN IF EXISTS work_order_cycle;
CREATE UNIQUE INDEX uq_stock_movement_work_order_item
    ON inventory.stock_movement (work_order_item_id) WHERE movement_type = 'WORK_ORDER_OUT';
ALTER TABLE workorder.work_order_product DROP COLUMN IF EXISTS customer_decision;
ALTER TABLE workorder.work_order_product DROP COLUMN IF EXISTS decided_at;
ALTER TABLE workorder.work_order_product DROP COLUMN IF EXISTS removed_at;
ALTER TABLE workorder.work_order_product DROP COLUMN IF EXISTS removed_by;
ALTER TABLE workorder.work_order_product DROP COLUMN IF EXISTS removal_reason;

-- V22–V25: permissões de Compras e as concessões e exceções que apontam para elas.
DELETE FROM iam.user_permission_exception WHERE permission_id IN (SELECT id FROM iam.permission WHERE code LIKE 'PURCHASE\_%');
DELETE FROM iam.profile_permission WHERE permission_id IN (SELECT id FROM iam.permission WHERE code LIKE 'PURCHASE\_%');
DELETE FROM iam.permission WHERE code LIKE 'PURCHASE\_%';

-- O Flyway volta a considerar V22 em diante pendentes.
DELETE FROM erp_meta.flyway_schema_history WHERE version IS NOT NULL AND version::int >= 22;

COMMIT;
