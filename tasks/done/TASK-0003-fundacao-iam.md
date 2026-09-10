# TASK-0003 — Fundação IAM: autenticação, usuários, perfis e permissões

## 1. Identificação

```text
ID: TASK-0003
Tipo: FEATURE BACKEND
Prioridade: HIGH
Status: DONE
Fase atual: CLOSED
Implementação: IMPLEMENTED
Revisão externa: APPROVED
Decision Requests abertas: 0
Data de criação: 2026-09-08
```

## 2. Objetivo

Implementar o backend IAM do MVP: identidade, autenticação, autorização, sessão, credenciais e auditoria básica.

## 3. Contexto

A `TASK-0002` entregou Spring Boot/PostgreSQL/Flyway. IAM é o próximo item da ordem aprovada e antecede módulos funcionais.

## 4. Módulo proprietário

`IAM / Segurança e Auditoria`.

## 5. Agente responsável

`AG-09 — Segurança & Auditoria`.

## 6. Agentes envolvidos

AG-00, AG-01, AG-02, AG-09, AG-10, AG-11, AG-13, AG-14 e AG-15. AG-12 não participa: frontend foi explicitamente excluído.

## 7. Requisitos relacionados

`REQ-SEG-001 — Fundação IAM do MVP — APPROVED`.

## 8. Decisões relacionadas

`DR-0002 — Definições iniciais do IAM MVP — DECIDED` e `DR-0003 — Complementos operacionais do IAM MVP — DECIDED`, por decisões explícitas do proprietário.

## 9. Regras de negócio

1. Primeiro Dono por bootstrap seguro somente quando não existe usuário.
2. Senha inicial fora de migration/código/Git e troca obrigatória no primeiro login.
3. `INHERIT` usa perfil; `ALLOW` concede; `DENY` retira; exceção explícita prevalece.
4. Usuário autenticado troca a própria senha; Dono redefine senha alheia com credencial temporária e troca obrigatória.
5. Senha nunca é persistida em texto puro; recuperação por e-mail está fora do MVP.
6. Spring Security Session, cookie `HttpOnly`, `Secure` em produção, uma sessão por usuário e oito horas de inatividade.
7. Autorização é aplicada no backend.
8. Novo usuário recebe credencial temporária exibida uma vez, somente hash persistido e troca obrigatória.
9. Bootstrap garante perfil `DONO`, permissões administrativas IAM, associações e primeiro Dono de forma consistente.
10. Existem somente os perfis fixos `DONO`, `GERENTE_ADMINISTRATIVO` e `GERENTE_FINANCEIRO`; códigos não são criados, excluídos, renomeados ou transformados.
11. Usuários possuem estado `ACTIVE|INACTIVE`; inativação encerra sessão e não apaga histórico.
12. O último Dono `ACTIVE` não pode ser inativado, inclusive sob concorrência.

## 10. Pré-condições

`TASK-0002` concluída, configuração segura para bootstrap e PostgreSQL disponível na implementação/testes.

## 11. Fluxo principal

```text
bootstrap → primeiro login → troca obrigatória → sessão → autorização → administração IAM → auditoria
```

## 12. Fluxos alternativos

Usuário existente torna bootstrap sem criação; novo login invalida sessão anterior; exceção explícita substitui perfil; Dono autorizado redefine credencial alheia.

## 13. Exceções

Credencial inválida não cria sessão; bootstrap inválido não cria registro parcial; ausência de sessão/permissão nega acesso; troca obrigatória restringe operações; oito horas inativas expiram a sessão.

## 14. Critérios de aceite

`CA-IAM-001` a `CA-IAM-039`, em `docs/requirements/security/REQ-SEG-001-fundacao-iam.md`.

## 15. Dependências

`TASK-0002 — DONE`, `DR-0002 — DECIDED` e `DR-0003 — DECIDED`.

## 16. Impacto em outros módulos

Módulos futuros consomem somente contratos públicos de identidade/autorização, sem acesso a internals IAM.

O MVP atende uma única oficina. Esta Task não introduz `Tenant`, `tenant_id`, contexto ou resolução de tenant, autenticação/autorização por tenant ou abstração SaaS multi-tenant.

## 17. Impacto financeiro

`NÃO`.

## 18. Impacto de estoque

`NÃO`.

## 19. Impacto fiscal

`NÃO`.

## 20. Segurança

Hash adaptativo aprovado, CSRF, proteção contra fixation/enumeração, cookies seguros por ambiente, secrets externos e ausência de segredo em logs/respostas persistentes.

## 21. Auditoria

Bootstrap, login, logout, troca/reset de senha, usuário, perfil, permissão e exceção são auditáveis sem senha/hash/cookie/credencial temporária.

