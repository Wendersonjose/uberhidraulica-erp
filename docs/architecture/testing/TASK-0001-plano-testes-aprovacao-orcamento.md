# QA & Testes — TASK-0001 — Aprovação Parcial de Orçamento

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

Agente:

```text
AG-13 — QA & Testes
```

Status:

```text
QA_APPROVED
```

Revisão:

```text
2
```

Data:

```text
2026-09-08
```

Testes executados:

```text
NÃO
```

Motivo:

```text
simulação documental anterior à implementação.
```

---

## 2. Estratégia

Camadas:

```text
Domain
Application
Database
API
Security
Concurrency
Frontend
E2E
Architecture
```

---

## 3. Banco

Obrigatório futuramente:

```text
PostgreSQL
+
Testcontainers.
```

Não usar H2 como substituto.

---

## 4. Riscos críticos

```text
aprovação em versão errada;

aprovação em item de outro orçamento;

aprovação em versão substituída;

decisão duplicada;

falha de atomicidade;

retry duplicando decisão;

corrida DECIDE x PRESENT;

perda de histórico;

token vazado.
```

---

## 5. Testes de domínio

### DOM-01

```text
A approve
B approve
C omitido
```

Resultado:

```text
A approved
B approved
C pending.
```

### DOM-02

```text
A reject
```

Resultado:

```text
A rejected.
```

### DOM-03

Preço alterado:

```text
nova QuoteItemRevision.
```

### DOM-04

Descrição alterada:

```text
nova QuoteItemRevision.
```

### DOM-05

Quantidade alterada:

```text
nova QuoteItemRevision.
```

### DOM-06

Alteração de técnico:

```text
não cria nova versão comercial.
```

### DOM-07

Reabertura:

```text
rejeição anterior preservada
+
nova versão pending.
```

### DOM-08 — DR-0001 DRAFT

```text
A-v1 apresentada/pending
A-v2 criada somente DRAFT
```

Esperado:

```text
A-v1 continua decidível.
```

### DOM-09 — DR-0001 PRESENTED

```text
A-v2 torna-se PRESENTED
```

Esperado:

```text
A-v1 deixa de aceitar nova decisão.
```

### DOM-10 — histórico

A-v1 aprovada antes da apresentação de A-v2.

Esperado:

```text
A-v1 continua approved historicamente.
```

### DOM-11 — complemento

```text
R1 = A-v1
R2 = A-v1 + B-v1
```

Esperado:

```text
A-v1 não fica stale.
```

---

## 6. Application

### APP-01

Token válido resolve PublicQuoteAccess correto.

### APP-02

Token de revisão diferente falha.

### APP-03

Item omitido não gera decisão.

### APP-04

Submission vazia falha.

### APP-05

Aceite falso falha.

### APP-06

Mesmo requestId + mesmo payload:

```text
replay.
```

### APP-07

Mesmo requestId + payload diferente:

```text
conflict.
```

### APP-08

Item SUPERSEDED:

```text
QUOTE_ITEM_REVISION_STALE.
```

### APP-09

Nova versão somente DRAFT:

```text
não gera stale.
```

---

## 7. Banco — integridade

### DB-01

Revision do Quote A + ItemRevision do Quote B:

```text
FK violation.
```

### DB-02

PublicAccess Quote A + Revision Quote B:

```text
FK violation.
```

### DB-03

Submission com revision diferente do Access:

```text
FK violation.
```

### DB-04

Decision em item não apresentado naquela Revision:

```text
FK violation.
```

### DB-05

Mesmo revision_number:

```text
UNIQUE violation.
```

### DB-06

Mesmo requestId no mesmo Access:

```text
UNIQUE violation.
```

### DB-07

Duas decisões para mesma ItemRevision:

```text
UNIQUE violation.
```

### DB-08

Quantidade zero:

```text
CHECK violation.
```

### DB-09

Preço negativo:

```text
CHECK violation.
```

### DB-10

Aceite false:

```text
CHECK violation.
```

### DB-11

IPv4:

```text
sucesso.
```

### DB-12

IPv6:

```text
sucesso.
```

---

## 8. Atomicidade

Request:

```text
A válido
B inválido
```

Esperado:

```text
zero Submission nova;
zero Decisions novas;
zero Outbox events.
```

---

## 9. API

### API-01

GET token válido:

```text
200.
```

### API-02

GET token inválido:

```text
404 PUBLIC_QUOTE_NOT_AVAILABLE.
```

### API-03

GET token expirado:

```text
410 PUBLIC_QUOTE_EXPIRED.
```

### API-04

POST parcial:

```text
201.
```

### API-05

Replay:

```text
200 replayed=true.
```

### API-06

Mesmo requestId diferente:

```text
409.
```

### API-07

Item já decidido:

```text
409 QUOTE_ITEM_ALREADY_DECIDED.
```

### API-08

Item stale:

```text
409 QUOTE_ITEM_REVISION_STALE.
```

### API-09

decisions vazio:

```text
400.
```

---

## 10. Segurança

### SEC-01

Token inválido não revela OS.

### SEC-02

Token alterado falha.

### SEC-03

Token revogado falha.

