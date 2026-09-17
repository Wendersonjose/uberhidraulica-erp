# TASK-0012 — Fluxo da OS: abertura, status configuráveis, Kanban, execução, entrega e cancelamento

## Identificação

- Status: `REVIEW`
- Prioridade: `CRITICAL`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: Oficina — `AG-03`
- Responsável atual: `AG-15` (revisão independente)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Abertura de ordem de serviço | cliente e veículo ativos, veículo do cliente, reclamação obrigatória, quilometragem e observações opcionais, número próprio e instante automático, status inicial configurado |
| Kanban e status das ordens de serviço | quadro por status configurado, histórico de toda mudança, colunas encerradas preservadas e filtradas por período |
| Execução, finalização e entrega da OS | iniciar execução, finalizar e registrar entrega com instante e autor; OS finalizada não aceita novos itens |
| Cancelamento de ordem de serviço | motivo obrigatório, instante e autor; sem retorno ao fluxo; dados preservados |
| Configuração do fluxo e status da OS | criar, renomear, ordenar, inativar/reativar status; status padrão por etapa; status usado nunca excluído |

## Decisões

- `DR-0012` (provisória): etapas fixas com status configuráveis, movimentação manual restrita às etapas operacionais, sem reabertura; supera o "sem workflow" da `DR-0004`.
- A quilometragem de entrada deixou de ser obrigatória e a reclamação passou a ser, conforme o cartão de abertura — altera o critério 9 da TASK-0004.

## Regras implementadas

1. Nove etapas fixas; a carga inicial cria um status padrão por etapa com identificadores estáveis.
2. Cada etapa tem exatamente um status padrão ativo (índice único parcial e `CHECK`).
3. Regras automáticas: abertura → padrão de `ABERTA`; iniciar execução → padrão de `EM_EXECUCAO`; finalizar → `FINALIZADA`; entregar → `ENTREGUE`; cancelar → `CANCELADA`.
4. Movimentação manual somente entre status ativos de etapas operacionais; `FINALIZADA`, `ENTREGUE` e `CANCELADA` só pelas ações próprias.
5. Execução não inicia a partir de `REPROVADA`; finalizar exige `EM_EXECUCAO`; entregar exige `FINALIZADA`; cancelar exige etapa operacional.
6. Mudanças de status e ações serializadas por bloqueio pessimista da OS.
7. Histórico com status de origem e destino, instante, usuário, motivo e indicação de automático; OS existentes ganharam o registro de abertura na migration.
8. Status padrão não pode ser inativado; status inativo não recebe OS, mas as OS que já estão nele continuam no quadro.
9. Serviços e itens físicos só são lançados em OS em etapa operacional (`409 WORK_ORDER_CLOSED`).
10. Kanban limita as colunas de encerramento às OS encerradas nos últimos `closedDays` dias (padrão 30).

## Contrato REST

- `POST /api/work-orders` aceita `complaint` (obrigatório), `notes` e `entryMileage` opcionais.
- `PUT /api/work-orders/{id}` atualiza quilometragem, reclamação e observações.
- `POST /api/work-orders/{id}/status` `{statusId, reason?}`; `POST .../start-execution`, `.../finish`, `.../deliver`, `.../cancel {reason}`.
- `GET /api/work-orders/{id}/status-history`; `GET /api/work-orders/board?closedDays=`.
- `GET|POST /api/work-order-statuses`, `PUT /api/work-order-statuses/{id}`, `POST .../inactivate|reactivate|make-default`, `PUT /api/work-order-statuses/order`.
- A resposta da OS mantém `status` como a etapa (compatível com `ABERTA`) e acrescenta `statusInfo`, `complaint`, `notes` e `lifecycle`.

## Persistência

- `V14__work_order_workflow.sql`: `workorder.status` com carga inicial, `status_id` na OS (substitui a coluna `status`), campos de reclamação, observações e ciclo de vida com constraints de coerência, e `work_order_status_history`.

## Arquitetura

- `workorder` passou a depender de `iam` apenas por `CurrentUser`, para registrar autoria.

## Frontend

- Kanban com colunas configuradas, cartões com cliente, veículo e reclamação, e filtro do período das encerradas.
- Abertura com reclamação obrigatória, observações e somente veículos ativos.
- Detalhe com ações do fluxo, cancelamento com motivo, datas do ciclo e histórico de status; lançamentos ocultos em OS encerrada.
- Página de configuração de status: criar, renomear, ordenar, tornar padrão, inativar e reativar.

## Testes

- `Task0012WorkOrderWorkflowIntegrationTest` (PostgreSQL): autenticação/CSRF, abertura e histórico, cliente/veículo inativos, movimentação manual e seus limites, execução → finalização → entrega com bloqueio de itens, cancelamento, configuração de status com padrão e ordem, Kanban com período, constraints.
- Testes das Tasks 0004, 0006, 0007, 0008, 0009/0010 e 0011 ajustados para enviar reclamação e limpar o histórico.
- Frontend: `workflow.test.tsx` e ajustes em `corrections`, `app` e `work-order-products`.

## Fora do escopo

Regras automáticas de aprovação/reprovação do orçamento (Task do orçamento); reabertura de OS finalizada; arrastar e soltar no Kanban; permissões específicas por ação (cartão de perfis e permissões); impressão/PDF.

## Histórico

- 2026-09-17 — Task criada a partir do Trello; backend, frontend e testes implementados; movida para `REVIEW`.
