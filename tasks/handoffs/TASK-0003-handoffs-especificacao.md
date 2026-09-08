# TASK-0003 — Handoffs da fase de especificação

## Identificação comum

```text
Task: TASK-0003 — Fundação IAM
Data: 2026-09-08
Status dos handoffs: COMPLETED
Decisão: DR-0002 — DECIDED
Requisito: REQ-SEG-001 — APPROVED
Frontend: fora do escopo; AG-12 não participa
Oficina: única no MVP; multi-tenancy fora do escopo
```

Todos os destinatários aceitaram as decisões do proprietário sem reinterpretá-las. Não há pendência impeditiva ou impacto financeiro, fiscal, estoque ou integração externa.

## HANDOFF-0003-01 — AG-00 → AG-01

**Objetivo:** transformar as cinco decisões aprovadas em requisito testável, delimitar backend-only e registrar fora do escopo.

**Entrada:** `AGENTS.md`, `DR-0002`, `TASK-0002`, decisões explícitas do proprietário.

**Resultado:** `REQ-SEG-001`, critérios `CA-IAM-001..025`, escopo e atores definidos.

```text
Aceite AG-01: ACCEPTED
Resultado: COMPLETED
Próxima ação: AG-09 validar domínio, segurança e auditoria.
```

## HANDOFF-0003-02 — AG-01 → AG-09

**Objetivo:** consolidar invariantes de autenticação, autorização, credenciais, sessão e auditoria.

**Regras centrais:** bootstrap único; troca obrigatória; `INHERIT/ALLOW/DENY`; sessão única de oito horas; reset pelo Dono; ausência de frontend/e-mail/MFA/SSO.

**Resultado:** `docs/domain/iam/fundacao-iam.md` e `docs/architecture/security/TASK-0003-seguranca-iam.md`.

```text
Aceite AG-09: SECURITY_APPROVED
Resultado: COMPLETED
Próxima ação: AG-02 definir fronteira e contratos.
```

## HANDOFF-0003-03 — AG-09 → AG-02

**Objetivo:** estruturar o módulo sem enfraquecer controles de segurança.

**Riscos:** bypass backend, CSRF, fixation, segredo em logs e acesso a internals.

**Resultado:** arquitetura modular, camadas, ports, contratos, transações, eventos e proibições em `docs/architecture/iam/TASK-0003-fundacao-iam.md`.

```text
Aceite AG-02: APPROVED
Resultado: COMPLETED
Próxima ação: AG-10 especificar persistência.
```

## HANDOFF-0003-04 — AG-02 → AG-10

**Objetivo:** desenhar persistência PostgreSQL sem criar migration.

**Entidades/conceitos:** User, Profile, Permission, ProfilePermission, UserPermissionException, Credential, Session e IamAuditEvent.

**Resultado:** relações, constraints, índices, histórico e concorrência em `docs/architecture/database/TASK-0003-modelo-dados-iam.md`.

```text
Aceite AG-10: DATA_APPROVED FOR SPECIFICATION
Resultado: COMPLETED
Próxima ação: AG-11 consolidar contrato backend/API.
```

## HANDOFF-0003-05 — AG-10 → AG-11

**Objetivo:** preparar a implementação posterior sem escrever código.

**Arquitetura:** Controller → Application → Domain → Repository Port → Persistence Adapter; Spring Security Session; PostgreSQL/Flyway.

**Resultado:** casos de uso e contrato REST conceitual em `docs/api/TASK-0003-contrato-rest-iam.md`.

```text
Aceite AG-11: SPECIFICATION_APPROVED
Implementação: NOT_STARTED
Resultado: COMPLETED
Próxima ação: AG-13 produzir plano de testes.
```

## HANDOFF-0003-06 — AG-11 → AG-13

**Objetivo:** derivar evidências testáveis dos critérios e riscos.

**Resultado:** plano unitário, integração, API, PostgreSQL/Testcontainers, concorrência, segurança, auditoria e arquitetura em `docs/architecture/testing/TASK-0003-plano-testes-iam.md`.

```text
Aceite AG-13: QA_PLAN_APPROVED
Testes de implementação: NOT_CREATED
Resultado: COMPLETED
Próxima ação: AG-14 validar impacto operacional.
```

## HANDOFF-0003-07 — AG-13 → AG-14

**Objetivo:** especificar secret injection, HTTPS, cookies e observabilidade sem alterar infraestrutura.

**Resultado:** `docs/architecture/devops/TASK-0003-operacao-segura-iam.md`.

```text
Aceite AG-14: DEVOPS_SPEC_APPROVED
Resultado: COMPLETED
Próxima ação: AG-15 revisar independentemente.
```

## HANDOFF-0003-08 — AG-14 → AG-15

**Objetivo:** revisão independente consolidada de decisão, requisito, segurança, arquitetura, dados, API, testes e operação.

**Entrada:** todos os artefatos listados na `TASK-0003`; implementation/migration/tests executáveis são N/A nesta fase.

**Resultado:** `docs/review/TASK-0003-revisao-especificacao.md`.

```text
Aceite AG-15: APPROVED
Resultado: COMPLETED
Próxima ação: AG-00 validar encerramento da fase documental.
```

## HANDOFF-0003-09 — AG-15 → AG-00

**Objetivo:** comunicar parecer independente e prontidão para implementação posterior.

```text
Findings impeditivos: 0
Decision Requests abertas: 0
Especificação pronta: SIM
Task: READY
Resultado: COMPLETED
```

AG-00 valida que a fase documental terminou e mantém a Task em `READY`. `IN_PROGRESS` somente será usado após autorização explícita e início da implementação; `DONE` depende de backend, migration, testes e revisão da implementação.
