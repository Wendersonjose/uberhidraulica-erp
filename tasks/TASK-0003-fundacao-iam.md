# TASK-0003 — Fundação IAM: autenticação, usuários, perfis e permissões

## 1. Identificação

```text
ID: TASK-0003
Tipo: FEATURE BACKEND
Prioridade: HIGH
Status: READY
Fase atual: SPECIFICATION APPROVED
Implementação: NOT_STARTED
Data de criação: 2026-09-08
```

## 2. Objetivo

Especificar o backend IAM do MVP: identidade, autenticação, autorização, sessão, credenciais e auditoria básica.

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

`DR-0002 — Definições iniciais do IAM MVP — DECIDED`, por decisão explícita do proprietário.

## 9. Regras de negócio

1. Primeiro Dono por bootstrap seguro somente quando não existe usuário.
2. Senha inicial fora de migration/código/Git e troca obrigatória no primeiro login.
3. `INHERIT` usa perfil; `ALLOW` concede; `DENY` retira; exceção explícita prevalece.
4. Usuário autenticado troca a própria senha; Dono redefine senha alheia com credencial temporária e troca obrigatória.
5. Senha nunca é persistida em texto puro; recuperação por e-mail está fora do MVP.
6. Spring Security Session, cookie `HttpOnly`, `Secure` em produção, uma sessão por usuário e oito horas de inatividade.
7. Autorização é aplicada no backend.

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

`CA-IAM-001` a `CA-IAM-025`, em `docs/requirements/security/REQ-SEG-001-fundacao-iam.md`.

## 15. Dependências

`TASK-0002 — DONE` e `DR-0002 — DECIDED`.

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

Usuários, perfis, permissões, associações, exceções, credenciais, sessão e auditoria. Somente especificação; migration não autorizada nesta fase.

## 23. Concorrência

Bootstrap e logins simultâneos preservam um primeiro Dono e uma sessão efetiva por usuário.

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

Login válido/inválido; `INHERIT/ALLOW/DENY`; sessão única/expiração; bootstrap; troca obrigatória; troca própria; reset pelo Dono; acesso negado; CSRF; cookies e auditoria.

## 30. Riscos

Vazamento de credencial, bootstrap duplicado, precedência incorreta, múltiplas sessões, bypass e auditoria inadequada.

## 31. Fora do escopo

React, telas, recuperação por e-mail, MFA, SSO/OAuth, frontend em geral e multi-tenancy. Evolução para múltiplas oficinas exige Task e decisão próprias.

## 32. Arquivos e artefatos esperados

DR, requisito, domínio/segurança, arquitetura, dados, API, testes, DevOps, handoffs e revisão. Java, migration e testes executáveis pertencem à fase futura.

## 33. Handoffs

Documentados em `tasks/handoffs/TASK-0003-handoffs-especificacao.md`:

```text
AG-00 → AG-01 → AG-09 → AG-02 → AG-10 → AG-11 → AG-13 → AG-14 → AG-15 → AG-00
```

## 34. Decision Requests

`DR-0002 — DECIDED`. Abertas: `0`.

## 35. Checklist Definition of Ready

- [x] objetivo, módulo, escopo, requisito e critérios claros;
- [x] decisões, arquitetura, segurança e dados rastreados;
- [x] dependências/agentes identificados;
- [x] nenhuma DR impeditiva.

## 36. Checklist antes de IN_PROGRESS

- [x] estado READY atingido;
- [x] responsável e dependência definidos;
- [ ] implementação de código explicitamente autorizada e iniciada.

## 37. Checklist antes de REVIEW

- [ ] implementação concluída;
- [ ] migration produzida;
- [ ] testes executáveis aprovados;
- [ ] documentação pós-implementação atualizada.

## 38. Checklist Definition of Done

- [x] especificação aprovada;
- [ ] backend e migration implementados;
- [ ] testes executados;
- [ ] revisão independente da implementação;
- [ ] validação final AG-00.

## 39. Resultado da revisão técnica

```text
AG-15: APPROVED para especificação
Findings impeditivos: 0
Apto para DONE: NÃO
Pronto para implementação: SIM
```

## 40. Histórico da Task

`Versão 1 — 2026-09-08 — especificação inicial baseada na DR-0002`.

## 41. Encerramento

```text
Status final da Task: READY
Fase de especificação: APPROVED
Implementação: NOT_STARTED
Pronta para implementação: SIM
```

A Task não é `DONE` porque a entrega de produção ainda não existe.
