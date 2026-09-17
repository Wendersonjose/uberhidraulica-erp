# TASK-0010 — Veículos: cadastro, busca, troca de proprietário, histórico e inativação

## Identificação

- Status: `REVIEW`
- Prioridade: `HIGH`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: CRM — `AG-03`
- Responsável atual: `AG-15` (revisão independente)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Cadastro de veículos | proprietário, placa, marca e modelo obrigatórios; ano, cor, quilometragem e observações opcionais |
| Listagem e busca de veículos | busca paginada por placa, marca, modelo e proprietário; filtro ativo/inativo; proprietário atual exibido |
| Atualização e troca de proprietário do veículo | edição de dados e transferência com histórico de propriedade |
| Histórico e inativação de veículos | histórico de OS e de proprietários; inativação e reativação sem exclusão física |

## Decisões

- `DR-0009` — oficina única (provisória).
- Ano do veículo passou a ser opcional, conforme o cartão; quando informado, entre 1900 e 2100.
- A FK composta `fk_work_order_vehicle_customer` foi substituída por `fk_work_order_vehicle`: com troca de proprietário, o par (veículo, cliente) deixa de ser permanente no cadastro. A OS guarda o cliente da abertura, e a pertinência continua verificada pela aplicação no momento da abertura.

## Regras implementadas

1. Todo veículo nasce ativo e com um período de propriedade aberto.
2. Placa normalizada e única entre todos os veículos, inclusive inativos.
3. A troca de proprietário bloqueia o veículo (`PESSIMISTIC_WRITE`), encerra o período atual e abre outro; o índice parcial `uq_crm_vehicle_ownership_current` garante no banco um único período aberto.
4. O novo proprietário deve existir e estar ativo; transferir para o próprio dono retorna `409 VEHICLE_ALREADY_OWNED_BY_CUSTOMER`.
5. OS existentes não são alteradas pela troca.
6. Veículo inativo continua consultável, com histórico; inativar/reativar repetido retorna `409`.
7. Autor da troca registrado em `changed_by` quando há usuário IAM autenticado.

## Contrato REST

- `GET /api/vehicles/search?q=&customerId=&active=&page=&size=` → itens `{vehicle, customerName}`
- `POST /api/vehicles` e `PUT /api/vehicles/{id}` aceitam `modelYear` opcional, `color` e `notes`
- `POST /api/vehicles/{id}/owner` com `{customerId}`
- `GET /api/vehicles/{id}/ownership-history`
- `POST /api/vehicles/{id}/inactivate` e `POST /api/vehicles/{id}/reactivate`
- `GET /api/work-orders?vehicleId=` para o histórico de serviços

## Persistência

- `V12__vehicle_status_ownership.sql`: ano anulável, cor, observações, `active`, tabela `crm.vehicle_ownership` com carga inicial dos vínculos existentes e troca da FK da OS.

## Arquitetura

- O módulo CRM passou a depender de `iam` apenas pelo contrato público `CurrentUser`, para registrar o autor da troca.
- `CustomerVehicleQuery.VehicleReference` expõe `active`, para que a OS possa recusar veículo inativo.

## Frontend

- Lista com busca no servidor, filtro de status e paginação.
- Cadastro (com proprietário pré-selecionado a partir do cliente) e edição.
- Detalhe com dados, proprietário, troca de proprietário com confirmação, histórico de proprietários e de OS, inativação e reativação.

## Testes

- `Task0009Task0010CrmIntegrationTest`: criação com campos opcionais e período de propriedade, troca preservando OS e histórico, conflitos, atualização, inativação, reativação, busca com filtros e paginação, e unicidade do período aberto no PostgreSQL.
- `Task0004VerticalIntegrationTest` ajustado para a nova FK.

## Fora do escopo

Grupos de veículos (cartão de preços por grupo, na Task de serviços); fotos e documentos do veículo; bloqueio de OS para veículo inativo (tratado na Task de OS).

## Histórico

- 2026-09-17 — Task criada a partir do Trello; backend, frontend e testes implementados; movida para `REVIEW`.
