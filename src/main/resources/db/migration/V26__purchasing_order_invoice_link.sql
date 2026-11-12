-- TASK-0021 / I5b: ligação pedido × NF-e (DR-0023, decidida pelo proprietário em 2026-11-12: C1-A, C2-A, C3-A, C4-A).
--
-- C1-A: o vínculo é EXPLÍCITO e confirmado por humano na importação da nota. O pedido vinculado fica encerrado pela
--       nota: não recebe, não é editado e não é cancelado enquanto o vínculo estiver ativo. O estoque entra sempre
--       pela compra da nota, ao custo real de aquisição.
-- C2-A/C3-A: qualquer diferença entre o pedido e a nota (total, quantidade, preço, item a mais, item a menos ou
--       substituído) fica gravada linha a linha e exige justificativa de quem tem PURCHASE_DIVERGENCE_ACCEPT.
-- C4-A: solicitação e cotação continuam fora (pós-piloto).
--
-- O estado "encerrado pela nota" é DERIVADO do vínculo ativo, não uma coluna na compra: guardar os dois criaria duas
-- verdades sobre o mesmo fato. Cancelar a compra da nota libera o vínculo (released_at) e o pedido volta a poder ser
-- vinculado; a linha liberada permanece como histórico.
--
-- Somente aditiva; nenhuma migration anterior é alterada.

CREATE TABLE purchasing.purchase_order_invoice_link (
    id UUID PRIMARY KEY,
    order_purchase_id UUID NOT NULL REFERENCES purchasing.purchase(id),
    invoice_purchase_id UUID NOT NULL REFERENCES purchasing.purchase(id),
    linked_at TIMESTAMPTZ NOT NULL,
    linked_by UUID NOT NULL,
    divergent BOOLEAN NOT NULL,
    divergence_justification VARCHAR(500),
    released_at TIMESTAMPTZ,
    released_by UUID,
    release_reason VARCHAR(500),
    CONSTRAINT ck_order_invoice_link_distinct CHECK (order_purchase_id <> invoice_purchase_id),
    -- C2-A: divergência sem justificativa não existe no banco, mesmo por SQL direto.
    CONSTRAINT ck_order_invoice_link_justified CHECK (
        NOT divergent OR (divergence_justification IS NOT NULL AND btrim(divergence_justification) <> '')),
    CONSTRAINT ck_order_invoice_link_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL
            AND btrim(release_reason) <> ''))
);

-- Identidade da aquisição: um pedido tem no máximo UMA nota ativa e uma nota encerra no máximo UM pedido. É o índice
-- que resolve a corrida de duas importações simultâneas escolhendo o mesmo pedido.
CREATE UNIQUE INDEX uq_order_invoice_link_active_order
    ON purchasing.purchase_order_invoice_link (order_purchase_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_order_invoice_link_active_invoice
    ON purchasing.purchase_order_invoice_link (invoice_purchase_id) WHERE released_at IS NULL;
CREATE INDEX ix_order_invoice_link_invoice ON purchasing.purchase_order_invoice_link (invoice_purchase_id);

-- Diferenças vistas no instante do vínculo (C2-A/C3-A). Linha a linha, e não um JSON, para a tela e o relatório lerem
-- sem interpretar texto.
CREATE TABLE purchasing.purchase_order_invoice_difference (
    link_id UUID NOT NULL REFERENCES purchasing.purchase_order_invoice_link(id),
    position INT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    product_id UUID,
    description VARCHAR(300),
    order_value NUMERIC(19,4),
    invoice_value NUMERIC(19,4),
    PRIMARY KEY (link_id, position),
    CONSTRAINT ck_order_invoice_difference_kind CHECK (kind IN (
        'TOTAL', 'QUANTITY', 'UNIT_PRICE', 'ITEM_NOT_IN_INVOICE', 'ITEM_NOT_IN_ORDER'))
);

-- O vínculo só muda para ser liberado; nunca é apagado. As diferenças são append-only.
CREATE TRIGGER tg_order_invoice_link_no_delete
    BEFORE DELETE ON purchasing.purchase_order_invoice_link
    FOR EACH ROW EXECUTE FUNCTION purchasing.forbid_mutation();

CREATE TRIGGER tg_order_invoice_difference_append_only
    BEFORE UPDATE OR DELETE ON purchasing.purchase_order_invoice_difference
    FOR EACH ROW EXECUTE FUNCTION purchasing.forbid_mutation();

-- ---------------------------------------------------------------- auditoria

ALTER TABLE purchasing.purchase_event DROP CONSTRAINT ck_purchase_event_type;
ALTER TABLE purchasing.purchase_event ADD CONSTRAINT ck_purchase_event_type CHECK (event_type IN (
    'IMPORTED', 'CREATED', 'CANCELLED', 'DOCUMENT_VOIDED', 'PAYABLES_REGISTERED', 'PAYABLES_CANCELLED',
    'ITEM_LINK_CONFIRMED', 'SUPPLIER_CREATED_FROM_NFE', 'RECEIPT_REGISTERED', 'RECEIPT_REVERSED',
    'ORDER_UPDATED', 'ORDER_SUBMITTED', 'ORDER_RETURNED', 'ORDER_APPROVED',
    'ORDER_LINKED_TO_INVOICE', 'ORDER_LINK_RELEASED'));
