# Arquitetura — TASK-0001 — Aprovação Parcial de Orçamento

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
DR-0001 — Momento de obsolescência de versão comercial pendente
```

Decisão:

```text
OPÇÃO B
```

Agente:

```text
AG-02 — Arquitetura
```

Status:

```text
ARCHITECTURE_APPROVED
```

Revisão:

```text
2
```

Data:

```text
2026-09-08
```

---

## 2. Contexto arquitetural

A feature pertence ao módulo:

```text
Oficina
```

Subdomínio:

```text
Quote / Orçamento
```

Arquitetura do sistema:

```text
Monólito Modular
Spring Modulith
REST
PostgreSQL
Transactional Outbox
React
```

Não introduzir:

```text
microservices;
Kafka;
RabbitMQ;
Redis;
distributed lock;
CQRS completo.
```

---

## 3. Responsabilidade do módulo

O módulo Oficina é proprietário de:

```text
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
QuoteDecisionSubmission
QuoteDecision
PublicQuoteAccess
```

Outros módulos não devem acessar diretamente:

```text
repository;
JPA entity;
tabelas internas.
```

---

## 4. Modelo conceitual

```text
WorkOrder
   │
   └── Quote
        │
        ├── QuoteRevision
        │      │
        │      └── QuoteItemRevision
        │
        ├── QuoteItem
        │      │
        │      └── QuoteItemRevision
        │
        ├── PublicQuoteAccess
        │
        └── QuoteDecisionSubmission
                 │
                 └── QuoteDecision
```

---

## 5. Quote

Representa:

```text
identidade lógica do orçamento.
```

Não representa uma apresentação comercial específica.

---

## 6. QuoteRevision

Representa:

```text
uma apresentação global.
```

Estados:

```text
DRAFT
PRESENTED
```

---

## 7. QuoteItem

Representa:

```text
identidade lógica do item comercial.
```

---

## 8. QuoteItemRevision

Representa:

```text
snapshot de uma condição comercial específica.
```

Inclui:

```text
descrição;
quantidade;
preço.
```

---

## 9. Separação obrigatória

Não confundir:

```text
QuoteRevision
```

com:

```text
QuoteItemRevision.
```

Uma nova revisão global pode reutilizar versões comerciais antigas.

---

## 10. Complemento

Exemplo:

```text
R1
├── A-v1
└── B-v1

R2
├── A-v1
├── B-v1
└── C-v1
```

A criação/apresentação de R2 não invalida automaticamente:

```text
A-v1
B-v1
```

---

## 11. DR-0001

Regra oficial:

```text
DRAFT não invalida versão comercial anteriormente apresentada.
```

---

## 12. Substituição comercial

Quando:

```text
A-v2
```

do mesmo:

```text
QuoteItem A
```

for efetivamente incluída em uma:

```text
QuoteRevision PRESENTED
```

então:

```text
A-v1 deixa de aceitar novas decisões.
```

---

## 13. Histórico

Isso não altera decisões anteriores.

Exemplo:

```text
A-v1 APPROVED
```

continua:

```text
APPROVED
```

historicamente após A-v2 ser apresentada.

---

## 14. Stale é por item

Não utilizar:

```text
existe QuoteRevision mais nova
→ revisão inteira stale.
```

A análise correta é:

```text
existe QuoteItemRevision posterior
do mesmo QuoteItem
efetivamente apresentada?
```

---

## 15. API pública

Endpoints:

```http
GET /api/public/quotes/{token}

POST /api/public/quotes/{token}/decisions
```

---

## 16. API interna

Contratos:

```http
POST /api/work-orders/{workOrderId}/quotes

GET /api/work-orders/{workOrderId}/quotes/{quoteId}

GET /api/work-orders/{workOrderId}/quotes/{quoteId}/history

POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions

POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions/{revisionId}/present

