-- VERIFICAÇÃO SOMENTE LEITURA do Supabase de produção ANTES de promover V22–V25 (Compras).
-- Cole no SQL Editor do Supabase (ou rode com psql) e leia o resultado de cada bloco. Só há SELECT/SHOW: nada é gravado.
-- A transação é declarada READ ONLY de propósito: se algum comando tentasse escrever, o banco recusaria.
--
--   psql "<conexão Session pooler>" -v ON_ERROR_STOP=1 -f scripts/ops/supabase-readonly-check.sql
BEGIN READ ONLY;

-- 1. ESPERADO: última versão = 21, success = true, e NENHUMA linha com version >= 22 ou success = false.
SELECT installed_rank, version, description, success, installed_on
FROM erp_meta.flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 6;

SELECT count(*) FILTER (WHERE NOT success) AS migrations_com_falha,
       count(*) FILTER (WHERE version ~ '^[0-9]+$' AND version::int >= 22) AS migrations_ja_aplicadas_22_ou_mais
FROM erp_meta.flyway_schema_history;   -- ESPERADO: 0 e 0

-- 2. ESPERADO: o schema purchasing NÃO existe ainda (a V22 o cria). Se existir, pare e investigue.
SELECT schema_name FROM information_schema.schemata
WHERE schema_name NOT LIKE 'pg\_%' AND schema_name <> 'information_schema'
ORDER BY schema_name;
-- Schemas do ERP esperados: crm, erp_meta, finance, iam, inventory, productcatalog, servicecatalog, workorder, workshop
-- (mais public e os do próprio Supabase). 'purchasing' NÃO deve aparecer.

-- 3. ESPERADO: 0 linhas. Nenhuma permissão de Compras nem id reservado pelas migrations V22–V25 já ocupado.
SELECT id, code FROM iam.permission
WHERE code LIKE 'PURCHASE\_%'
   OR id BETWEEN '10000000-0000-0000-0000-000000000031' AND '10000000-0000-0000-0000-000000000039';

-- 4. ESPERADO: 0. A V24 amplia a restrição de origem do estoque para aceitar PURCHASE; nenhuma linha atual pode estar fora de
-- MANUAL/WORK_ORDER (senão a restrição anterior já estaria violada).
SELECT count(*) AS movimentos_com_origem_inesperada
FROM inventory.stock_movement WHERE source_type NOT IN ('MANUAL', 'WORK_ORDER');

-- 5. ESPERADO: 0 e 0. As colunas novas (gtin, ncm) e a purchase_receipt_item_id ainda não existem.
SELECT count(*) AS colunas_novas_ja_existentes
FROM information_schema.columns
WHERE (table_schema = 'productcatalog' AND table_name = 'product' AND column_name IN ('gtin', 'ncm'))
   OR (table_schema = 'inventory' AND table_name = 'stock_movement' AND column_name = 'purchase_receipt_item_id');

-- 6. O usuário da aplicação precisa poder criar schema (a V22 faz CREATE SCHEMA purchasing). ESPERADO: true.
SELECT has_database_privilege(current_user, current_database(), 'CREATE') AS pode_criar_schema,
       current_user AS usuario;

-- 7. Volume: as migrations só ALTERAM duas tabelas (product e stock_movement); com poucos milhares de linhas levam milissegundos.
SELECT (SELECT count(*) FROM productcatalog.product)  AS produtos,
       (SELECT count(*) FROM inventory.stock_movement) AS movimentos_estoque,
       (SELECT count(*) FROM finance.payable)          AS contas_a_pagar,
       (SELECT count(*) FROM workorder.work_order)     AS ordens_de_servico,
       pg_size_pretty(pg_database_size(current_database())) AS tamanho_do_banco;

-- 8. Conexões ativas (o Render roda a versão antiga e a nova ao mesmo tempo durante o deploy: 2 x 5 conexões).
SELECT count(*) AS conexoes_ativas, current_setting('max_connections') AS limite
FROM pg_stat_activity WHERE datname = current_database();

SHOW server_version;   -- o ERP é testado em PostgreSQL 18; o Supabase pode estar em outra versão (as migrations usam SQL padrão)

-- 9. Os módulos que NÃO são do ERP em public não são tocados. Lista só para registro.
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;

ROLLBACK;
