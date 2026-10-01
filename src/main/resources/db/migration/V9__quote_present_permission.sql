-- Apresentar um orçamento define os valores que o cliente poderá aceitar e inicia a validade
-- comercial. Estar autenticado não basta: a permissão é explícita e verificada no backend.
INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000007', 'QUOTE_PRESENT', 'Apresentar orçamento ao cliente')
ON CONFLICT (code) DO NOTHING;

-- GERENTE_FINANCEIRO não recebe automaticamente; exceções individuais do IAM continuam valendo.
INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p
CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_ADMINISTRATIVO')
  AND permission.code = 'QUOTE_PRESENT'
ON CONFLICT DO NOTHING;
