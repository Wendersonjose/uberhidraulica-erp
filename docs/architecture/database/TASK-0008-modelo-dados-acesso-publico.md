# Modelo de dados — Acesso público e decisão

- Origem: `TASK-0008`
- Agente proprietário: `AG-10 — Banco de Dados`
- Migration: `V10__public_quote_decision.sql`
- Status: `APPROVED` para o escopo da TASK-0008
- Data: `2026-09-15`

Implementa as seções 38 a 65 do modelo aprovado da `TASK-0001` (REVISION 2), sem simplificação.

## 1. `workshop.public_quote_access`

Credencial pública limitada a **uma** apresentação.

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `token_digest` | `BYTEA` | SHA-256 do token; o token bruto nunca entra |
| `valid_until` | `TIMESTAMPTZ` | validade da credencial, distinta da validade comercial |
| `revoked_at` | `TIMESTAMPTZ` | nulo enquanto ativo |

```text
fk_public_quote_access_revision_quote   (quote_revision_id, quote_id) → quote_revision(id, quote_id)
uq_public_quote_access_token_digest     lookup único, sem varredura
uq_public_quote_access_scope            (id, quote_revision_id, quote_id), base da FK da submissão
ck_public_quote_access_validity         valid_until >= created_at
ck_public_quote_access_revocation       revoked_at >= created_at
ck_public_quote_access_digest_length    octet_length(token_digest) = 32
```

A checagem de tamanho do digest foi acrescentada ao DDL aprovado: ela garante, no banco, que ninguém
gravou ali outra coisa — um token em claro mais curto, por exemplo — por engano de implementação.

### Duas validades

```text
quote_revision.valid_until        prazo da proposta comercial
public_quote_access.valid_until   prazo máximo daquele link
```

A regra `access.validUntil <= revision.validUntil` é verificada **na aplicação**, porque um `CHECK` do
PostgreSQL não consulta outra tabela, e o modelo aprovado é explícito em não criar trigger só para
isso. O que o cliente vê é o menor dos dois.

## 2. `workshop.quote_decision_submission`

Submissão pública atômica, com as evidências.

```text
fk_quote_submission_access_scope   (public_quote_access_id, quote_revision_id, quote_id)
                                   → public_quote_access(id, quote_revision_id, quote_id)
uq_quote_submission_request        (public_quote_access_id, request_id) — idempotência
uq_quote_submission_scope          (id, quote_revision_id, quote_id) — base da FK da decisão
ck_quote_submission_document_type  CPF | CNPJ
ck_quote_submission_document_length 11 para CPF, 14 para CNPJ
ck_quote_submission_document_digits somente dígitos
ck_quote_submission_name            nome não em branco
ck_quote_submission_acceptance      explicit_acceptance = TRUE
```

A FK do triplo é o que impede um acesso emitido para `R1` registrar submissão declarando `R2`.

`explicit_acceptance = TRUE` como `CHECK`: uma submissão sem aceite não é um registro ruim, é um
registro que **não pode existir**. Abrir o link não decide, e o banco também sabe disso.

`ip_address` é `INET` e não texto, porque aceita IPv4 e IPv6 sem normalização manual e sem espaço
para lixo. `document_digits` e `document_length` foram acrescentados ao DDL aprovado; o dígito
verificador continua **fora** do banco, conforme a seção 56 do modelo e a seção 46 da revisão de
segurança.

## 3. `workshop.quote_decision`

Decisão individual consolidada.

```text
fk_quote_decision_submission_scope  (submission_id, quote_revision_id, quote_id)
                                    → quote_decision_submission(id, quote_revision_id, quote_id)
fk_quote_decision_presented_item    (quote_revision_id, quote_item_revision_id, quote_id)
                                    → quote_revision_item(...)
uq_quote_decision_item_revision     UNIQUE (quote_item_revision_id)
ck_quote_decision_type              APPROVE | REJECT
```

A segunda FK é o coração da integridade: ela prova, no banco, que o item **realmente fazia parte da
apresentação daquela submissão**. Não basta pertencer ao mesmo orçamento.

`UNIQUE (quote_item_revision_id)` implementa "uma decisão efetiva por versão comercial". `APPROVE` e
`REJECT` para a mesma versão não podem coexistir como fatos, e retratação não existe nesta Task.

### Pendente não é registro

`PENDING` **não** é gravado. Ausência de linha significa pendente. Persistir o estado pendente
exigiria criar e manter uma linha para cada item apresentado, que o cliente nunca decidiu, e abriria
a porta para o estado derivado divergir do fato.

## 4. Por que JDBC e não JPA neste trecho

O adapter destas três tabelas usa JDBC direto. `ip_address` é `INET` e `token_digest` é `BYTEA`:
mapeá-los por entidade exigiria conversores que existiriam apenas para contornar o mapeador, sem
ganho nenhum sobre três tabelas de escrita simples e imutável.

O restante do módulo continua em JPA, e `ddl-auto=validate` não se incomoda: ele confere entidades
contra tabelas, não o contrário.

## 5. Validação

`Task0008PublicQuoteDecisionIntegrationTest` confere o registro `success` da versão `10` em
`flyway_schema_history` e exercita por SQL direto, fora da aplicação:

```text
decisão sobre item que não estava na revisão da submissão  → fk_quote_decision_presented_item
segunda decisão para a mesma versão comercial              → uq_quote_decision_item_revision
aceite falso                                               → ck_quote_submission_acceptance
documento com comprimento incompatível                     → ck_quote_submission_document_length
```
