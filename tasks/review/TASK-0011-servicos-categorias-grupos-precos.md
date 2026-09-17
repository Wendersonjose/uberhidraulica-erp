# TASK-0011 — Serviços: categorias, busca, preços por veículo ou grupo e inativação

## Identificação

- Status: `REVIEW`
- Prioridade: `HIGH`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: Catálogo de Serviços — `AG-04`
- Responsável atual: `AG-15` (revisão independente)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Cadastro de serviços | nome obrigatório; descrição, categoria e preço base opcionais; nasce ativo |
| Listagem, busca e categorias de serviços | busca paginada por nome/descrição, filtro por categoria e status; cadastro de categorias |
| Preços de serviços por veículo ou grupo | grupos de veículos, preço por veículo e por grupo, sugestão com prioridade veículo → grupo → base |
| Atualização e inativação de serviços | edição, inativação e reativação; serviço inativo recusado em nova OS |

## Decisões

- `DR-0011` (provisória): categorias cadastradas, um grupo por veículo, prioridade veículo → grupo ativo → base, preço manual na OS.

## Regras implementadas

1. Categoria e grupo têm nome único sem diferenciar maiúsculas (`409 *_NAME_ALREADY_EXISTS`) e inativação lógica.
2. Categoria inativa não recebe novos serviços, mas permanece nos que já a usam.
3. Grupo inativo não recebe veículos e não participa da sugestão de preço.
4. Veículo em no máximo um grupo (chave primária em `vehicle_id`); adicionar a outro grupo move o veículo.
5. No máximo um preço por (serviço, veículo) e por (serviço, grupo), garantido por índices únicos parciais; exatamente um alvo por preço (`CHECK`).
6. Na OS, o serviço é lançado com o preço sugerido para o veículo da OS ou com o preço informado; a origem (`VEHICLE`, `GROUP`, `BASE`, `MANUAL`) fica gravada no snapshot, e mudanças posteriores de preço não alteram OS existentes.
7. Serviço sem preço aplicável exige preço manual (`400 SERVICE_PRICE_REQUIRED`); serviço inativo é recusado (`409 SERVICE_INACTIVE`).

## Contrato REST

- `GET /api/services/search?q=&categoryId=&active=&page=&size=`
- `POST /api/services/{id}/inactivate` e `/reactivate`
- `GET /api/services/{id}/prices`, `PUT /api/services/{id}/prices/vehicles/{vehicleId}`, `PUT /api/services/{id}/prices/groups/{groupId}`, `DELETE /api/services/{id}/prices/{priceId}`
- `GET /api/services/{id}/price-suggestion?vehicleId=`
- `GET|POST /api/service-categories`, `PUT /api/service-categories/{id}`, `POST .../inactivate|reactivate`
- `GET|POST /api/vehicle-groups`, `PUT /api/vehicle-groups/{id}`, `POST .../inactivate|reactivate`
- `GET /api/vehicle-groups/{id}/vehicles`, `PUT|DELETE /api/vehicle-groups/{id}/vehicles/{vehicleId}`, `GET /api/vehicles/{vehicleId}/group`
- `POST /api/work-orders/{id}/services` aceita `price` opcional; a resposta traz `priceSource`
- O pedido de serviço usa `categoryId`; a resposta mantém `category` (nome) e acrescenta `categoryId`.

## Persistência

- `V13__service_categories_groups_prices.sql`: `service_category` com migração das categorias em texto, `vehicle_group`, `vehicle_group_member`, `service_price`, descrição e preço base anuláveis, e `price_source`/descrição anulável no serviço da OS.

## Arquitetura

- `servicecatalog` passou a depender de `crm` apenas por `CustomerVehicleQuery` (validar veículo e listar veículos do grupo).
- `ServiceCatalogQuery.suggestPrice` é o contrato usado pela OS.

## Frontend

- Abas Serviços / Categorias / Grupos de veículos.
- Lista com busca, filtros e paginação; cadastro e edição; detalhe com preços por grupo e por veículo, e inativação.
- Grupos: criação, detalhe com veículos, busca para adicionar e remoção.
- OS: sugestão de preço com a origem e campo de preço praticado opcional.

## Testes

- `Task0011ServiceCatalogIntegrationTest` (PostgreSQL): serviço sem descrição/preço, categorias e conflitos, busca e inativação, prioridade veículo → grupo → base com grupo inativo, preço praticado preservado na OS, serviço inativo e sem preço, regras de grupo e constraints.
- `crm-services.test.tsx` no frontend, cobrindo também telas de Clientes e Veículos das Tasks 0009/0010.

## Fora do escopo

Histórico de alterações de preço; preço por cliente; importação de tabela de preços; permissões específicas para alterar preço.

## Histórico

- 2026-09-17 — Task criada a partir do Trello; backend, frontend e testes implementados; movida para `REVIEW`.
