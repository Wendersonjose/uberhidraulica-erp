# Backend / API — TASK-0001 — Aprovação Parcial de Orçamento

## 1. Identificação

Task:

```text
TASK-0001
```

Requisito:

```text
REQ-ORC-001
```

Decision Request:

```text
DR-0001
```

Decisão:

```text
OPÇÃO B
```

Agente:

```text
AG-11 — Backend Spring
```

Status:

```text
BACKEND_CONTRACT_APPROVED
```

Revisão:

```text
2
```

Data:

```text
2026-09-08
```

Implementação Java:

```text
NÃO
```

---

## 2. Objetivo

Definir contratos finais para:

```text
apresentação;
consulta pública;
decisão pública;
reabertura;
histórico;
idempotência;
concorrência;
stale.
```

---

## 3. API pública

```http
GET /api/public/quotes/{token}

POST /api/public/quotes/{token}/decisions
```

---

## 4. API interna

```http
POST /api/work-orders/{workOrderId}/quotes

GET /api/work-orders/{workOrderId}/quotes/{quoteId}

GET /api/work-orders/{workOrderId}/quotes/{quoteId}/history

POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions

POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions/{revisionId}/present

POST /api/work-orders/{workOrderId}/quotes/{quoteId}/items/{itemId}/reopen
```

---

## 5. Casos de uso

```text
CreateQuoteUseCase

CreateQuoteRevisionUseCase

PresentQuoteRevisionUseCase

GetPublicQuoteRevisionUseCase

RegisterPublicQuoteDecisionsUseCase

ReopenRejectedQuoteItemUseCase

GetQuoteHistoryUseCase
```

---

## 6. PresentQuoteRevisionUseCase

Responsabilidade:

```text
DRAFT
→
PRESENTED
```

e criação do:

```text
PublicQuoteAccess.
```

---

## 7. DR-0001 na apresentação

Ao apresentar revisão:

para cada `QuoteItemRevision`, verificar se existe versão anterior do mesmo:

```text
QuoteItem.
```

---

## 8. Mesma ItemRevision reutilizada

Exemplo:

```text
R1 → A-v1

R2 → A-v1 + B-v1
```

Não tornar A-v1 stale.

---

## 9. Nova ItemRevision

Exemplo:

```text
R1 → A-v1

R2 → A-v2
```

Quando R2 se torna PRESENTED:

```text
A-v1 não aceita mais nova decisão.
```

---

## 10. DRAFT

Criar A-v2 dentro de uma revisão DRAFT:

```text
não bloqueia A-v1.
```

---

## 11. GET público

```http
GET /api/public/quotes/{token}
```

Fluxo:

```text
token
↓
digest
↓
PublicQuoteAccess
↓
validade/revogação
↓
QuoteRevision
↓
QuoteItemRevision
↓
decisões
↓
availability
↓
DTO.
```

---

## 12. Response

```json
{
  "revisionReference": "6fd9533c-1524-44b5-9fd5-a597bd3ae986",
  "revisionNumber": 2,
  "presentedAt": "2026-09-08T12:00:00Z",
  "validUntil": "2026-09-15T12:00:00Z",
  "items": [
    {
      "itemReference": "252401d7-4f01-4297-a088-6b917e19dfaa",
      "description": "Reparo da caixa de direção",
      "quantity": "1",
      "unitPrice": "500.00",
      "totalPrice": "500.00",
      "decisionStatus": "PENDING_APPROVAL",
      "decisionAvailability": "DECIDABLE"
    }
  ]
}
```

---

## 13. `validUntil` público

Representa:

```text
prazo efetivo do acesso público.
```

Calcular conceitualmente:

```text
min(
    QuoteRevision.validUntil,
    PublicQuoteAccess.validUntil
)
```

---

## 14. DecisionStatus

```text
PENDING_APPROVAL

APPROVED

REJECTED
```

---

## 15. DecisionAvailability

```text
DECIDABLE

ALREADY_DECIDED

SUPERSEDED
```

---

## 16. Item aprovado

```text
decisionStatus = APPROVED

decisionAvailability = ALREADY_DECIDED
```

---

## 17. Item rejeitado

```text
decisionStatus = REJECTED

decisionAvailability = ALREADY_DECIDED
```

---

## 18. Item substituído

