# TASK-0004 — Vertical mínima para lançamento de OS de teste

## Identificação

- Status: `FRONTEND_CORRECTIONS_READY_FOR_REVIEW`
- Prioridade: `CRITICAL`
- Criada em: `2026-09-10`
- Proprietário principal: Oficina — `AG-03`
- Responsável atual: `AG-12`

## Objetivo

Disponibilizar o backend mínimo executável do fluxo Cliente → Veículo → Serviço → Ordem de Serviço para lançamentos fictícios e teste operacional, sem antecipar os demais domínios do ERP.

## Escopo

- Cliente PF/PJ: nome ou razão social, CPF/CNPJ, ativo/inativo, criação, consulta, listagem e atualização mínima.
- Veículo: vínculo obrigatório com cliente, placa, fabricante, modelo, ano, quilometragem cadastral opcional e fabricante da caixa de direção opcional; criação, consulta, listagem por cliente e atualização mínima.
- Serviço: nome, descrição, categoria somente se já definida, preço-base, garantia padrão, referência/base de comissão somente se já definida, ativo/inativo, criação, consulta e listagem.
- OS: número/identificador, cliente, veículo do cliente, quilometragem de entrada obrigatória, abertura, inclusão opcional de serviços, consulta e listagem.
- PostgreSQL/Flyway, API REST autenticada, testes Testcontainers e verificação Spring Modulith.

## Fora do escopo

Frontend React; orçamento completo e aprovação pública; estoque/reserva; compras; financeiro; comissão completa; garantia operacional; fiscal; integrações bancárias; conciliação; rentabilidade; Supabase; exclusão física.

## Regras existentes utilizadas

1. Cliente é entidade de negócio e não possui login.
2. Veículo pertence obrigatoriamente a um cliente e não replica seus dados.
3. A OS referencia cliente e veículo do mesmo cliente e exige quilometragem de entrada.
4. A OS pode existir antes do orçamento.
5. Controller → Application → Domain → Repository Port → Persistence Adapter.
6. Módulos não acessam internals de outros módulos; integração ocorre por contrato público.
7. PostgreSQL, Flyway e `ddl-auto=validate`; UUID, `TIMESTAMPTZ`, `BigDecimal`/`NUMERIC`, FKs e constraints reais.
8. Registros relevantes não sofrem exclusão física.
9. Endpoints internos exigem sessão autenticada e preservam CSRF/IAM.
10. Persistência PostgreSQL é testada com Testcontainers; H2 não é aceito.

## Critérios de aceite

1. Criar, consultar, listar e atualizar cliente PF/PJ, preservando ativo/inativo.
2. Rejeitar documento incompatível com o tipo de pessoa e duplicidade segundo a decisão de unicidade.
3. Criar, consultar e atualizar veículo vinculado a cliente existente.
4. Rejeitar veículo para cliente inexistente e listar veículos por cliente.
5. Normalizar placa de modo consistente.
6. Criar, consultar e listar serviço ativo/inativo com preço-base não negativo e garantia padrão conforme decisão aplicável.
7. Abrir OS com identificador único, instante de abertura, cliente, veículo pertencente ao cliente e quilometragem de entrada não negativa.
8. Permitir OS sem orçamento e inclusão opcional de um ou mais serviços existentes.
9. Rejeitar OS sem quilometragem e veículo de outro cliente.
10. Consultar uma OS com seus relacionamentos e listar OS.
11. Todos os endpoints novos exigem autenticação e operações mutáveis preservam CSRF.
12. Migrations posteriores à V2 validam em PostgreSQL real, incluindo FKs, unicidades, checks e índices.
13. O teste vertical Cliente → Veículo → Serviço → OS → consulta passa em PostgreSQL/Testcontainers.
14. `ApplicationModules.verify()`, `mvn test` e `git diff --check` passam.

## Módulos envolvidos

- `customer` (CRM), `vehicle`, `servicecatalog`, `workorder`, IAM somente por sua API pública/infraestrutura de autenticação já instalada.
- Agentes: AG-00, AG-01, AG-02, AG-03, AG-09, AG-10, AG-11, AG-13 e AG-15.

## Handoffs