### SEC-04

IDOR por item falha.

### SEC-05

IDOR por revision falha.

### SEC-06

Preço enviado pelo cliente não altera snapshot.

### SEC-07

Token bruto ausente nos logs.

### SEC-08

CPF/CNPJ integral ausente de logs comuns.

### SEC-09

Cache-Control no-store.

### SEC-10

Referrer-Policy no-referrer.

### SEC-11

XSS em descrição não executa.

### SEC-12

Token válido não supera item stale.

---

## 11. Concorrência

### CON-01

```text
APPROVE A-v1
×
APPROVE A-v1
```

Resultado:

```text
uma decisão.
```

### CON-02

```text
APPROVE A-v1
×
REJECT A-v1
```

Resultado:

```text
uma decisão efetiva.
```

### CON-03

Mesmo requestId simultâneo:

```text
uma Submission.
```

### CON-04 — DECIDE vence

```text
DECIDE A-v1
```

consolida antes de:

```text
PRESENT A-v2.
```

Resultado:

```text
A-v1 recebe decisão válida;
A-v2 posteriormente pending.
```

### CON-05 — PRESENT vence

```text
PRESENT A-v2
```

consolida antes da Decision antiga.

Resultado:

```text
A-v1 Decision falha com stale.
```

### CON-06

As duas operações não podem cometer ignorando a outra.

---

## 12. Outbox

### OUT-01

Approval gera:

```text
QuoteItemApproved.
```

### OUT-02

Reject gera:

```text
QuoteItemRejected.
```

### OUT-03

Rollback:

```text
zero evento.
```

### OUT-04

Replay:

```text
não duplica evento.
```

---

## 13. Frontend

### FE-01

Pendente mostra controles.

### FE-02

Aprovado não mostra controles.

### FE-03

Rejeitado não mostra controles.

### FE-04

SUPERSEDED não mostra controles.

### FE-05

DRAFT de nova versão não altera interface pública atual.

### FE-06

409 stale provoca refetch.

### FE-07

Complemento não marca item reutilizado como stale.

### FE-08

Timeout reaproveita requestId.

### FE-09

Item omitido não é enviado.

### FE-10

Checkbox de aceite inicia desmarcado.

### FE-11

Página mobile funcional.

### FE-12

Descrição com script não executa.

---

## 14. E2E-01 — aprovação parcial

```text
A approve
B approve
C omitido
```

Após reload:

```text
A approved
B approved
C pending.
```

---

## 15. E2E-02 — alteração comercial

```text
A-v1 pending
↓
A-v2 DRAFT
```

A-v1 ainda decidível.

Depois:

```text
A-v2 PRESENTED
```

A-v1:

```text
SUPERSEDED.
```

---

## 16. E2E-03 — complemento

```text
R1 = A-v1 approved

R2 = A-v1 + B-v1
```

Resultado:

```text
A-v1 continua approved
B-v1 pending.
```

---

## 17. E2E-04 — concorrência

Duas sessões controladas:

```text
cliente decide A-v1

gerente apresenta A-v2
```

Testar as duas ordens possíveis.

---

## 18. Testes arquiteturais

```text
Controller não acessa JpaRepository.

Domain não depende de HTTP.

Outro módulo não acessa persistence adapter de Quote.

Eventos intermodulares não carregam PII desnecessária.
```

---

## 19. Findings do AG-15

### HIGH-01

```text
integridade relacional
```

Cobertura:

```text
DB-01
DB-02
DB-03
DB-04
```

Status:

```text
READY_FOR_REREVIEW.
```

### HIGH-02

```text
stale
```

Cobertura:

```text
DOM-08
DOM-09
DOM-10
DOM-11
APP-08
APP-09
CON-04
CON-05
```

Status:

```text
READY_FOR_REREVIEW.
```

### MEDIUM-01

Duas validades:

```text
contrato consolidado.
```

Status:

```text
READY_FOR_REREVIEW.
```

### LOW-01

Frontend não depende mais de veículo/placa.

Status:

```text
READY_FOR_REREVIEW.
```

---

## 20. Gate

```text
CRITICAL GAPS:
0

HIGH GAPS:
0

BLOCKING DECISION REQUESTS:
0
```

---

## 21. Resultado

```text
TASK:
TASK-0001

STATUS:
QA_APPROVED

REVISION:
2

DR-0001:
INCORPORATED

HIGH-01:
COVERED

HIGH-02:
COVERED

MEDIUM-01:
COVERED

LOW-01:
COVERED

EXECUTABLE TESTS:
NOT YET

READY_FOR_AG15_REREVIEW:
YES
```

---

## 22. Regra final

Os testes futuros precisam provar não apenas que:

```text
aprovar funciona.
```

Precisam provar que:

```text
APROVAR NÃO FUNCIONA
QUANDO NÃO DEVERIA FUNCIONAR.
```

Especialmente em:

```text
stale;
IDOR;
concorrência;
retry;
falha transacional.
```

**A CORREÇÃO DA FEATURE É DEFINIDA TAMBÉM PELOS CENÁRIOS EM QUE O SISTEMA RECUSA UMA DECISÃO.**