```text
decisionStatus = PENDING_APPROVAL

decisionAvailability = SUPERSEDED
```

---

## 19. Token expirado

GET:

```text
410 Gone
PUBLIC_QUOTE_EXPIRED
```

Não retornar orçamento.

---

## 20. Token revogado/inexistente

```text
404 Not Found
PUBLIC_QUOTE_NOT_AVAILABLE
```

---

## 21. Request de decisão

```json
{
  "revisionReference": "6fd9533c-1524-44b5-9fd5-a597bd3ae986",
  "requestId": "4e4a0bd3-d562-48a3-94f0-b220695d2712",
  "customer": {
    "name": "José da Silva",
    "documentType": "CPF",
    "documentNumber": "00000000000"
  },
  "explicitAcceptance": true,
  "decisions": [
    {
      "itemReference": "252401d7-4f01-4297-a088-6b917e19dfaa",
      "decision": "APPROVE"
    }
  ]
}
```

---

## 22. Decisions

Aceitos:

```text
APPROVE
REJECT
```

---

## 23. Submissão vazia

```text
decisions = []
```

Resultado:

```text
400 VALIDATION_ERROR.
```

---

## 24. Item omitido

Não cria decisão.

Permanece:

```text
PENDING_APROVACAO
```

se ainda decidível.

---

## 25. POST — sequência

```text
1. validar DTO;

2. calcular digest do token;

3. buscar PublicQuoteAccess;

4. validar revogação;

5. validar validade do Access;

6. validar validade comercial;

7. validar revisionReference;

8. normalizar identidade;

9. validar aceite;

10. canonicalizar payload;

11. calcular payloadDigest;

12. verificar replay idempotente;

13. carregar itens da revisão;

14. validar ownership;

15. validar decisão existente;

16. validar stale comercial;

17. validar todos os itens antes de persistir;

18. criar Submission;

19. criar Decisions;

20. criar eventos/outbox;

21. commit;

22. retornar response.
```

---

## 26. Stale

Se existir versão comercial posterior do mesmo QuoteItem já:

```text
PRESENTED
```

resultado:

```text
409 Conflict
QUOTE_ITEM_REVISION_STALE.
```

---

## 27. DRAFT não gera stale

Se versão posterior existir somente em DRAFT:

```text
não gerar QUOTE_ITEM_REVISION_STALE.
```

---

## 28. Complemento

Se revisão posterior reutiliza exatamente a mesma:

```text
QuoteItemRevision
```

não gerar stale.

---

## 29. Idempotência

Escopo:

```text
(publicQuoteAccessId, requestId)
```

---

## 30. Replay

Mesmo payload canônico:

```text
200 OK
replayed = true.
```

---

## 31. Primeira submissão

```text
201 Created
replayed = false.
```

---

## 32. Reuso conflitante

Mesmo requestId, conteúdo diferente:

```text
409
IDEMPOTENCY_KEY_REUSED.
```

---

## 33. Concorrência DECIDE × PRESENT

A operação de:

```text
RegisterPublicQuoteDecisions
```

e:

```text
PresentQuoteRevision
```

precisa detectar concorrência referente ao mesmo QuoteItem.

---

## 34. Resultado permitido A

Decision consolida primeiro:

```text
A-v1 recebe decisão.
```

Depois:

```text
A-v2 é apresentada.
```

---

## 35. Resultado permitido B

A-v2 é apresentada primeiro:

```text
A-v1 fica stale.
```

Decision posterior:

```text
409 QUOTE_ITEM_REVISION_STALE.
```

---

## 36. Estratégia técnica

Primeira implementação deve combinar:

```text
transaction;
optimistic locking;
unique constraints;
revalidação de stale dentro da transaction.
```

---

## 37. Regra importante

Não validar stale somente antes de abrir a transação.

A condição deve permanecer válida no ponto de persistência/commit.

---

## 38. Banco

Usar modelo revisado AG-10 com:

```text
FKs compostas.
```

---

## 39. Cross-quote

Mesmo que Java possua bug, banco deve rejeitar:

```text
Revision A
+
ItemRevision B.
```

---

## 40. Erros finais