1. AG-00 → AG-01/AG-03: consolidação de requisitos e domínio — `CONCLUÍDO_COM_DR`.
2. AG-03 → AG-02/AG-09: fronteiras e autenticação — `CONCLUÍDO`.
3. AG-02 → AG-10/AG-11: persistência e backend — `CONCLUÍDO`.
4. AG-11 → AG-13: testes incrementais e integração vertical — `CONCLUÍDO`.
5. AG-13 → AG-15: revisão independente — `EM_REVISÃO`.
6. AG-15 → AG-00: validação externa e encerramento — `PENDENTE`.

## Decision Requests

- `DR-0004`: `DECIDED` — toda OS nasce `ABERTA`; sem workflow adicional nesta Task.
- `DR-0005`: `DECIDED` — documento somente em dígitos, único globalmente inclusive para cliente inativo.

## Testes obrigatórios

Os cenários enumerados na solicitação da TASK foram cobertos por testes de catálogo e integração vertical com PostgreSQL/Testcontainers. Incluem PF/PJ, normalização/unicidade documental inclusive após inativação, vínculo de veículo, OS `ABERTA`, quilometragem, ownership, serviço inexistente, consultas/listagens, autenticação, CSRF, constraints e modularidade.

## Implementação entregue

- Módulo `crm`: Cliente e Veículo, API, domínio, ports, adapters e contrato público `CustomerVehicleQuery`.
- Módulo `servicecatalog`: preservado e acrescido somente do contrato público `ServiceCatalogQuery` necessário à OS.
- Módulo `workorder`: abertura/listagem/consulta da OS e inclusão de serviço com snapshot comercial mínimo.
- Migrations: V3 catálogo preservada; V4 CRM/Veículo; V5 OS e vínculo de serviços.
- Endpoints: `/api/customers`, `/api/customers/{id}`, `/api/vehicles`, `/api/vehicles/{id}`, `/api/customers/{id}/vehicles`, `/api/services`, `/api/services/{id}`, `/api/work-orders`, `/api/work-orders/{id}`, `/api/work-orders/{id}/services`.
- Segurança: baseline IAM preservado; autenticação obrigatória e CSRF em mutações; nenhuma permissão granular nova.

## Resultado dos critérios e gates

- Critérios de aceite: `14/14 IMPLEMENTADOS` no escopo backend desta etapa.
- Teste vertical real: Cliente → Veículo → Serviço → OS → serviço vinculado → consulta, `PASS` em PostgreSQL/Testcontainers.
- Suíte completa: `42` testes, `0` failures, `0` errors, `0` skipped.
- `ModularityTest`: `1` teste, `PASS`.
- Pendência real: revisão externa; frontend permanece para a próxima etapa e está fora desta execução.

## Aprovação externa do backend

- Backend: `IMPLEMENTED / APPROVED`.
- Revisão externa backend: `APPROVED`.
- Testes backend: `42`.
- Failures: `0`.
- Errors: `0`.
- Skipped: `0`.
- ModularityTest: `PASS`.
- Critérios backend: `14/14`.
- Decision Requests abertas: `0`.
- DR-0004: `DECIDED / CONFORME`.
- DR-0005: `DECIDED / CONFORME`.
- Cliente: `APPROVED`.
- Veículo: `APPROVED`.
- Serviço: `APPROVED`.
- Ordem de Serviço: `APPROVED`.
- Integração vertical Cliente → Veículo → Serviço → OS: `APPROVED / PASS`.

## Implementação frontend mínima

- React/TypeScript mantido em `frontend/`, sem recriar o scaffold ou mover o backend.
- Rotas protegidas para login, troca obrigatória de senha, Clientes PF/PJ, Veículos, Serviços e Ordens de Serviço.
- Sessão Spring Security real com cookie HttpOnly, `credentials: include` e CSRF dinâmico em todas as mutações, inclusive login e logout.
- Consultas e mutações centralizadas com TanStack Query; listagem de veículos consolidada por cliente na camada de API.
- Abertura de OS somente com cliente, veículo pertencente ao cliente e quilometragem; status `ABERTA` recebido do backend.
- Detalhe da OS com snapshots de serviços, inclusão pelo catálogo real, invalidação da consulta e subtotal local apenas para apresentação.
- CSS próprio desktop-first alinhado ao Figma, sem Tailwind e sem dados fictícios em runtime.
- Vitest/jsdom/Testing Library configurados, com cobertura dos fluxos críticos do frontend.
- Vite proxy `/api` para `http://localhost:8080`.

