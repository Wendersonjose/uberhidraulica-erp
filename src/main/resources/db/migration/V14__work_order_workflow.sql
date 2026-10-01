-- TASK-0012 / DR-0012: status configuráveis por etapa, histórico, abertura com reclamação,
-- execução, finalização, entrega e cancelamento.

CREATE TABLE workorder.status (
    id UUID PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    position INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    stage_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_workorder_status_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_workorder_status_stage CHECK (stage IN ('ABERTA', 'EM_DIAGNOSTICO', 'AGUARDANDO_APROVACAO', 'APROVADA',
        'REPROVADA', 'EM_EXECUCAO', 'FINALIZADA', 'ENTREGUE', 'CANCELADA')),
    -- O status padrão de uma etapa é o alvo das regras automáticas e não pode ficar inativo.
    CONSTRAINT ck_workorder_status_default_active CHECK (NOT stage_default OR active)
);
CREATE UNIQUE INDEX uq_workorder_status_name ON workorder.status(lower(name));
CREATE UNIQUE INDEX uq_workorder_status_stage_default ON workorder.status(stage) WHERE stage_default;

INSERT INTO workorder.status (id, name, stage, position, active, stage_default, created_at, updated_at) VALUES
    ('20000000-0000-0000-0000-000000000001', 'Aberta',               'ABERTA',               10, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000002', 'Em diagnóstico',       'EM_DIAGNOSTICO',       20, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000003', 'Aguardando aprovação', 'AGUARDANDO_APROVACAO', 30, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000004', 'Aprovada',             'APROVADA',             40, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000005', 'Reprovada',            'REPROVADA',            50, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000006', 'Em execução',          'EM_EXECUCAO',          60, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000007', 'Finalizada',           'FINALIZADA',           70, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000008', 'Entregue',             'ENTREGUE',             80, TRUE, TRUE, now(), now()),
    ('20000000-0000-0000-0000-000000000009', 'Cancelada',            'CANCELADA',            90, TRUE, TRUE, now(), now());

ALTER TABLE workorder.work_order ADD COLUMN status_id UUID REFERENCES workorder.status(id);
UPDATE workorder.work_order SET status_id = '20000000-0000-0000-0000-000000000001';
ALTER TABLE workorder.work_order ALTER COLUMN status_id SET NOT NULL;
ALTER TABLE workorder.work_order DROP CONSTRAINT ck_work_order_status_initial;
ALTER TABLE workorder.work_order DROP COLUMN status;
CREATE INDEX ix_work_order_status ON workorder.work_order(status_id, opened_at DESC);

-- Quilometragem de entrada passa a ser opcional; a reclamação é exigida pela aplicação para novas OS.
ALTER TABLE workorder.work_order ALTER COLUMN entry_mileage DROP NOT NULL;
ALTER TABLE workorder.work_order
    ADD COLUMN complaint VARCHAR(2000),
    ADD COLUMN notes VARCHAR(2000),
    ADD COLUMN execution_started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN finished_by UUID,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN delivered_by UUID,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by UUID,
    ADD COLUMN cancellation_reason VARCHAR(500),
    ADD CONSTRAINT ck_work_order_complaint CHECK (complaint IS NULL OR btrim(complaint) <> ''),
    ADD CONSTRAINT ck_work_order_notes CHECK (notes IS NULL OR btrim(notes) <> ''),
    ADD CONSTRAINT ck_work_order_cancellation CHECK ((cancelled_at IS NULL) = (cancellation_reason IS NULL)),
    ADD CONSTRAINT ck_work_order_cancellation_reason CHECK (cancellation_reason IS NULL OR btrim(cancellation_reason) <> ''),
    ADD CONSTRAINT ck_work_order_delivery_after_finish CHECK (delivered_at IS NULL OR (finished_at IS NOT NULL AND delivered_at >= finished_at)),
    ADD CONSTRAINT ck_work_order_finished_or_cancelled CHECK (finished_at IS NULL OR cancelled_at IS NULL);

CREATE TABLE workorder.work_order_status_history (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES workorder.work_order(id),
    from_status_id UUID REFERENCES workorder.status(id),
    to_status_id UUID NOT NULL REFERENCES workorder.status(id),
    changed_at TIMESTAMPTZ NOT NULL,
    changed_by UUID,
    reason VARCHAR(500),
    automatic BOOLEAN NOT NULL,
    CONSTRAINT ck_work_order_status_history_reason CHECK (reason IS NULL OR btrim(reason) <> '')
);
CREATE INDEX ix_work_order_status_history_order ON workorder.work_order_status_history(work_order_id, changed_at, id);

INSERT INTO workorder.work_order_status_history (id, work_order_id, from_status_id, to_status_id, changed_at, automatic)
SELECT gen_random_uuid(), id, NULL, status_id, opened_at, TRUE FROM workorder.work_order;