```text
VALIDATION_ERROR

PUBLIC_QUOTE_NOT_AVAILABLE

PUBLIC_QUOTE_EXPIRED

QUOTE_REVISION_NOT_AUTHORIZED

QUOTE_ITEM_NOT_IN_REVISION

QUOTE_ITEM_ALREADY_DECIDED

QUOTE_ITEM_REVISION_STALE

IDEMPOTENCY_KEY_REUSED

CONCURRENT_MODIFICATION

RATE_LIMIT_EXCEEDED

INTERNAL_ERROR
```

---

## 41. HTTP

```text
200 — GET / replay

201 — nova Submission

400 — validação

404 — token inválido/revogado

409 — conflito/stale/idempotência

410 — acesso expirado

429 — rate limit

500 — erro inesperado
```

---

## 42. Formato de erro

```json
{
  "code": "QUOTE_ITEM_REVISION_STALE",
  "message": "Este item foi atualizado. Recarregue o orçamento antes de continuar.",
  "correlationId": "bffab82c-04ea-41ab-8546-81c35ce0e64d"
}
```

---

## 43. Atomicidade

Todos os items são validados antes de consolidar a Submission.

---

## 44. Falha em um item

Request:

```text
A válido
B stale
```

Resultado:

```text
409
zero decisões novas.
```

---

## 45. Token

Nunca:

```text
logar;
persistir bruto;
retornar em erro.
```

---

## 46. CPF/CNPJ

Não logar documento completo.

---

## 47. Headers públicos

```http
Cache-Control: no-store
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
```

---

## 48. Segurança

`permitAll` somente nas rotas públicas exatas.

Não utilizar:

```text
permitAll("/api/public/**")
```

indiscriminadamente.

---

## 49. DTOs

```text
PublicQuoteResponse

RegisterQuoteDecisionsRequest

RegisterQuoteDecisionsResponse

QuoteDetailsResponse

QuoteHistoryResponse
```

---

## 50. Money

```text
BigDecimal.
```

---

## 51. Clock

```text
Clock
```

injetável.

---

## 52. Repositories

Ports:

```text
QuoteRepository

PublicQuoteAccessRepository

QuoteDecisionSubmissionRepository
```

---

## 53. Eventos

```text
QuoteRevisionPresented
QuoteItemApproved
QuoteItemRejected
QuoteItemReopened
```

---

## 54. Outbox

Mesma transação da mudança de domínio.

---

## 55. Testes obrigatórios novos

```text
A-v2 DRAFT não deixa A-v1 stale;

A-v2 PRESENTED deixa A-v1 stale;

complemento reutilizando A-v1 não deixa stale;

token válido não supera stale;

DECIDE vence PRESENT;

PRESENT vence DECIDE;

request contendo item stale sofre rollback completo.
```

---

## 56. LOW-01

O GET público desta Task não possui contrato para:

```text
veículo;
placa.
```

O frontend não deve depender desses dados.

Finding:

```text
RESOLVED
```

---

## 57. MEDIUM-01

```text
QuoteRevision.validUntil
=
validade comercial

PublicQuoteAccess.validUntil
=
validade da credencial
```

Finding:

```text
RESOLVED
```

---

## 58. HIGH-02

DR-0001 incorporada.

Finding:

```text
RESOLVED
```

---

## 59. Resultado

```text
TASK:
TASK-0001

STATUS:
BACKEND_CONTRACT_APPROVED

REVISION:
2

DR-0001:
INCORPORATED

STALE:
DEFINED

VALIDITIES:
DEFINED

CONCURRENCY DECIDE x PRESENT:
DEFINED

CROSS-QUOTE DATABASE PROTECTION:
INCORPORATED

JAVA:
NOT IMPLEMENTED

READY_FOR_FRONTEND:
YES

READY_FOR_QA:
YES
```

---

## 60. Regra final

Para consolidar uma decisão pública, não basta:

```text
TOKEN VÁLIDO.
```

É necessário:

```text
TOKEN
+
ESCOPO
+
REVISÃO
+
ITEM APRESENTADO
+
VALIDADE
+
VERSÃO AINDA DECIDÍVEL
+
IDENTIDADE
+
ACEITE
+
IDEMPOTÊNCIA
+
CONCORRÊNCIA
+
TRANSAÇÃO.
```

**A EXISTÊNCIA HISTÓRICA DE UMA VERSÃO NÃO SIGNIFICA QUE ELA CONTINUA ABERTA PARA NOVAS DECISÕES.**