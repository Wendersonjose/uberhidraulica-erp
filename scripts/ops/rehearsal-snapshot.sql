-- Usado SOMENTE por scripts/ops/rehearse-promotion.sh, no banco DESCARTÁVEL do ensaio (nunca na produção: cria um schema).
-- Tira uma foto linha a linha de todas as tabelas do ERP antes das migrations novas, e cria a função que, depois delas,
-- diz por tabela quantas linhas pré-existentes foram alteradas/apagadas e quantas linhas novas surgiram.
-- A comparação usa só as colunas que existiam antes: coluna nova (nula) acrescentada por migration não conta como alteração.
CREATE SCHEMA rehearsal_snapshot;
CREATE TABLE rehearsal_snapshot.map (src_schema text, src_table text, snap text, cols text);

DO $$
DECLARE
    r record;
    n int := 0;
BEGIN
    FOR r IN
        SELECT t.table_schema, t.table_name
        FROM information_schema.tables t
        WHERE t.table_type = 'BASE TABLE'
          AND t.table_schema IN ('iam', 'crm', 'servicecatalog', 'productcatalog', 'inventory', 'workorder', 'workshop',
                                 'finance', 'purchasing')
          AND t.table_name NOT LIKE 'spring\_session%'
        ORDER BY 1, 2
    LOOP
        n := n + 1;
        EXECUTE format('CREATE TABLE rehearsal_snapshot.%I AS SELECT * FROM %I.%I', 't' || n, r.table_schema, r.table_name);
        INSERT INTO rehearsal_snapshot.map
        SELECT r.table_schema, r.table_name, 't' || n,
               string_agg(quote_ident(c.column_name), ',' ORDER BY c.ordinal_position)
        FROM information_schema.columns c
        WHERE c.table_schema = r.table_schema AND c.table_name = r.table_name;
    END LOOP;
END $$;

CREATE FUNCTION rehearsal_snapshot.diff()
    RETURNS TABLE (tabela text, antes bigint, alteradas_ou_apagadas bigint, novas bigint)
    LANGUAGE plpgsql AS $$
DECLARE
    m record;
BEGIN
    FOR m IN SELECT * FROM rehearsal_snapshot.map ORDER BY 1, 2 LOOP
        tabela := m.src_schema || '.' || m.src_table;
        EXECUTE format('SELECT count(*) FROM rehearsal_snapshot.%I', m.snap) INTO antes;
        EXECUTE format('SELECT count(*) FROM (SELECT s::text FROM rehearsal_snapshot.%I s EXCEPT ALL '
                       'SELECT row(%s)::text FROM %I.%I) d', m.snap, m.cols, m.src_schema, m.src_table)
            INTO alteradas_ou_apagadas;
        EXECUTE format('SELECT count(*) FROM (SELECT row(%s)::text FROM %I.%I EXCEPT ALL '
                       'SELECT s::text FROM rehearsal_snapshot.%I s) d', m.cols, m.src_schema, m.src_table, m.snap)
            INTO novas;
        RETURN NEXT;
    END LOOP;
END $$;

SELECT count(*) FROM rehearsal_snapshot.map;
