-- Auditoria: productcatalog, inventory, workorder (exceto finish, que já tem FINANCE_BILL), crm e
-- servicecatalog não tinham nenhum @PreAuthorize. Fecha a lacuna reaproveitando os três perfis fixos
-- (DR-0003), com granularidade para separar operação de rotina de operação sensível/financeira.

INSERT INTO iam.permission (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000016', 'PRODUCT_MANAGE', 'Criar e editar produtos do catálogo'),
    ('10000000-0000-0000-0000-000000000017', 'PRODUCT_COST_MANAGE', 'Alterar custo de referência e preço de venda de produtos'),
    ('10000000-0000-0000-0000-000000000018', 'INVENTORY_MOVE', 'Registrar entrada e saída de estoque'),
    ('10000000-0000-0000-0000-000000000019', 'INVENTORY_ADJUST', 'Ajustar e estornar movimentação de estoque; configurar modo de baixa'),
    ('10000000-0000-0000-0000-000000000020', 'WORKORDER_MANAGE', 'Abrir, editar, diagnosticar, mover status, iniciar execução, entregar, cancelar e lançar item na Ordem de Serviço'),
    ('10000000-0000-0000-0000-000000000021', 'WORKORDER_CONFIG', 'Configurar os status do fluxo da Ordem de Serviço'),
    ('10000000-0000-0000-0000-000000000022', 'CRM_MANAGE', 'Criar, editar, inativar/reativar e transferir cliente e veículo'),
    ('10000000-0000-0000-0000-000000000023', 'SERVICE_MANAGE', 'Criar, editar, ativar/inativar serviços, categorias e grupos de veículos'),
    ('10000000-0000-0000-0000-000000000024', 'SERVICE_PRICE_MANAGE', 'Alterar preços de serviço por veículo ou por grupo'),
    ('10000000-0000-0000-0000-000000000025', 'QUOTE_MANAGE', 'Criar orçamento e nova revisão de orçamento')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_ADMINISTRATIVO')
  AND permission.code IN ('PRODUCT_MANAGE', 'INVENTORY_MOVE', 'WORKORDER_MANAGE', 'WORKORDER_CONFIG',
                           'CRM_MANAGE', 'SERVICE_MANAGE', 'QUOTE_MANAGE')
ON CONFLICT DO NOTHING;

INSERT INTO iam.profile_permission (profile_id, permission_id)
SELECT p.id, permission.id
FROM iam.profile p CROSS JOIN iam.permission permission
WHERE p.code IN ('DONO', 'GERENTE_FINANCEIRO')
  AND permission.code IN ('PRODUCT_COST_MANAGE', 'INVENTORY_ADJUST', 'SERVICE_PRICE_MANAGE')
ON CONFLICT DO NOTHING;
