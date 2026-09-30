-- TASK-0016 / DR-0018: sessão de caixa físico e movimentos auditáveis.
--
-- Princípios:
--   * no MVP existe no máximo uma sessão OPEN por instalação;
--   * movimentos são append-only; correções são movimentos REVERSAL/ADJUSTMENT;
--   * saldo esperado é derivado do saldo físico de abertura + movimentos;
--   * recebimentos/pagamentos continuam sendo a fonte financeira; caixa representa custódia física;
--   * valores monetários usam NUMERIC(19,2), conforme DR-0007.

CREATE TABLE finance.cash_session (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    conference_status VARCHAR(20) NOT NULL,

    opened_at TIMESTAMPTZ NOT NULL,
    opened_by UUID NOT NULL,
    opening_expected_balance NUMERIC(19,2) NOT NULL,
    opening_counted_balance NUMERIC(19,2) NOT NULL,
    opening_difference_reason VARCHAR(500),

    closed_at TIMESTAMPTZ,
    closed_by UUID,
    closing_expected_balance NUMERIC(19,2),
    closing_counted_balance NUMERIC(19,2),
    closing_difference_reason VARCHAR(500),

    checked_at TIMESTAMPTZ,
    checked_by UUID,
    checked_counted_balance NUMERIC(19,2),
    checked_difference_reason VARCHAR(500),

    CONSTRAINT ck_cash_session_status CHECK (status IN ('OPEN', 'CLOSED', 'AUTO_CLOSED')),
    CONSTRAINT ck_cash_session_conference_status CHECK (conference_status IN ('CHECKED', 'NOT_CHECKED')),
    CONSTRAINT ck_cash_session_opening_balances CHECK (opening_expected_balance >= 0 AND opening_counted_balance >= 0),
    CONSTRAINT ck_cash_session_opening_difference_reason CHECK (
        opening_counted_balance = opening_expected_balance
        OR (opening_difference_reason IS NOT NULL AND btrim(opening_difference_reason) <> '')
    ),
    CONSTRAINT ck_cash_session_close_shape CHECK (
        (status = 'OPEN'
            AND closed_at IS NULL AND closed_by IS NULL
            AND closing_expected_balance IS NULL AND closing_counted_balance IS NULL
            AND closing_difference_reason IS NULL)
        OR
        (status = 'CLOSED'
            AND closed_at IS NOT NULL AND closed_by IS NOT NULL
            AND closing_expected_balance IS NOT NULL AND closing_counted_balance IS NOT NULL
            AND conference_status = 'CHECKED')
        OR
        (status = 'AUTO_CLOSED'
            AND closed_at IS NOT NULL
            AND closing_expected_balance IS NOT NULL
            AND closing_counted_balance IS NULL
            AND conference_status IN ('NOT_CHECKED', 'CHECKED'))
    ),
    CONSTRAINT ck_cash_session_closing_balances CHECK (
        closing_expected_balance IS NULL OR closing_expected_balance >= 0
    ),
    CONSTRAINT ck_cash_session_manual_difference_reason CHECK (
        status <> 'CLOSED'
        OR closing_counted_balance = closing_expected_balance
        OR (closing_difference_reason IS NOT NULL AND btrim(closing_difference_reason) <> '')
    ),
    CONSTRAINT ck_cash_session_check_shape CHECK (
        (conference_status = 'NOT_CHECKED'
            AND checked_at IS NULL AND checked_by IS NULL
            AND checked_counted_balance IS NULL AND checked_difference_reason IS NULL)
        OR
        (conference_status = 'CHECKED'
            AND (
                status = 'CLOSED'
                OR (status = 'AUTO_CLOSED'
                    AND checked_at IS NOT NULL AND checked_by IS NOT NULL
                    AND checked_counted_balance IS NOT NULL)
            ))
    ),
    CONSTRAINT ck_cash_session_checked_balance CHECK (checked_counted_balance IS NULL OR checked_counted_balance >= 0),
    CONSTRAINT ck_cash_session_checked_difference_reason CHECK (
        status <> 'AUTO_CLOSED' OR conference_status <> 'CHECKED'
        OR checked_counted_balance = closing_expected_balance
        OR (checked_difference_reason IS NOT NULL AND btrim(checked_difference_reason) <> '')
    )
);

-- O sistema atual ainda é single-installation/single-tenant no banco. Este índice materializa D2 do MVP.
-- Quando tenant_id existir como conceito persistido, a unicidade deverá migrar para (tenant_id) WHERE OPEN.
CREATE UNIQUE INDEX uq_cash_session_single_open
    ON finance.cash_session ((1))
    WHERE status = 'OPEN';
