# TASK-0001 — Aprovação Parcial de Orçamento

## Identificação

```text
ID:
TASK-0001

Módulo:
Oficina / Orçamento

Prioridade:
HIGH

Status:
SPECIFICATION_DONE

Implementação:
NOT_IMPLEMENTED

Data de conclusão da especificação:
2026-09-08
```

---

## Objetivo

Especificar ponta a ponta a aprovação parcial de orçamento.

---

## Resultado funcional

Cliente pode:

```text
aprovar item;

rejeitar item;

deixar item pendente.
```

Item omitido:

```text
permanece pendente.
```

---

## Regras principais

```text
1. decisão é individual por item;

2. decisões são históricas;

3. preço alterado exige nova versão;

4. descrição alterada exige nova versão;

5. quantidade alterada exige nova versão;

6. mudança interna não invalida aprovação;

7. complemento preserva versões não alteradas;

8. reabertura cria nova versão;

9. validade padrão comercial é 7 dias;

10. acesso é feito por token público seguro;

11. decisão exige nome, CPF/CNPJ e aceite explícito;

12. submission é idempotente;

13. submission com vários itens é atômica;

14. uma ItemRevision possui no máximo uma decisão efetiva.
```

---

## DR-0001

```text
Momento de obsolescência de versão comercial pendente
```

Decisão:

```text
OPÇÃO B
```

Regra:

```text
DRAFT não invalida versão anteriormente apresentada.

Nova versão comercial do mesmo item,
quando PRESENTED,
faz a anterior deixar de aceitar novas decisões.

Complemento com a mesma ItemRevision não invalida.
```

---

## Arquitetura

```text
Monólito modular
Spring Modulith
PostgreSQL
REST
Transactional Outbox
React
```

---

## Segurança

```text
token opaco de alta entropia;

raw token não persistido;

SHA-256 digest;

IDOR protegido;

dados públicos minimizados;

token/CPF não registrados em logs comuns.
```

---

## Banco

Proteções:

```text
FKs compostas;
quote_id controlado;
UNIQUE;
CHECK;
TIMESTAMPTZ;
INET;
BYTEA;
NUMERIC.
```

---

## API pública

```http
GET /api/public/quotes/{token}

POST /api/public/quotes/{token}/decisions
```

---

## Erro stale

```text
409
QUOTE_ITEM_REVISION_STALE
```

---

## Testes futuros

Obrigatórios:

```text
domain;

application;

PostgreSQL/Testcontainers;

API;

security;

idempotency;

atomicity;

concurrency;

outbox;

frontend;

E2E;

architecture.
```

---

## Agentes concluídos

```text
AG-00 — DONE

AG-01 — APPROVED

AG-02 — APPROVED

AG-03 — APPROVED

AG-09 — APPROVED

AG-10 — APPROVED FOR SPEC

AG-11 — APPROVED

AG-12 — APPROVED

AG-13 — APPROVED

AG-15 — APPROVED
```

---

## Findings AG-15

```text
HIGH-01:
CLOSED

HIGH-02:
CLOSED

MEDIUM-01:
CLOSED

LOW-01:
CLOSED
```

---

## Decision Requests abertas

```text
0
```

---

## Implementação

```text
Java:
NÃO

React:
NÃO

Flyway:
NÃO

Testes executáveis:
NÃO
```

---

## Definition of Done da especificação

- [x] requisito;
- [x] critérios de aceite;
- [x] domínio;
- [x] arquitetura;
- [x] segurança;
- [x] banco;
- [x] backend/API;
- [x] frontend;
- [x] QA;
- [x] revisão independente;
- [x] Decision Request resolvida;
- [x] findings corrigidos;
- [x] re-review AG-15;
- [x] encerramento AG-00.

---

## Resultado

```text
TASK-0001
SPECIFICATION_DONE
```

A feature está:

```text
PRONTA PARA FUTURA IMPLEMENTAÇÃO
```

e não:

```text
PRONTA PARA PRODUÇÃO.
```