## Correções da revisão externa do frontend

- Checkpoint: `FRONTEND_CORRECTIONS_READY_FOR_REVIEW`; correções implementadas, aguardando revisão externa focada. Task permanece aberta.
- `REV-FE-001`: busca por nome case-insensitive corrigida; busca documental exige dígitos; CPF/CNPJ formatados e busca vazia cobertos por testes de interface.
- `REV-FE-002`: 401 de API protegida encerra sessão local, cancela queries e limpa cache; logout 401 tratado como sessão encerrada; falhas de rede/servidor exibidas com possibilidade de tentar novamente. Respostas de sessão anterior são descartadas. Restauração de sessão e troca obrigatória preservadas, sem armazenamento persistente de autenticação.
- `REV-FE-003`: estados loading/error/empty e retry nas consultas auxiliares de clientes, veículos, resumo da OS e catálogo; cliente sem veículo recebe orientação e caminho para cadastro existente. Ações dependentes são protegidas.
- `REV-FE-004`: removido noCheck; tipos de entrada/saída Zod/RHF corrigidos; imports explícitos no setup Vitest; checagem TypeScript efetiva habilitada no build.
- `REV-FE-005`: chaves compartilhadas para lista de OS e veículos por cliente; invalidação após criação de OS e cadastro de veículo, com regressões usando cache previamente populado e ainda fresco.
- `REV-FE-006`: testes de formulários renderizados, quilometragem zero/vazia, payload, navegação, troca de cliente, inclusão/refetch de serviço e subtotal de múltiplos snapshots; regressões de sessão e estados auxiliares.
- Gates em 2026-09-11: `npm test -- --run` — 45 testes PASS, 0 failures, 3 arquivos; `npm run build` — PASS com TypeScript real; `npx tsc -p tsconfig.app.json --noEmit` — exit 0; `npm run lint` — exit 0, 0 warnings; `git diff --check` — PASS.
- Java, migrations e pom.xml permanecem sem diferenças, inclusive na comparação com o baseline backend aprovado. Sem dependências novas, alterações de contratos backend, commit ou push.
- Nenhuma Decision Request necessária para estas correções. Encerramento independente dos findings depende da próxima revisão.

## Pendências da Task

- Revisão externa focada pós-correção do frontend.
- Execução integrada com backend e frontend reais.
- Validação visual e teste operacional pelo navegador.
- Primeiro lançamento fictício de OS.
- Commit/push após revisão e autorização.

## Findings iniciais

- Não existem especificações aprovadas próprias para Cliente, Veículo, Serviço ou abertura de OS no snapshot atual.
- TASK-0001 aprova a existência de OS antes do orçamento, mas não define seu estado inicial.
- Nenhum documento vigente define o escopo da unicidade documental.
- A revisão AG-01/AG-03 confirmou a regra aprovada de garantia padrão geral de 90 dias, alterável por serviço; base de comissão é o valor-base e não requer coluna separada.

## Histórico

- 2026-09-10 — Task criada, critérios consolidados e implementação parcial iniciada; DRs abertas sem bloquear os módulos independentes.
- 2026-09-10 — Serviço mínimo implementado com V3, API autenticada, CSRF e testes PostgreSQL; suíte completa verde (36 testes). Task bloqueada por DR-0004/DR-0005, sem declarar backend completo.
- 2026-09-10 — Owner decidiu DR-0004 e DR-0005; bloqueios removidos e Task retomada em `IN_PROGRESS`, preservando o catálogo já implementado.
- 2026-09-10 — Backend e integração vertical implementados; testes específicos verdes; Task movida para `IMPLEMENTATION_REVIEW`, aguardando revisão externa e sem commit/push.
- 2026-09-10 — Revisão externa independente do backend `APPROVED`; checkpoint movido para `FRONTEND_PENDING`. A Task permanece aberta.
- 2026-09-10 — Frontend mínimo implementado e gates locais executados; checkpoint movido para `FRONTEND_IMPLEMENTATION_REVIEW`, sem declarar integração operacional concluída.
- 2026-09-11 — Corrigidos os seis findings MEDIUM da revisão externa; 45 testes PASS, TypeScript real/build/lint verdes, 0 warnings. Checkpoint movido para `FRONTEND_CORRECTIONS_READY_FOR_REVIEW`, sem concluir a Task e sem commit/push.
