-- TASK-0021 / I5: pedido de compra (ORDER/DIRECT), rascunho editável e aprovação com segregação de funções.
--
-- Implementa a decisão aprovada da DR-0022 D8 (2026-10-13): segregação de aprovação, com exceção para o DONO.
-- A exceção é uma PERMISSÃO (PURCHASE_APPROVE_OWN), não uma checagem de perfil no código: quem a recebe no perfil
-- pode aprovar o que ele mesmo criou, e o proprietário muda isso sem alterar código.
--
-- O que esta migration NÃO faz, de propósito:
--   * não cria contas a pagar para pedido: a obrigação de um pedido sem nota depende da conciliação pedido × NF-e,
--     que ainda não tem regra aprovada. Gerar conta aqui e importar a nota depois contaria o valor duas vezes;
--   * não cria tabelas novas: pedido usa purchasing.purchase / purchase_item, os mesmos do recebimento (I4).
--
-- Somente aditiva; nenhuma migration anterior é alterada.

-- ---------------------------------------------------------------- controle de edição do rascunho

-- Controle otimista: duas pessoas editando o mesmo rascunho não se sobrescrevem em silêncio.
ALTER TABLE purchasing.purchase ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE purchasing.purchase ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE purchasing.purchase ADD COLUMN updated_by UUID;

-- Quem aprovou e quando. Fica na compra (e não só na trilha) porque a tela e o relatório precisam do aprovador sem
-- varrer eventos. Compra importada de NF-e nasce aprovada pelo fato fiscal e não tem aprovador: os dois ficam nulos.
ALTER TABLE purchasing.purchase ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE purchasing.purchase ADD COLUMN approved_by UUID;
ALTER TABLE purchasing.purchase ADD CONSTRAINT ck_purchase_approval_pair
    CHECK ((approved_at IS NULL) = (approved_by IS NULL));

-- ---------------------------------------------------------------- itens: apagar só em rascunho

-- Na V23 o item nunca podia ser apagado. O rascunho de pedido é editado trocando os itens, e rascunho ainda não é
-- fato (não tem recebimento, parcela nem documento). Fora do rascunho o item continua intocável pelo banco.
DROP TRIGGER tg_purchase_item_no_delete ON purchasing.purchase_item;

CREATE FUNCTION purchasing.forbid_item_delete_unless_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM purchasing.purchase p WHERE p.id = OLD.purchase_id AND p.status = 'DRAFT') THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'purchasing_record_is_append_only: DELETE em purchasing.purchase_item de compra que não é rascunho'
        USING ERRCODE = 'check_violation';
END $$;

CREATE TRIGGER tg_purchase_item_delete_draft_only
    BEFORE DELETE ON purchasing.purchase_item
    FOR EACH ROW EXECUTE FUNCTION purchasing.forbid_item_delete_unless_draft();

-- ---------------------------------------------------------------- auditoria

ALTER TABLE purchasing.purchase_event DROP CONSTRAINT ck_purchase_event_type;
ALTER TABLE purchasing.purchase_event ADD CONSTRAINT ck_purchase_event_type CHECK (event_type IN (
    'IMPORTED', 'CREATED', 'CANCELLED', 'DOCUMENT_VOIDED', 'PAYABLES_REGISTERED', 'PAYABLES_CANCELLED',
    'ITEM_LINK_CONFIRMED', 'SUPPLIER_CREATED_FROM_NFE', 'RECEIPT_REGISTERED', 'RECEIPT_REVERSED',
    'ORDER_UPDATED', 'ORDER_SUBMITTED', 'ORDER_RETURNED', 'ORDER_APPROVED'));

-- ---------------------------------------------------------------- permissões (AG-09, DR-0022 D8)

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000038', 'PURCHASE_APPROVE', 'Aprovar e devolver pedido de compra'),
    ('10000000-0000-0000-0000-000000000039', 'PURCHASE_APPROVE_OWN', 'Aprovar pedido de compra que o próprio usuário criou (exceção da segregação de funções)')
ON CONFLICT (code) DO NOTHING;

-- Aprovar: DONO e GERENTE_ADMINISTRATIVO. Aprovar o PRÓPRIO pedido: só o DONO, conforme a D8 aprovada.
INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_ADMINISTRATIVO') AND permission.code = 'PURCHASE_APPROVE'
ON CONFLICT DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code = 'DONO' AND permission.code = 'PURCHASE_APPROVE_OWN'
ON CONFLICT DO NOTHING;
