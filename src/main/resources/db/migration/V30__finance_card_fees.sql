-- TASK-0024 etapa B / DR-0024 Q4: taxas e parcelas de cartão, sem integração com adquirente (DR-0021).
--
-- A tabela de taxas é versionada: uma tabela nova não apaga a anterior. O recebimento congela parcelas, taxa,
-- valor da taxa e data prevista do repasse, para que mudar a configuração depois não reescreva o histórico.

CREATE TABLE finance.payment_method_fee_schedule (
    id UUID PRIMARY KEY,
    payment_method_id UUID NOT NULL REFERENCES finance.payment_method(id),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL
);
CREATE INDEX ix_payment_method_fee_schedule_method ON finance.payment_method_fee_schedule (payment_method_id, created_at DESC);

CREATE TABLE finance.payment_method_fee (
    schedule_id UUID NOT NULL REFERENCES finance.payment_method_fee_schedule(id),
    installments INTEGER NOT NULL,
    fee_percent NUMERIC(5,2) NOT NULL,
    settlement_days INTEGER NOT NULL,
    PRIMARY KEY (schedule_id, installments),
    CONSTRAINT ck_payment_method_fee_installments CHECK (installments BETWEEN 1 AND 24),
    CONSTRAINT ck_payment_method_fee_percent CHECK (fee_percent >= 0 AND fee_percent < 100),
    CONSTRAINT ck_payment_method_fee_days CHECK (settlement_days BETWEEN 0 AND 365)
);

ALTER TABLE finance.receipt ADD COLUMN card_installments INTEGER;
ALTER TABLE finance.receipt ADD COLUMN fee_percent NUMERIC(5,2);
ALTER TABLE finance.receipt ADD COLUMN fee_amount NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE finance.receipt ADD COLUMN expected_settlement_on DATE;
ALTER TABLE finance.receipt
    -- Ou nada (forma sem taxa), ou o conjunto inteiro, congelado no lançamento.
    ADD CONSTRAINT ck_receipt_card_fee CHECK (
        (card_installments IS NULL AND fee_percent IS NULL AND expected_settlement_on IS NULL AND fee_amount = 0)
        OR (card_installments BETWEEN 1 AND 24 AND fee_percent >= 0 AND fee_percent < 100 AND expected_settlement_on >= received_on
            AND fee_amount >= 0 AND fee_amount < amount));