## 22. Persistência

Usuários, perfis, permissões, associações, exceções, credenciais, sessão e auditoria foram implementados pela migration Flyway `V2__iam_foundation.sql`, validada em PostgreSQL real.

## 23. Concorrência

Bootstrap e logins simultâneos preservam um primeiro Dono e uma sessão efetiva por usuário. Inativações concorrentes preservam ao menos um Dono `ACTIVE`.

## 24. Eventos de domínio

Fatos conceituais de bootstrap, usuário, perfil/permissão, senha, login e logout, sem segredo. Outbox não é exigida.

## 25. Integrações externas

`NENHUMA`.

## 26. API

Contrato conceitual para login, logout, sessão, troca própria, reset administrativo, usuários, perfis, permissões e exceções.

## 27. Frontend

`FORA DO ESCOPO`. Nenhuma decisão de interface será inventada. Uma futura Task de frontend IAM/login deverá consultar o Figma aprovado antes da implementação.

## 28. Testes obrigatórios

Domínio, aplicação, API, segurança, sessão, concorrência, PostgreSQL/Testcontainers, auditoria e arquitetura.

## 29. Casos de teste mínimos

Login válido/inválido; `INHERIT/ALLOW/DENY`; sessão única/expiração; bootstrap administrativo; criação com temporária; perfis fixos; `ACTIVE/INACTIVE`; último Dono; troca própria; reset; acesso negado; CSRF; cookies e auditoria.

## 30. Riscos

Vazamento de credencial, bootstrap duplicado, precedência incorreta, múltiplas sessões, bypass e auditoria inadequada.

## 31. Fora do escopo

React, telas, recuperação por e-mail, MFA, SSO/OAuth, frontend em geral e multi-tenancy. Evolução para múltiplas oficinas exige Task e decisão próprias.

## 32. Arquivos e artefatos esperados

DR, requisito, domínio/segurança, arquitetura, dados, API, DevOps, código Java, migration Flyway, testes executáveis, handoffs e revisões de especificação/implementação.

## 33. Handoffs

Documentados em `tasks/handoffs/TASK-0003-handoffs-especificacao.md`:

```text
AG-00 → AG-01 → AG-09 → AG-02 → AG-10 → AG-11 → AG-13 → AG-14 → AG-15 → AG-00
```

## 34. Decision Requests

`DR-0002 — DECIDED`; `DR-0003 — DECIDED`. Abertas: `0`.

## 35. Checklist Definition of Ready

- [x] objetivo, módulo, escopo, requisito e critérios claros;
- [x] decisões, arquitetura, segurança e dados rastreados;
- [x] dependências/agentes identificados;
- [x] nenhuma DR impeditiva.

## 36. Checklist antes de IN_PROGRESS

- [x] estado READY atingido;
- [x] responsável e dependência definidos;
- [x] implementação de código explicitamente autorizada e iniciada.

## 37. Checklist antes de REVIEW

- [x] implementação concluída;
- [x] migration produzida e validada em PostgreSQL/Testcontainers;
- [x] testes executáveis aprovados (`31` testes, sem falhas, erros ou ignorados);
- [x] documentação pós-implementação atualizada.

## 38. Checklist Definition of Done

- [x] especificação aprovada;
- [x] backend e migration implementados;
- [x] testes executados;
- [x] revisão independente da implementação;
- [x] validação final AG-00.

## 39. Resultado da revisão técnica

```text
AG-15: APPROVED
Revisão externa: APPROVED
Findings impeditivos: 0
Apto para DONE: SIM
```

## 40. Histórico da Task

```text
Versão 1 — 2026-09-08 — especificação inicial baseada na DR-0002
Versão 2 — 2026-09-08 — complementos aprovados na DR-0003
Versão 3 — 2026-09-09 — backend IAM, migration V2 e suíte de 26 testes implementados
Versão 4 — 2026-09-09 — EXT-IAM-001..006 fechados e suíte ampliada para 30 testes
Versão 5 — 2026-09-09 — segunda revisão externa atendida com prova determinística de concorrência, paginação exaustiva e suíte reprodutível de 31 testes
Versão 6 — 2026-09-10 — terceira e final revisão externa APPROVED; validação final AG-00 e encerramento formal
```

## 41. Encerramento

```text
Status atual da Task: DONE
Fase atual: CLOSED
Implementação: IMPLEMENTED
Revisão externa: APPROVED
Decision Requests abertas: 0
```

Evidências de fechamento:

- `CA-IAM-001..039`: PASS;
- `31` testes;
- `0` failures;
- `0` errors;
- `0` skipped;
- `ApplicationModules.verify()`: PASS;
- findings externos fechados;
- nenhuma pendência técnica da `TASK-0003`.
