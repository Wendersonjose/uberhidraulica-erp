-- TASK-0009 / DR-0010: documento opcional, telefone, e-mail e endereço estruturado.

ALTER TABLE crm.customer ALTER COLUMN document DROP NOT NULL;

-- A compatibilidade PF/PJ continua valendo sempre que houver documento; NULL não conflita com
-- uq_crm_customer_document, que permanece global (DR-0005).
ALTER TABLE crm.customer DROP CONSTRAINT ck_crm_customer_document;
ALTER TABLE crm.customer ADD CONSTRAINT ck_crm_customer_document CHECK (
    document IS NULL OR
    (person_type = 'PF' AND document ~ '^[0-9]{11}$') OR
    (person_type = 'PJ' AND document ~ '^[0-9]{14}$')
);

-- Telefone é obrigatório pela API; a coluna aceita NULL somente para clientes anteriores à regra.
ALTER TABLE crm.customer
    ADD COLUMN phone VARCHAR(13),
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN address_zip_code VARCHAR(8),
    ADD COLUMN address_street VARCHAR(160),
    ADD COLUMN address_number VARCHAR(20),
    ADD COLUMN address_complement VARCHAR(80),
    ADD COLUMN address_district VARCHAR(80),
    ADD COLUMN address_city VARCHAR(80),
    ADD COLUMN address_state VARCHAR(2),
    ADD CONSTRAINT ck_crm_customer_phone CHECK (phone IS NULL OR phone ~ '^[0-9]{10,13}$'),
    ADD CONSTRAINT ck_crm_customer_email CHECK (email IS NULL OR email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    ADD CONSTRAINT ck_crm_customer_zip_code CHECK (address_zip_code IS NULL OR address_zip_code ~ '^[0-9]{8}$'),
    ADD CONSTRAINT ck_crm_customer_state CHECK (address_state IS NULL OR address_state ~ '^[A-Z]{2}$');

CREATE INDEX ix_crm_customer_phone ON crm.customer(phone);
CREATE INDEX ix_crm_customer_lower_name ON crm.customer(lower(name), id);
