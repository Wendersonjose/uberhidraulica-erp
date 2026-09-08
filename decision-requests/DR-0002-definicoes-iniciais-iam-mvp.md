# DR-0002 — Definições iniciais do IAM MVP

## 1. Identificação

```text
ID: DR-0002
Tipo: SECURITY
Status: DECIDED
Criado em: 2026-09-08
Criado por: AG-00 — Orquestrador
Última atualização: 2026-09-08
```

## 2. Task e origem

```text
Task principal: TASK-0003 — Fundação IAM
Módulo: IAM / Segurança e Auditoria
Origem da decisão: PROPRIETÁRIO DO PRODUTO
```

## 3. Problema e contexto

Era necessário definir o comportamento inicial de bootstrap, autorização, credenciais, sessões e o recorte da primeira entrega IAM antes de criar contratos ou persistência. A ausência dessas definições bloqueava a implementação sem suposições.

## 4. Motivo

- [x] regra de negócio ausente;
- [x] risco de segurança;
- [x] problema de persistência;
- [x] definição de escopo.

## 5. Opções e decisão

As alternativas foram apresentadas diretamente ao proprietário do produto. Em vez de reproduzir opções históricas não registradas, este documento consolida a decisão explícita recebida, que possui autoridade máxima conforme `AGENTS.md`.

```text
Opção escolhida: OUTRA — conjunto consolidado definido pelo proprietário
Necessita decisão do proprietário: SIM
Decisão já tomada: SIM
Task deve permanecer bloqueada pela DR: NÃO
```

## 6. Decisão final

### 6.1 Bootstrap do primeiro Dono

- o primeiro Dono será criado por bootstrap inicial;
- os dados virão de variáveis de ambiente/configuração segura;
- o bootstrap somente poderá criar o Dono quando ainda não existir nenhum usuário;
- a senha inicial nunca será gravada em migration, código-fonte ou Git;
- o primeiro login exigirá troca obrigatória da senha.

### 6.2 Exceções individuais

```text
INHERIT: usa a permissão definida pelo perfil.
ALLOW: concede individualmente permissão ausente no perfil.
DENY: retira individualmente permissão concedida pelo perfil.
Precedência: exceção individual explícita > perfil.
```

### 6.3 Senhas

- usuário autenticado poderá alterar a própria senha;
- o Dono poderá redefinir a senha de outro usuário;
- redefinição administrativa gerará credencial temporária e exigirá troca no próximo login;
- recuperação automática por e-mail ficará fora do MVP;
- senhas nunca serão armazenadas em texto puro.

### 6.4 Sessões

- Spring Security Session;
- cookie `HttpOnly`;
- cookie `Secure` em produção;
- somente uma sessão ativa por usuário;
- expiração após oito horas de inatividade.

### 6.5 Escopo da TASK-0003

Inclui backend de usuários, perfis, permissões, exceções, login, logout, sessão, autorização, troca de senha, redefinição administrativa, bootstrap e auditoria básica de IAM.

Exclui React, telas, recuperação por e-mail, MFA, SSO/OAuth e frontend em geral.

O MVP inicial atende uma única oficina. A TASK-0003 não inclui conceito `Tenant`, `tenant_id`, contexto de tenant, autenticação/autorização por tenant ou arquitetura multi-tenant. Evolução para múltiplas oficinas dependerá de Task e decisão próprias.

## 7. Impactos

```text
Funcional: fluxo de acesso e administração de identidade.
Segurança: autenticação, autorização, sessão, credenciais e auditoria.
Arquitetura: módulo IAM e contratos públicos.
Persistência: usuários, perfis, permissões, credenciais, sessões e auditoria.
Infraestrutura: secrets, HTTPS e atributo Secure em produção.
Financeiro/estoque/fiscal/integrações externas: nenhum.
```

## 8. Agentes envolvidos

```text
AG-00, AG-01, AG-02, AG-09, AG-10, AG-11, AG-13, AG-14 e AG-15.
AG-12: não participa, por exclusão explícita de frontend.
```

## 9. Responsável e data

```text
Responsável pela decisão final: Proprietário do produto
Agente: PROPRIETÁRIO DO PRODUTO
Data: 2026-09-08
```

## 10. Regras resultantes e artefatos afetados

As cinco decisões desta DR são regras oficiais da `TASK-0003` e devem ser propagadas ao requisito, domínio/segurança, arquitetura, dados, API, testes, DevOps, handoffs e revisão.

```text
Requisito: REQ-SEG-001
Migration necessária na implementação: SIM
ADR separada: NÃO; não altera a arquitetura macro aprovada
Handoff: SIM
Risco residual: SIM — implementação incorreta de controles de segurança
Mitigação: revisão AG-09, testes AG-13 e revisão independente AG-15
```

## 11. Testes decorrentes

Bootstrap único e concorrente; primeiro login; troca obrigatória; autenticação válida/inválida; precedência `INHERIT/ALLOW/DENY`; redefinição administrativa; uma sessão; expiração; autorização backend; proteção CSRF; cookies; persistência PostgreSQL; auditoria e não exposição de segredos.

## 12. Encerramento

```text
Status final: DECIDED
Bloqueio funcional removido: SIM
Documentação da TASK-0003 atualizada: SIM
Decisão comunicada ao AG-00: SIM
```
