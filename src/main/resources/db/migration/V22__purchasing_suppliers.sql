-- TASK-0021 / I2: fornecedores, contatos comerciais e trilha de auditoria do cadastro (módulo Compras, AG-05).
--
-- Princípios:
--   * somente schema novo `purchasing`: nada em `public` (que no Supabase já tem tabelas de outro módulo) e nenhuma
--     alteração em tabelas de outros módulos;
--   * fornecedor nunca é apagado (histórico de compras, cotações e preços); inativar bloqueia novos usos;
--   * trilha de auditoria append-only: o banco recusa UPDATE e DELETE em supplier_event;
--   * documento (CPF/CNPJ) sem máscara, único quando informado; CNPJ aceita o formato alfanumérico (NT 2025.001);
--   * valores monetários em NUMERIC(19,2), conforme DR-0007.

CREATE SCHEMA IF NOT EXISTS purchasing;

CREATE TABLE purchasing.supplier (
    id UUID PRIMARY KEY,
    person_type VARCHAR(2) NOT NULL,
    legal_name VARCHAR(160) NOT NULL,
    trade_name VARCHAR(160),
    -- 11 dígitos (CPF) ou 14 posições (CNPJ numérico ou alfanumérico); o dígito verificador é validado na aplicação.
    document VARCHAR(14),
    state_registration VARCHAR(30),
    phone VARCHAR(24),
    email VARCHAR(254),
    address_zip_code VARCHAR(8),
    address_street VARCHAR(160),
    address_number VARCHAR(20),
    address_complement VARCHAR(80),
    address_district VARCHAR(80),
    address_city VARCHAR(80),
    address_state VARCHAR(2),
    payment_terms VARCHAR(160),
    preferred_payment_method VARCHAR(80),
    usual_due_day SMALLINT,
    credit_limit NUMERIC(19,2),
    commercial_notes VARCHAR(1000),
    -- Empresa relacionada à oficina: informativo, nunca confunde as pessoas jurídicas (CNPJ próprio e único).
    related_party BOOLEAN NOT NULL DEFAULT FALSE,
    related_party_note VARCHAR(300),
    status VARCHAR(16) NOT NULL,
    inactivated_at TIMESTAMPTZ,
    inactivated_by UUID,
    inactivation_reason VARCHAR(500),
    -- Controle otimista: duas pessoas editando o mesmo cadastro não se sobrescrevem em silêncio.
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT ck_supplier_person_type CHECK (person_type IN ('PF', 'PJ')),
    CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_supplier_legal_name CHECK (btrim(legal_name) <> ''),
    CONSTRAINT ck_supplier_document_format CHECK (document IS NULL OR document ~ '^[0-9]{11}$' OR document ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    -- CPF só para pessoa física e CNPJ só para pessoa jurídica.
    CONSTRAINT ck_supplier_document_person_type CHECK (document IS NULL
        OR (person_type = 'PF' AND document ~ '^[0-9]{11}$')
        OR (person_type = 'PJ' AND document ~ '^[0-9A-Z]{12}[0-9]{2}$')),
    CONSTRAINT ck_supplier_zip_code CHECK (address_zip_code IS NULL OR address_zip_code ~ '^[0-9]{8}$'),
    CONSTRAINT ck_supplier_state CHECK (address_state IS NULL OR address_state ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_supplier_due_day CHECK (usual_due_day IS NULL OR usual_due_day BETWEEN 1 AND 31),
    CONSTRAINT ck_supplier_credit_limit CHECK (credit_limit IS NULL OR credit_limit >= 0),
    CONSTRAINT ck_supplier_related_party_note CHECK (related_party OR related_party_note IS NULL),
    CONSTRAINT ck_supplier_inactivation CHECK (
        (status = 'ACTIVE' AND inactivated_at IS NULL AND inactivated_by IS NULL AND inactivation_reason IS NULL)
        OR (status = 'INACTIVE' AND inactivated_at IS NOT NULL AND inactivated_by IS NOT NULL
            AND inactivation_reason IS NOT NULL AND btrim(inactivation_reason) <> ''))
);
CREATE UNIQUE INDEX uq_supplier_document ON purchasing.supplier (document) WHERE document IS NOT NULL;
CREATE INDEX ix_supplier_name ON purchasing.supplier (lower(legal_name), id);
CREATE INDEX ix_supplier_status_name ON purchasing.supplier (status, lower(legal_name), id);

CREATE TABLE purchasing.supplier_contact (
    id UUID PRIMARY KEY,
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(id),
    name VARCHAR(160) NOT NULL,
    role VARCHAR(80),
    phone VARCHAR(24),
    email VARCHAR(254),
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    -- Contato removido fica inativo: cotações e pedidos futuros podem ter referenciado quem atendeu.
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_supplier_contact_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_supplier_contact_primary_active CHECK (NOT primary_contact OR active)
);
CREATE INDEX ix_supplier_contact_supplier ON purchasing.supplier_contact (supplier_id, active DESC, lower(name), id);
-- No máximo um contato principal ativo por fornecedor.
CREATE UNIQUE INDEX uq_supplier_contact_primary ON purchasing.supplier_contact (supplier_id)
    WHERE primary_contact AND active;

CREATE TABLE purchasing.supplier_event (
    id UUID PRIMARY KEY,
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(id),
    event_type VARCHAR(24) NOT NULL,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500),
    -- {"campo": {"before": "...", "after": "..."}}; documento de pessoa física é gravado mascarado.
    changes JSONB,
    CONSTRAINT ck_supplier_event_type CHECK (event_type IN ('CREATED', 'UPDATED', 'INACTIVATED', 'REACTIVATED',
        'CONTACT_ADDED', 'CONTACT_UPDATED', 'CONTACT_REMOVED')),
    CONSTRAINT ck_supplier_event_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT ck_supplier_event_inactivation_reason CHECK (event_type <> 'INACTIVATED' OR reason IS NOT NULL)
);
CREATE INDEX ix_supplier_event_supplier ON purchasing.supplier_event (supplier_id, occurred_at DESC, id);

-- ---------------------------------------------------------------- histórico não se apaga nem se reescreve

CREATE FUNCTION purchasing.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'purchasing_record_is_append_only: % em %.%', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END $$;

CREATE TRIGGER tg_supplier_event_append_only
    BEFORE UPDATE OR DELETE ON purchasing.supplier_event
    FOR EACH ROW EXECUTE FUNCTION purchasing.forbid_mutation();

CREATE TRIGGER tg_supplier_no_delete
    BEFORE DELETE ON purchasing.supplier
    FOR EACH ROW EXECUTE FUNCTION purchasing.forbid_mutation();

CREATE TRIGGER tg_supplier_contact_no_delete
    BEFORE DELETE ON purchasing.supplier_contact
    FOR EACH ROW EXECUTE FUNCTION purchasing.forbid_mutation();

-- ---------------------------------------------------------------- permissões (AG-05 §117, AG-09)

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000031', 'PURCHASE_VIEW', 'Consultar Compras e Fornecedores'),
    ('10000000-0000-0000-0000-000000000032', 'PURCHASE_SUPPLIER_MANAGE', 'Cadastrar, alterar, inativar e reativar fornecedores')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_ADMINISTRATIVO')
  AND permission.code IN ('PURCHASE_VIEW', 'PURCHASE_SUPPLIER_MANAGE')
ON CONFLICT DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code = 'GERENTE_FINANCEIRO' AND permission.code = 'PURCHASE_VIEW'
ON CONFLICT DO NOTHING;
