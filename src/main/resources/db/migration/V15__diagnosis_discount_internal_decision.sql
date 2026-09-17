-- TASK-0013 / DR-0013: diagnóstico da OS, regras automáticas de status, desconto por item do
-- orçamento e registro interno da decisão do cliente.

ALTER TABLE workorder.work_order
    ADD COLUMN diagnosis VARCHAR(4000),
    ADD COLUMN diagnosed_at TIMESTAMPTZ,
    ADD COLUMN diagnosed_by UUID,
    ADD CONSTRAINT ck_work_order_diagnosis CHECK (diagnosis IS NULL OR btrim(diagnosis) <> ''),
    ADD CONSTRAINT ck_work_order_diagnosis_time CHECK ((diagnosis IS NULL) = (diagnosed_at IS NULL));

CREATE TABLE workorder.status_automation (
    event VARCHAR(32) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_status_automation_event CHECK (event IN ('DIAGNOSIS_REGISTERED', 'QUOTE_PRESENTED', 'QUOTE_APPROVED', 'QUOTE_REJECTED'))
);
INSERT INTO workorder.status_automation (event, enabled, updated_at) VALUES
    ('DIAGNOSIS_REGISTERED', TRUE, now()),
    ('QUOTE_PRESENTED', TRUE, now()),
    ('QUOTE_APPROVED', TRUE, now()),
    ('QUOTE_REJECTED', TRUE, now());

-- Desconto em valor faz parte da condição comercial versionada; total = bruto arredondado − desconto.
ALTER TABLE workshop.quote_item_revision
    ADD COLUMN discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_quote_item_revision_discount CHECK (discount_amount >= 0 AND discount_amount = round(discount_amount, 2));

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000008', 'QUOTE_DISCOUNT', 'Conceder desconto em item de orçamento')
ON CONFLICT (code) DO NOTHING;
INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_ADMINISTRATIVO') AND permission.code = 'QUOTE_DISCOUNT'
ON CONFLICT DO NOTHING;

-- A submissão passa a ter canal. O link público mantém todas as evidências exigidas pela TASK-0008;
-- o registro interno exige o usuário que registrou e o canal de contato com o cliente.
ALTER TABLE workshop.quote_decision_submission
    ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'PUBLIC_LINK',
    ADD COLUMN contact_channel VARCHAR(16),
    ADD COLUMN recorded_by UUID,
    ADD COLUMN notes VARCHAR(500);
ALTER TABLE workshop.quote_decision_submission
    ALTER COLUMN public_quote_access_id DROP NOT NULL,
    ALTER COLUMN request_payload_digest DROP NOT NULL,
    ALTER COLUMN customer_name DROP NOT NULL,
    ALTER COLUMN document_type DROP NOT NULL,
    ALTER COLUMN document_number DROP NOT NULL,
    ALTER COLUMN ip_address DROP NOT NULL;
ALTER TABLE workshop.quote_decision_submission
    DROP CONSTRAINT ck_quote_submission_document_type,
    DROP CONSTRAINT ck_quote_submission_document_length,
    DROP CONSTRAINT ck_quote_submission_document_digits,
    DROP CONSTRAINT ck_quote_submission_name;
ALTER TABLE workshop.quote_decision_submission
    ADD CONSTRAINT ck_quote_submission_channel CHECK (channel IN ('PUBLIC_LINK', 'INTERNAL')),
    ADD CONSTRAINT ck_quote_submission_contact_channel CHECK (contact_channel IS NULL OR contact_channel IN ('PRESENCIAL', 'TELEFONE', 'WHATSAPP', 'EMAIL', 'OUTRO')),
    ADD CONSTRAINT ck_quote_submission_public_evidence CHECK (channel <> 'PUBLIC_LINK' OR (
        public_quote_access_id IS NOT NULL AND request_payload_digest IS NOT NULL AND customer_name IS NOT NULL
        AND document_type IS NOT NULL AND document_number IS NOT NULL AND ip_address IS NOT NULL)),
    ADD CONSTRAINT ck_quote_submission_internal_evidence CHECK (channel <> 'INTERNAL' OR (
        recorded_by IS NOT NULL AND contact_channel IS NOT NULL AND public_quote_access_id IS NULL)),
    ADD CONSTRAINT ck_quote_submission_document_type CHECK (document_type IS NULL OR document_type IN ('CPF', 'CNPJ')),
    ADD CONSTRAINT ck_quote_submission_document_length CHECK (document_number IS NULL OR
        (document_type = 'CPF' AND char_length(document_number) = 11) OR (document_type = 'CNPJ' AND char_length(document_number) = 14)),
    ADD CONSTRAINT ck_quote_submission_document_digits CHECK (document_number IS NULL OR document_number ~ '^[0-9]+$'),
    ADD CONSTRAINT ck_quote_submission_name CHECK (customer_name IS NULL OR btrim(customer_name) <> ''),
    ADD CONSTRAINT ck_quote_submission_notes CHECK (notes IS NULL OR btrim(notes) <> '');
