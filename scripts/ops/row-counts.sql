-- CONTAGEM EXATA DE LINHAS das tabelas do ERP. SOMENTE LEITURA (transação READ ONLY; só SELECT).
--
-- Serve para provar que um backup restaurado é a MESMA base de origem: rode este arquivo na produção e na cópia restaurada,
-- com a mesma linha de comando, e compare as duas saídas (devem ser idênticas, linha a linha).
--
--   psql "<conexão>" -qtA -F ' ' -v ON_ERROR_STOP=1 -f scripts/ops/row-counts.sql > contagem-producao.txt
--
-- `public` fica de fora de propósito: no Supabase ele guarda outro módulo (salários), que não faz parte do backup do ERP.
-- Contagem exata (count(*)), não a estimativa de pg_stat: com o app no ar, as tabelas de sessão (iam.spring_session*) e de
-- auditoria podem mudar entre o dump e a contagem; as demais precisam bater.
BEGIN READ ONLY;

SELECT t.table_schema || '.' || t.table_name AS tabela,
       (xpath('/row/c/text()',
              query_to_xml(format('SELECT count(*) AS c FROM %I.%I', t.table_schema, t.table_name), false, true, '')
       ))[1]::text::bigint AS linhas
FROM information_schema.tables t
WHERE t.table_type = 'BASE TABLE'
  AND t.table_schema IN ('erp_meta', 'iam', 'crm', 'servicecatalog', 'productcatalog', 'inventory', 'workorder',
                         'workshop', 'finance', 'purchasing')
ORDER BY 1;

ROLLBACK;