POST /api/work-orders/{workOrderId}/quotes/{quoteId}/items/{itemId}/reopen
```

---

## 17. PublicQuoteAccess

O acesso público é vinculado exatamente a:

```text
Quote
+
QuoteRevision.
```

O token não funciona como autorização global.

---

## 18. Token

Token:

```text
opaco;
aleatório;
alta entropia;
256 bits recomendados.
```

Persistir somente:

```text
SHA-256(token bruto).
```

Nunca persistir:

```text
token bruto.
```

---

## 19. Validade comercial

Fonte:

```text
QuoteRevision.validUntil
```

Significa:

```text
prazo comercial da proposta apresentada.
```

---

## 20. Validade da credencial

Fonte:

```text
PublicQuoteAccess.validUntil
```

Significa:

```text
prazo técnico de utilização daquele token.
```

---

## 21. Relação entre validades

Regra:

```text
PublicQuoteAccess.validUntil
<=
QuoteRevision.validUntil.
```

O token nunca pode ampliar a validade comercial.

---

## 22. Decisão pública

Para aceitar nova decisão:

```text
acesso existe;
acesso não foi revogado;
acesso está válido;
revisão comercial está válida;
item pertence à revisão autorizada;
item ainda não possui decisão;
item não está comercialmente obsoleto.
```

---

## 23. Integridade estrutural

Após revisão AG-10, o banco utiliza:

```text
quote_id redundante controlado;
FOREIGN KEYS compostas;
UNIQUE compostos.
```

---

## 24. Proteções obrigatórias

O PostgreSQL deve impedir:

```text
Revision do Quote A
→ ItemRevision do Quote B

PublicAccess do Quote A
→ Revision do Quote B

Submission de R1
→ Access de R2

Decision em item não apresentado na Revision.
```

---

## 25. Defesa em profundidade

Aplicar:

```text
Domain validation
+
Application validation
+
PostgreSQL constraints.
```

---

## 26. Transação

Uma submissão pública é processada em:

```text
uma única transação PostgreSQL.
```

Inclui:

```text
QuoteDecisionSubmission;
QuoteDecision;
Outbox.
```

---

## 27. Atomicidade

Se request contém:

```text
A APPROVE
B REJECT
```

e B falhar:

```text
A também deve sofrer rollback.
```

---

## 28. Idempotência

Escopo:

```text
PublicQuoteAccess
+
requestId.
```

Mesmo conteúdo:

```text
replay.
```

Conteúdo diferente:

```text
409 Conflict.
```

---

## 29. Concorrência de decisão

Proteção:

```text
UNIQUE(quote_item_revision_id)
+
transaction
+
optimistic locking.
```

---

## 30. Concorrência crítica DR-0001

Cenário:

```text
DECIDE A-v1
×
PRESENT A-v2
```

As operações precisam possuir ordem efetiva.

---

## 31. Se DECIDE vencer

```text
A-v1 recebe decisão válida.
```

Depois:

```text
A-v2 pode ser apresentada.
```

A decisão de A-v1 permanece histórica.

---

## 32. Se PRESENT vencer

```text
A-v2 torna-se nova condição apresentada.
```

Tentativa posterior de decidir A-v1:

```text
QUOTE_ITEM_REVISION_STALE.
```

---

## 33. Ponto de serialização

A implementação deve coordenar ambas as operações utilizando o mesmo contexto lógico do:

```text
QuoteItem
```

ou mecanismo equivalente que garanta detecção de concorrência.

---

## 34. Mecanismo inicial

Preferir inicialmente:

```text
optimistic locking
+
constraints
+
transaction.
```

Lock pessimista somente se testes demonstrarem necessidade.

---

## 35. Eventos

Eventos conceituais:

```text
QuoteRevisionPresented
QuoteItemApproved
QuoteItemRejected
QuoteItemReopened
```

---

## 36. Outbox

Eventos intermodulares são gravados:

```text
na mesma transação
```

que os fatos de domínio correspondentes.

---

## 37. Evento sem PII desnecessária

Não incluir normalmente:

```text
CPF/CNPJ;
IP;
User-Agent;
token.
```

---

## 38. Segurança

Rotas públicas podem ser:

```text
permitAll
```

somente no sentido IAM.

Ainda exigem:

```text
validação de PublicQuoteAccess.
```

---

## 39. IDOR

Sempre validar:

```text
token
→ access
→ quote
→ revision
→ item revision.
```

---

## 40. Fonte de verdade

O navegador nunca define:

```text
preço;
descrição;
quantidade;
estado;
revisão efetiva.
```

---

## 41. Controller

Fluxo obrigatório:

```text
Controller
↓
Application
↓
Domain
↓
Repository Port
↓
Persistence Adapter
```

---

## 42. Proibido

```text
Controller → JpaRepository
```

---

## 43. Dados monetários

Java:

```text
BigDecimal
```

PostgreSQL:

```text
NUMERIC
```

---

## 44. Tempo

Usar:

```text
Clock
```

controlável.

---

## 45. Public GET

O contrato público não precisa exibir nesta Task:

```text
veículo;
placa;
dados administrativos da OS.
```

Isso elimina dependência não prevista pelo backend atual.

---

## 46. Dados públicos mínimos

GET público deve retornar somente:

```text
revisionReference;
revisionNumber;
presentedAt;
validUntil efetivo;
items.
```

---

## 47. Item público

Cada item contém:

```text
itemReference;
description;
quantity;
unitPrice;
totalPrice;
decisionStatus;
decisionAvailability.
```

---

## 48. DecisionStatus

```text
PENDING_APPROVAL
APPROVED
REJECTED
```

---

## 49. DecisionAvailability

```text
DECIDABLE
ALREADY_DECIDED
SUPERSEDED
```

Expiração global do acesso é tratada no endpoint, não como estado do item.

---

## 50. Token expirado

GET e POST:

```text
410 Gone
PUBLIC_QUOTE_EXPIRED
```

Sem retornar detalhes comerciais.

---

## 51. Token inválido/revogado

```text
404 Not Found
PUBLIC_QUOTE_NOT_AVAILABLE
```

---

## 52. Stale de item

Se token ainda for válido, mas o cliente tentar decidir versão substituída:

```text
409 Conflict
QUOTE_ITEM_REVISION_STALE
```

---

## 53. Frontend

Frontend deve:

```text
mostrar SUPERSEDED como indisponível;
refazer GET após conflitos;
não reaplicar escolha antiga;
preservar requestId em retry técnico.
```

---

## 54. Banco

Modelo revisado pelo AG-10:

```text
DATA_REVISED
```

Finding:

```text
HIGH-01
```

Status arquitetural:

```text
RESOLVIDO
```

---

## 55. HIGH-02

Decision Request:

```text
DR-0001
```

Status:

```text
DECIDED
```

Finding:

```text
RESOLVIDO
```

---

## 56. MEDIUM-01

Duas validades foram semanticamente separadas.

Status:

```text
RESOLVIDO
```

---

## 57. LOW-01

Frontend não exigirá nesta Task:

```text
veículo;
placa.
```

no contrato público.

Status:

```text
RESOLVIDO
```

---

## 58. Testes arquiteturais futuros

Validar:

```text
módulos não acessam persistence adapters de Quote;

