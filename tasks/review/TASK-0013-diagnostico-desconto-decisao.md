# TASK-0013 — Diagnóstico, desconto no orçamento e registro da decisão do cliente

## Identificação

- Status: `REVIEW`
- Prioridade: `HIGH`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: Oficina — `AG-03`
- Responsável atual: `AG-15` (revisão independente)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Diagnóstico e orçamento da OS | diagnóstico técnico na OS; desconto por item quando permitido; totais calculados no backend a partir dos itens; preços preservados por versão comercial (já existente desde a TASK-0007) |
| Aprovação e reprovação de orçamento | registro interno da decisão com data/hora, canal e usuário; orçamento apresentado preservado; reprovação não exclui a OS; status da OS atualizado automaticamente |

## Decisões

- `DR-0013` (provisória): desconto em valor por item com permissão `QUOTE_DISCOUNT`, registro interno da decisão com `QUOTE_PRESENT`, regras automáticas de status desligáveis e restritas às etapas anteriores à execução.
- `DR-0007` aplicada ao desconto: bruto arredondado em duas casas com `HALF_UP`, desconto em duas casas e total = bruto − desconto.

## Regras implementadas

1. Diagnóstico editável só em etapa operacional; o primeiro registro guarda instante e autor e, em OS `ABERTA`, move para `EM_DIAGNOSTICO`.
2. Desconto é parte da condição comercial: alterar o desconto cria nova versão; mesmos termos reaproveitam a versão; desconto maior que o bruto é recusado (`QUOTE_DISCOUNT_EXCEEDS_ITEM_TOTAL`).
3. Desconto maior que zero exige `QUOTE_DISCOUNT` (`403 QUOTE_DISCOUNT_NOT_ALLOWED`); o banco garante desconto não negativo e com duas casas.
4. Registro interno segue as mesmas regras da decisão pública: revisão apresentada (`409 QUOTE_REVISION_NOT_PRESENTED`), dentro da validade, itens da revisão, não decididos, não obsoletos, validação completa antes de gravar e serialização pela versão do orçamento.
5. Uma decisão por versão comercial, qualquer que seja o canal (unicidade existente em `quote_decision`).
6. A submissão guarda o canal (`PUBLIC_LINK` ou `INTERNAL`); cada canal tem `CHECK` das evidências obrigatórias.
7. Status automático: apresentação → `AGUARDANDO_APROVACAO`; algum item aprovado → `APROVADA`; todos decididos e nenhum aprovado → `REPROVADA`. Só atua de `ABERTA` a `REPROVADA`; OS em execução ou encerrada não é movida. Toda mudança automática entra no histórico com o motivo.
8. O orçamento informa a OS por um contrato público (`WorkOrderCommercialEvents`), sem conhecer status nem histórico da OS.

## Contrato REST

- `PUT /api/work-orders/{id}/diagnosis` `{diagnosis}`
- `GET /api/work-order-statuses/automations`; `PUT /api/work-order-statuses/automations/{event}` `{enabled}`
- `POST /api/work-orders/{id}/quotes/{quoteId}/revisions` aceita `discount` por item
- `POST /api/work-orders/{id}/quotes/{quoteId}/revisions/{revisionId}/decisions` `{contactChannel, authorizedBy?, notes?, decisions[]}` — exige `QUOTE_PRESENT`
- Versões de item passam a expor `discountAmount`, `grossTotal` e `decision`; o item público expõe `discountAmount`.

## Persistência

- `V15__diagnosis_discount_internal_decision.sql`: diagnóstico na OS, `workorder.status_automation`, `discount_amount` no item do orçamento, permissão `QUOTE_DISCOUNT` para Dono e Gerente Administrativo, canal e evidências por canal na submissão de decisão.

## Frontend

- OS: cartão de diagnóstico.
- Orçamento: coluna de desconto e de decisão, campo de desconto bloqueado sem permissão, cartão para registrar a decisão do cliente (canal, quem autorizou, observações, por item ou "aprovar/reprovar todos").
- Página pública: exibe o desconto do item.
- Configuração de status: regras automáticas liga/desliga.

## Testes

- `Task0013DiagnosisDiscountDecisionIntegrationTest` (PostgreSQL, sessão IAM real): diagnóstico e regra desligada; desconto versionado, limite e permissão; apresentação e aprovação interna movendo a OS; reprovação total e OS em execução preservada; evidências por canal no banco.
- Frontend: `decision.test.tsx`.

## Fora do escopo

Desconto percentual ou no total do orçamento; desconto em item físico lançado diretamente na OS; retratação de decisão; notificação ao cliente.

## Histórico

- 2026-09-17 — Task criada a partir do Trello; backend, frontend e testes implementados; movida para `REVIEW`.
