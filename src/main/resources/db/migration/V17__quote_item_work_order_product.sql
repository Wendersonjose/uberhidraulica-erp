-- DR-0008 (opção A): vínculo opcional do item comercial com o item físico específico da OS,
-- simétrico ao vínculo existente com serviço. Linhas antigas não são reescritas: as colunas novas
-- nascem nulas e só são preenchidas quando há vínculo físico.

-- Alvos das FKs compostas que impedem associação cross-OS no banco.
ALTER TABLE workshop.quote
    ADD CONSTRAINT uq_quote_id_work_order UNIQUE (id, work_order_id);
ALTER TABLE workorder.work_order_product
    ADD CONSTRAINT uq_work_order_product_id_work_order UNIQUE (id, work_order_id);

-- work_order_id é redundante de propósito, como o quote_id de quote_item_revision (V8): ele liga as
-- duas FKs abaixo, de modo que o item físico e o orçamento pertençam obrigatoriamente à mesma OS.
ALTER TABLE workshop.quote_item
    ADD COLUMN work_order_id UUID,
    ADD COLUMN work_order_product_id UUID,
    ADD CONSTRAINT ck_quote_item_product_scope
        CHECK ((work_order_product_id IS NULL) = (work_order_id IS NULL)),
    ADD CONSTRAINT fk_quote_item_quote_work_order
        FOREIGN KEY (quote_id, work_order_id) REFERENCES workshop.quote(id, work_order_id),
    ADD CONSTRAINT fk_quote_item_work_order_product
        FOREIGN KEY (work_order_product_id, work_order_id) REFERENCES workorder.work_order_product(id, work_order_id);

-- O mesmo item físico é cobrado no máximo uma vez por orçamento; orçamentos alternativos podem repeti-lo.
CREATE UNIQUE INDEX uq_quote_item_work_order_product
    ON workshop.quote_item(quote_id, work_order_product_id)
    WHERE work_order_product_id IS NOT NULL;