domain não depende de Spring MVC;

controller não acessa repository concreto;

outbox não é manipulado diretamente pelo domínio;

events são contratos intermodulares.
```

---

## 59. Handoff AG-02 → AG-11

```text
DR-0001 incorporada.

Implementar stale por QuoteItemRevision.

DRAFT não invalida.

PRESENTED de nova versão do mesmo item invalida
a anterior para novas decisões.

GET público precisa expor availability do item.

POST deve devolver QUOTE_ITEM_REVISION_STALE.

Decision x Present exige proteção concorrente.

Duas validades são distintas.

Banco revisado com integridade composta.
```

---

## 60. Handoff AG-02 → AG-13

Adicionar testes para:

```text
DRAFT não invalida;

PRESENTED invalida versão anterior;

complemento reutilizando mesma versão;

token válido + item stale;

DECIDE vence PRESENT;

PRESENT vence DECIDE;

cross-quote bloqueado no PostgreSQL.
```

---

## 61. Resultado

```text
TASK:
TASK-0001

STATUS:
ARCHITECTURE_APPROVED

REVISION:
2

DR-0001:
INCORPORATED

HIGH-01:
RESOLVED

HIGH-02:
RESOLVED

MEDIUM-01:
RESOLVED

LOW-01:
RESOLVED

READY_FOR_BACKEND:
YES

READY_FOR_AG15_REREVIEW:
AFTER CONTRACT/QA UPDATE
```

---

## 62. Regra final

A arquitetura precisa preservar simultaneamente:

```text
HISTÓRICO
+
SEGURANÇA
+
INTEGRIDADE
+
CONCORRÊNCIA.
```

Uma versão comercial antiga pode continuar existindo no histórico sem continuar autorizada para novas decisões.

```text
DRAFT
→ não substitui

PRESENTED nova versão do mesmo item
→ substitui para novas decisões
```

**REVISÃO GLOBAL E VERSÃO COMERCIAL DO ITEM SÃO CONCEITOS DIFERENTES E NÃO DEVEM SER COLAPSADOS.**