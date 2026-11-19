-- TASK-0024 etapa B / DR-0024 Q3: juros e multa por atraso, sugeridos e decididos pelo operador.
-- Q5 aproveita a mesma mudança de configuração: limite de aprovação de contas a pagar.
--
-- O valor original nunca muda: juros e multa aplicados viram acréscimo (receivable_adjustment com origem
-- LATE_CHARGE, V28); dispensados ficam registrados com motivo. O cálculo decidido guarda a regra e as parcelas.

-- ---------------------------------------------------------------- configuração

ALTER TABLE finance.settings DROP CONSTRAINT ck_finance_settings_key;
ALTER TABLE finance.settings
    ADD CONSTRAINT ck_finance_settings_key CHECK (key IN ('DEFAULT_RECEIVABLE_DUE_DAYS', 'LATE_FEE_PERCENT',
        'LATE_INTEREST_MONTHLY_PERCENT', 'PAYABLE_APPROVAL_THRESHOLD')),
    ADD CONSTRAINT ck_finance_settings_late_fee CHECK (key <> 'LATE_FEE_PERCENT'
        OR (value ~ '^[0-9]{1,2}(\.[0-9]{1,2})?$' AND value::NUMERIC <= 20)),
    ADD CONSTRAINT ck_finance_settings_late_interest CHECK (key <> 'LATE_INTEREST_MONTHLY_PERCENT'
        OR (value ~ '^[0-9]{1,2}(\.[0-9]{1,2})?$' AND value::NUMERIC <= 10)),
    ADD CONSTRAINT ck_finance_settings_approval_threshold CHECK (key <> 'PAYABLE_APPROVAL_THRESHOLD'
        OR value ~ '^[0-9]{1,9}(\.[0-9]{1,2})?$');

-- Padrões da DR-0024: multa de 2% e juros de 1% ao mês; aprovação desligada (0) até o proprietário definir o limite.
INSERT INTO finance.settings (key, value, updated_at) VALUES
    ('LATE_FEE_PERCENT', '2.00', now()),
    ('LATE_INTEREST_MONTHLY_PERCENT', '1.00', now()),
    ('PAYABLE_APPROVAL_THRESHOLD', '0.00', now())
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------- cálculo decidido

CREATE TABLE finance.receivable_late_charge (
    id UUID PRIMARY KEY,
    receivable_id UUID NOT NULL REFERENCES finance.receivable(id),
    decision VARCHAR(8) NOT NULL,
    calculated_on DATE NOT NULL,
    fee_percent NUMERIC(5,2) NOT NULL,
    interest_percent NUMERIC(5,2) NOT NULL,
    fee_amount NUMERIC(19,2) NOT NULL,
    interest_amount NUMERIC(19,2) NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    -- Aplicado: o acréscimo gerado. Estornar o acréscimo (DR-0017) devolve o período à sugestão.
    adjustment_id UUID REFERENCES finance.receivable_adjustment(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_receivable_late_charge_key UNIQUE (idempotency_key),
    CONSTRAINT uq_receivable_late_charge_adjustment UNIQUE (adjustment_id),
    CONSTRAINT ck_receivable_late_charge_decision CHECK (decision IN ('APPLIED', 'WAIVED')),
    CONSTRAINT ck_receivable_late_charge_adjustment CHECK ((decision = 'APPLIED') = (adjustment_id IS NOT NULL)),
    CONSTRAINT ck_receivable_late_charge_amounts CHECK (fee_amount >= 0 AND interest_amount >= 0 AND total_amount > 0
        AND total_amount = fee_amount + interest_amount),
    CONSTRAINT ck_receivable_late_charge_percents CHECK (fee_percent BETWEEN 0 AND 20 AND interest_percent BETWEEN 0 AND 10),
    CONSTRAINT ck_receivable_late_charge_reason CHECK (btrim(reason) <> '')
);
CREATE INDEX ix_receivable_late_charge_receivable ON finance.receivable_late_charge (receivable_id, recorded_at);

CREATE TABLE finance.receivable_late_charge_line (
    late_charge_id UUID NOT NULL REFERENCES finance.receivable_late_charge(id),
    plan_version INTEGER NOT NULL,
    installment_number INTEGER NOT NULL,
    due_date DATE NOT NULL,
    base_amount NUMERIC(19,2) NOT NULL,
    interest_from DATE NOT NULL,
    calculated_on DATE NOT NULL,
    days INTEGER NOT NULL,
    fee_amount NUMERIC(19,2) NOT NULL,
    interest_amount NUMERIC(19,2) NOT NULL,
    PRIMARY KEY (late_charge_id, installment_number),
    CONSTRAINT ck_receivable_late_charge_line_values CHECK (plan_version >= 0 AND installment_number >= 1 AND base_amount > 0
        AND days >= 0 AND fee_amount >= 0 AND interest_amount >= 0 AND interest_from >= due_date AND calculated_on > due_date)
);
