# Contrato REST — Orçamento interno

- Origem: `TASK-0007`
- Base: `/api/work-orders/{workOrderId}/quotes`
- Autenticação: sessão Spring Security obrigatória
- CSRF: obrigatório nas mutações
- Status: `APPROVED` para o escopo da TASK-0007
- Data: `2026-09-15`

> Este é o contrato **interno**. O acesso público por token (`/api/public/quotes/{token}`) e a
> submissão de decisão do cliente não fazem parte desta Task.

## 1. Endpoints

```http
POST /api/work-orders/{workOrderId}/quotes
GET  /api/work-orders/{workOrderId}/quotes
GET  /api/work-orders/{workOrderId}/quotes/{quoteId}
GET  /api/work-orders/{workOrderId}/quotes/{quoteId}/history
POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions
POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions/{revisionId}/present
```

O `workOrderId` do caminho não é decorativo: todo acesso exige que o orçamento pertença àquela OS.
Um `quoteId` válido de outra OS responde `404`, não `403` — a resposta não confirma que o recurso
existe em outro lugar.

`POST .../items/{itemId}/reopen`, previsto na arquitetura da TASK-0001, **não** foi implementado:
reabrir um item rejeitado só tem significado quando existe decisão, que pertence à Task seguinte.

## 2. `POST /quotes`

Sem corpo. Cria a identidade lógica do orçamento, sem nenhuma revisão.

Resposta `201` com `Location` e o orçamento vazio. `404 WORK_ORDER_NOT_FOUND` quando a OS não existe.

## 3. `POST /quotes/{quoteId}/revisions`

```json
{
  "items": [
    { "quoteItemId": null, "workOrderServiceId": null,
      "description": "Recondicionamento da caixa", "quantity": 1, "unitPrice": "500.00" },
    { "quoteItemId": "…", "description": "Óleo ATF", "quantity": "2.500", "unitPrice": "42.90" }
  ]
}
```

Cria uma revisão em `DRAFT` com o próximo `revisionNumber`. Para cada item:

| `quoteItemId` | Efeito |
| --- | --- |
| ausente | cria um novo item comercial e sua versão 1 |
| informado, termos idênticos | **reaproveita** a versão atual — é o complemento da RN-22 |
| informado, termos diferentes | cria a versão seguinte do mesmo item, preservando a anterior |

"Termos" são exatamente descrição, quantidade e preço unitário. Qualquer diferença nesses três campos
cria versão nova; nada além deles cria.

`workOrderServiceId`, quando informado, precisa ser um serviço lançado **naquela** OS.

Limites de entrada: quantidade estritamente positiva com até três casas; preço unitário não negativo
com até duas casas. Ambos são persistidos em `NUMERIC(19,4)`.

Resposta `201` com o orçamento completo.

## 4. `POST /quotes/{quoteId}/revisions/{revisionId}/present`

Sem corpo. Move a revisão de `DRAFT` para `PRESENTED`, grava `presentedAt` e define
`validUntil = presentedAt + 7 dias`.

**A validade não é parametrizável.** REQ-ORC-001 seção 29 admite outro prazo apenas por configuração
aprovada, e nenhuma existe; aceitar o prazo por requisição deixaria a validade comercial na mão de
quem chama a API.

Exige pelo menos um item. Uma revisão de número inferior a outra já apresentada não pode ser
apresentada, para que a sequência do que o cliente recebeu permaneça legível.

A gravação é condicional à versão lida: duas apresentações concorrentes da mesma revisão não podem
ambas ter sucesso.

## 5. Representação do orçamento

```json
{
  "id": "…", "workOrderId": "…", "createdAt": "…", "createdBy": "…",
  "revisions": [
    { "id": "…", "revisionNumber": 1, "status": "PRESENTED",
      "presentedAt": "…", "validUntil": "…", "expired": false,
      "items": [ { "quoteItemRevisionId": "…", "displayOrder": 1 } ] }
  ],
  "items": [
    { "id": "…", "workOrderServiceId": null,
      "revisions": [
        { "id": "…", "revisionSequence": 1, "description": "…",
          "quantity": 1.0000, "unitPrice": 500.0000, "totalPrice": 500.0000,
          "presented": true, "availability": "AVAILABLE" }
      ] }
  ]
}
```

