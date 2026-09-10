CREATE SCHEMA IF NOT EXISTS iam;

CREATE TABLE iam.profile (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    description VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_iam_profile_code CHECK (code IN ('DONO', 'GERENTE_ADMINISTRATIVO', 'GERENTE_FINANCEIRO'))
);

CREATE TABLE iam.permission (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE iam.app_user (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL UNIQUE,
    profile_id UUID NOT NULL REFERENCES iam.profile(id),
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_iam_user_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_iam_user_profile_state ON iam.app_user(profile_id, state);

CREATE TABLE iam.profile_permission (
    profile_id UUID NOT NULL REFERENCES iam.profile(id),
    permission_id UUID NOT NULL REFERENCES iam.permission(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (profile_id, permission_id)
);

CREATE INDEX ix_iam_profile_permission_permission ON iam.profile_permission(permission_id);

CREATE TABLE iam.user_permission_exception (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES iam.app_user(id),
    permission_id UUID NOT NULL REFERENCES iam.permission(id),
    resolution VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_iam_user_permission_exception UNIQUE (user_id, permission_id),
    CONSTRAINT ck_iam_permission_resolution CHECK (resolution IN ('INHERIT', 'ALLOW', 'DENY'))
);

CREATE INDEX ix_iam_user_permission_exception_permission ON iam.user_permission_exception(permission_id);

CREATE TABLE iam.credential (
    user_id UUID PRIMARY KEY REFERENCES iam.app_user(id),
    password_hash VARCHAR(255) NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE iam.audit_event (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id UUID REFERENCES iam.app_user(id),
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80),
    target_id VARCHAR(120),
    result VARCHAR(24) NOT NULL,
    correlation_id VARCHAR(100),
    before_state TEXT,
    after_state TEXT,
    CONSTRAINT ck_iam_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX ix_iam_audit_occurred_at ON iam.audit_event(occurred_at);
CREATE INDEX ix_iam_audit_actor ON iam.audit_event(actor_user_id, occurred_at);
CREATE INDEX ix_iam_audit_target ON iam.audit_event(target_type, target_id, occurred_at);

INSERT INTO iam.profile (id, code, description) VALUES
    ('00000000-0000-0000-0000-000000000001', 'DONO', 'Dono'),
    ('00000000-0000-0000-0000-000000000002', 'GERENTE_ADMINISTRATIVO', 'Gerente Administrativo'),
    ('00000000-0000-0000-0000-000000000003', 'GERENTE_FINANCEIRO', 'Gerente Financeiro')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000001', 'IAM_USERS_READ', 'Consultar usuários'),
    ('10000000-0000-0000-0000-000000000002', 'IAM_USERS_MANAGE', 'Criar e alterar usuários'),
    ('10000000-0000-0000-0000-000000000003', 'IAM_CATALOG_READ', 'Consultar perfis e permissões'),
    ('10000000-0000-0000-0000-000000000004', 'IAM_PROFILE_PERMISSIONS_MANAGE', 'Administrar permissões de perfis'),
    ('10000000-0000-0000-0000-000000000005', 'IAM_USER_EXCEPTIONS_MANAGE', 'Administrar exceções individuais'),
    ('10000000-0000-0000-0000-000000000006', 'IAM_PASSWORD_RESET', 'Redefinir senha de outro usuário')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p
CROSS JOIN iam.permission permission
WHERE p.code = 'DONO'
  AND permission.code LIKE 'IAM_%'
ON CONFLICT DO NOTHING;

CREATE TABLE iam.spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(320),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON iam.spring_session(session_id);
CREATE INDEX spring_session_ix2 ON iam.spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON iam.spring_session(principal_name);

CREATE TABLE iam.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES iam.spring_session(primary_id) ON DELETE CASCADE
);