CREATE INDEX ix_cash_session_opened_at ON finance.cash_session (opened_at DESC);
CREATE INDEX ix_cash_session_status ON finance.cash_session (status, opened_at DESC);

CREATE TABLE finance.cash_movement (
    id UUID PRIMARY KEY,
    cash_session_id UUID NOT NULL REFERENCES finance.cash_session(id),
    movement_type VARCHAR(32) NOT NULL,
    direction VARCHAR(3) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    reason VARCHAR(500),

    receipt_id UUID REFERENCES finance.receipt(id),
    payable_payment_id UUID REFERENCES finance.payable_payment(id),
    reversed_movement_id UUID REFERENCES finance.cash_movement(id),

    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,

    CONSTRAINT uq_cash_movement_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_cash_movement_type CHECK (movement_type IN (
        'RECEIPT', 'CHANGE', 'PAYABLE_PAYMENT', 'SUPPLY', 'WITHDRAWAL', 'ADJUSTMENT', 'REVERSAL'
    )),
    CONSTRAINT ck_cash_movement_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_cash_movement_amount CHECK (amount > 0),
    CONSTRAINT ck_cash_movement_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT ck_cash_movement_manual_reason CHECK (
        movement_type NOT IN ('SUPPLY', 'WITHDRAWAL', 'ADJUSTMENT', 'REVERSAL')
        OR (reason IS NOT NULL AND btrim(reason) <> '')
    ),
    CONSTRAINT ck_cash_movement_receipt_origin CHECK (
        (movement_type IN ('RECEIPT', 'CHANGE') AND receipt_id IS NOT NULL AND payable_payment_id IS NULL)
        OR
        (movement_type NOT IN ('RECEIPT', 'CHANGE') AND receipt_id IS NULL)
    ),
    CONSTRAINT ck_cash_movement_payable_origin CHECK (
        (movement_type = 'PAYABLE_PAYMENT' AND payable_payment_id IS NOT NULL AND receipt_id IS NULL)
        OR
        (movement_type <> 'PAYABLE_PAYMENT' AND payable_payment_id IS NULL)
    ),
    CONSTRAINT ck_cash_movement_reversal_origin CHECK (
        (movement_type = 'REVERSAL' AND reversed_movement_id IS NOT NULL)
        OR
        (movement_type <> 'REVERSAL' AND reversed_movement_id IS NULL)
    ),
    CONSTRAINT ck_cash_movement_direction_by_type CHECK (
        (movement_type IN ('RECEIPT', 'SUPPLY') AND direction = 'IN')
        OR (movement_type IN ('CHANGE', 'PAYABLE_PAYMENT', 'WITHDRAWAL') AND direction = 'OUT')
        OR movement_type IN ('ADJUSTMENT', 'REVERSAL')
    ),
    CONSTRAINT ck_cash_movement_not_self_reversal CHECK (reversed_movement_id IS NULL OR reversed_movement_id <> id)
);

CREATE INDEX ix_cash_movement_session_recorded ON finance.cash_movement (cash_session_id, recorded_at, id);
CREATE UNIQUE INDEX uq_cash_movement_receipt
    ON finance.cash_movement (receipt_id)
    WHERE movement_type = 'RECEIPT';
CREATE UNIQUE INDEX uq_cash_movement_change
    ON finance.cash_movement (receipt_id)
    WHERE movement_type = 'CHANGE';
CREATE UNIQUE INDEX uq_cash_movement_payable_payment
    ON finance.cash_movement (payable_payment_id)
    WHERE movement_type = 'PAYABLE_PAYMENT';
CREATE UNIQUE INDEX uq_cash_movement_reversal
    ON finance.cash_movement (reversed_movement_id)
    WHERE movement_type = 'REVERSAL';

-- Permissões aprovadas na DR-0018. FINANCE_VIEW continua cobrindo leitura/consulta do caixa.
INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000026', 'CASH_SESSION_OPEN', 'Abrir sessão de caixa'),
    ('10000000-0000-0000-0000-000000000027', 'CASH_SESSION_CLOSE', 'Fechar e conferir sessão de caixa'),
    ('10000000-0000-0000-0000-000000000028', 'CASH_SUPPLY', 'Registrar suprimento de caixa'),
    ('10000000-0000-0000-0000-000000000029', 'CASH_WITHDRAWAL', 'Registrar sangria de caixa'),
    ('10000000-0000-0000-0000-000000000030', 'CASH_REVERSAL', 'Estornar movimentação de caixa')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO')
  AND permission.code IN ('CASH_SESSION_OPEN', 'CASH_SESSION_CLOSE', 'CASH_SUPPLY', 'CASH_WITHDRAWAL', 'CASH_REVERSAL')
ON CONFLICT DO NOTHING;
