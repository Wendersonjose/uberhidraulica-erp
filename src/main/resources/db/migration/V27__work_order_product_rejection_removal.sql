-- DR-0019 (decidida pelo proprietário em 2026-11-12: opções A + B).
--
-- A: quando o cliente REJEITA um item de orçamento ligado a um item físico da OS, o Estoque devolve a baixa daquele
--    item. Se uma revisão posterior o aprovar, a baixa é refeita na finalização da OS.
-- B: um item físico não aprovado pode ser REMOVIDO da OS, com motivo e autor, devolvendo o estoque.
--
-- Nada é apagado: a remoção é uma marca no item (histórico antes de sobrescrita) e a devolução é um movimento novo
-- que referencia a baixa original.
--
-- Somente aditiva; nenhuma migration anterior é alterada.

-- ---------------------------------------------------------------- item físico da OS

-- Última decisão do cliente sobre o item (via orçamento) e a remoção manual. A OS não interpreta o orçamento: ela só
-- guarda o que o orçamento lhe informou, para saber o que baixar na finalização e o que ainda pode ser removido.
ALTER TABLE workorder.work_order_product ADD COLUMN customer_decision VARCHAR(10);
ALTER TABLE workorder.work_order_product ADD COLUMN decided_at TIMESTAMPTZ;
ALTER TABLE workorder.work_order_product ADD COLUMN removed_at TIMESTAMPTZ;
ALTER TABLE workorder.work_order_product ADD COLUMN removed_by UUID;
ALTER TABLE workorder.work_order_product ADD COLUMN removal_reason VARCHAR(500);

ALTER TABLE workorder.work_order_product ADD CONSTRAINT ck_work_order_product_decision CHECK (
    (customer_decision IS NULL AND decided_at IS NULL)
    OR (customer_decision IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL));
-- Remoção sem motivo não existe, nem por SQL direto.
ALTER TABLE workorder.work_order_product ADD CONSTRAINT ck_work_order_product_removal CHECK (
    (removed_at IS NULL AND removal_reason IS NULL)
    OR (removed_at IS NOT NULL AND removal_reason IS NOT NULL AND btrim(removal_reason) <> ''));

-- ---------------------------------------------------------------- baixa por item e por ciclo

-- Até a V26 um item da OS só podia ter UMA baixa na vida. Com a devolução por rejeição, o item pode voltar a ser
-- baixado (rejeitado, reapresentado e aprovado): cada baixa é um ciclo. O índice mantém o nome e continua impedindo
-- duas baixas simultâneas do mesmo item — duas transações calculam o mesmo ciclo e a segunda é recusada.
ALTER TABLE inventory.stock_movement ADD COLUMN work_order_cycle INT NOT NULL DEFAULT 1;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_work_order_cycle CHECK (work_order_cycle >= 1);

DROP INDEX inventory.uq_stock_movement_work_order_item;
CREATE UNIQUE INDEX uq_stock_movement_work_order_item
    ON inventory.stock_movement (work_order_item_id, work_order_cycle) WHERE movement_type = 'WORK_ORDER_OUT';