Itens saem na ordem em que o cliente os vê, derivada do `displayOrder` da apresentação.

### `availability`

Inteiramente derivado a cada leitura; **nenhuma coluna guarda obsolescência**, conforme a proibição de
`is_stale` no modelo aprovado.

| Valor | Significado |
| --- | --- |
| `AVAILABLE` | apresentada, não substituída e dentro da validade |
| `SUPERSEDED` | existe versão posterior do mesmo item efetivamente apresentada (RN-20) |
| `EXPIRED` | a apresentação que a contém passou da validade (RN-12) |
| `NOT_PRESENTED` | existe só em rascunho: o cliente nunca a recebeu (RN-19) |

`expired` na revisão é derivado de `validUntil` com o relógio do servidor. `EXPIRED` nunca é gravado
como situação.

## 6. `GET /quotes/{quoteId}/history`

Lista cronológica derivada do próprio dado, sem tabela de eventos:

```json
[ { "occurredAt": "…", "type": "REVISION_CREATED", "revisionNumber": 1 },
  { "occurredAt": "…", "type": "ITEM_REVISION_CREATED", "quoteItemId": "…", "revisionSequence": 1 },
  { "occurredAt": "…", "type": "REVISION_PRESENTED", "revisionNumber": 1 } ]
```

## 7. Erros

| Situação | HTTP | `code` |
| --- | --- | --- |
| Lista de itens vazia, descrição em branco, quantidade não positiva, preço negativo, escala excedida | `400` | `VALIDATION_FAILED` |
| Invariante de domínio violada | `400` | `INVALID_QUOTE_ITEM_REVISION` / `INVALID_QUOTE_REVISION` |
| Mesmo item comercial repetido na revisão | `400` | `QUOTE_ITEM_DUPLICATED` |
| Revisão sem item na apresentação | `400` | `QUOTE_REVISION_EMPTY` |
| OS inexistente | `404` | `WORK_ORDER_NOT_FOUND` |
| Orçamento inexistente ou de outra OS | `404` | `QUOTE_NOT_FOUND` |
| Revisão inexistente | `404` | `QUOTE_REVISION_NOT_FOUND` |
| Item comercial inexistente | `404` | `QUOTE_ITEM_NOT_FOUND` |
| Serviço informado não é da OS | `404` | `WORK_ORDER_SERVICE_NOT_FOUND` |
| Revisão já apresentada | `409` | `QUOTE_REVISION_ALREADY_PRESENTED` |
| Revisão anterior à última apresentada | `409` | `QUOTE_REVISION_OUT_OF_ORDER` |
| Revisão alterada entre leitura e gravação | `409` | `QUOTE_REVISION_CONCURRENTLY_MODIFIED` |
| Total exigiria decisão de arredondamento | `422` | `QUOTE_TOTAL_REQUIRES_ROUNDING_DECISION` |
| Sem sessão | `401` | `AUTHENTICATION_REQUIRED` |
| Sem token CSRF | `403` | `ACCESS_DENIED` |

O `422` é o único caso em que o sistema recusa um pedido correto: quantidade e preço gerariam um total
com mais de quatro casas decimais, e a regra de arredondamento comercial ainda não foi decidida
(`DR-0006`, `DR-0007`). Arredondar por conta própria mudaria o valor cobrado do cliente.

## 8. Ausências deliberadas

```text
DELETE de orçamento, revisão ou item
PUT de versão comercial já criada
descarte de rascunho
reabertura de item
acesso público por token
decisão do cliente
total do orçamento calculado pelo backend
evento de domínio e outbox
permissão granular de orçamento
```
