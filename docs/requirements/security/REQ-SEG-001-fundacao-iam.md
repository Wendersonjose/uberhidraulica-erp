# REQ-SEG-001 — Fundação IAM do MVP

## Identificação

```text
ID: REQ-SEG-001
Versão: 1
Status: APPROVED
Task: TASK-0003
Decisão: DR-0002 — DECIDED
Módulo proprietário: IAM
Responsável funcional: AG-09
Data: 2026-09-08
```

## Problema e objetivo

O backend ainda não possui identidade, autenticação ou autorização para proteger os módulos funcionais futuros. O objetivo é estabelecer o IAM interno mínimo, rastreável e testável do MVP, sem frontend.

## Atores

- Dono: usuário interno que, quando autorizado, administra IAM e pode redefinir a senha de outro usuário;
- usuário interno autenticado: consulta sua sessão e altera a própria senha;
- sistema: autentica, controla sessão, resolve permissões, executa bootstrap e audita;
- operador de ambiente: fornece configuração segura de bootstrap, sem inserir segredo no repositório.

## Escopo funcional

Usuários, perfis, permissões, associação perfil-permissão, exceções individuais, login, logout, sessão atual, autorização backend, troca da própria senha, redefinição pelo Dono, bootstrap do primeiro Dono e auditoria básica de IAM.

O MVP atende uma única oficina. Identidade e autorização não dependem de tenant nesta Task.

## Regras funcionais

### RN-IAM-001 — Autenticação

Autenticação interna usa e-mail e senha, Spring Security Session e cookie de sessão `HttpOnly`. Falha de autenticação não deve revelar se o e-mail existe.

### RN-IAM-002 — Autoridade do backend

Toda operação protegida é autorizada no backend. Conhecer uma rota ou enviar manualmente uma requisição não concede permissão.

### RN-IAM-003 — Resolução de permissão

Para uma permissão consultada:

```text
exceção ALLOW → permitido;
exceção DENY → negado;
INHERIT ou ausência de exceção explícita → resultado do perfil.
```

Uma exceção explícita sempre prevalece sobre o perfil. `INHERIT` não concede nem retira por si só.

### RN-IAM-004 — Perfis e permissões

Os perfis iniciais previstos são Dono, Gerente Administrativo e Gerente Financeiro. Permissões são configuráveis e associações perfil-permissão determinam o resultado herdado. Esta especificação não presume matriz funcional dos gerentes; cada módulo futuro definirá suas permissões e concessões.

### RN-IAM-005 — Bootstrap

O bootstrap lê dados do primeiro Dono de configuração segura, somente cria o Dono quando não existe nenhum usuário e marca sua credencial para troca obrigatória. Não há senha em migration, código ou Git. Ausência ou invalidade da configuração necessária deve falhar sem criar usuário parcial.

### RN-IAM-006 — Concorrência do bootstrap

A condição “nenhum usuário existente” deve ser preservada também sob concorrência. No máximo um primeiro Dono pode ser criado.

### RN-IAM-007 — Senhas

Somente representação derivada por mecanismo de hash de senha aprovado pode ser persistida. Senha, senha inicial e credencial temporária não podem aparecer em logs ou auditoria.

### RN-IAM-008 — Troca própria

Usuário autenticado pode trocar a própria senha. Quando a troca obrigatória estiver ativa, a sessão fica limitada às operações indispensáveis para consultar a própria sessão, trocar a senha e encerrar a sessão; outros casos de uso protegidos são negados.

### RN-IAM-009 — Redefinição administrativa

Somente um Dono autenticado e autorizado pode redefinir a senha de outro usuário. A operação cria uma credencial temporária, ativa troca obrigatória no próximo login e é auditada. A credencial temporária em claro somente pode ser entregue uma vez no resultado imediato da operação protegida; não é persistida nem registrada em log/auditoria.

### RN-IAM-010 — Recuperação ausente

Não existe recuperação automática por e-mail no MVP.

### RN-IAM-011 — Sessão

Existe no máximo uma sessão ativa por usuário. Um novo login válido invalida a sessão ativa anterior. A sessão expira após oito horas de inatividade. Logout invalida a sessão corrente.

### RN-IAM-012 — Cookie e transporte

O cookie é `HttpOnly` em todos os ambientes e `Secure` em produção. Requisições autenticadas mutáveis devem possuir proteção contra CSRF. A configuração técnica de `SameSite`, hash adaptativo e demais parâmetros será selecionada e testada por AG-09/AG-02/AG-14 sem mudar estas regras funcionais.

### RN-IAM-013 — Auditoria básica

Registrar de forma persistente, sem segredos: bootstrap concluído, login bem-sucedido, login falho, logout, troca própria de senha, redefinição administrativa, criação/alteração de usuário, alteração de perfil, associação de permissão e alteração de exceção individual. Quando aplicável: ator, instante, ação, recurso, resultado, estado anterior/posterior sem segredo e correlation id.

### RN-IAM-014 — Histórico

Auditoria não é substituída por `logger`. Registros relevantes de IAM não são apagados para ocultar histórico.

## Fora do escopo

React e qualquer frontend; recuperação por e-mail; MFA; SSO/OAuth; matriz de permissões dos módulos ainda não implementados; ações críticas genéricas de outros domínios; links públicos de orçamento; `Tenant`, `tenant_id`, resolução/contexto de tenant e multi-tenancy.

Nenhuma decisão de interface pertence a esta Task. Uma futura Task de frontend IAM/login deverá consultar o Figma aprovado antes de implementar a interface.

## Critérios de aceite

- **CA-IAM-001:** DADO banco sem usuários e configuração válida, QUANDO o bootstrap executar, ENTÃO cria exatamente um Dono com troca obrigatória ativa.
- **CA-IAM-002:** DADO ao menos um usuário, QUANDO o bootstrap executar, ENTÃO não cria nem altera usuário.
- **CA-IAM-003:** DADAS execuções concorrentes sobre banco vazio, QUANDO concluírem, ENTÃO existe no máximo um primeiro Dono.
- **CA-IAM-004:** DADA configuração ausente/inválida, QUANDO o bootstrap executar, ENTÃO nenhum usuário parcial ou senha em claro é persistido.
- **CA-IAM-005:** DADO primeiro Dono, QUANDO autenticar inicialmente, ENTÃO somente operações permitidas durante troca obrigatória ficam acessíveis até alterar a senha.
- **CA-IAM-006:** DADAS credenciais válidas, QUANDO login ocorrer, ENTÃO uma sessão autenticada é criada em cookie `HttpOnly`.
- **CA-IAM-007:** DADAS credenciais inválidas, QUANDO login ocorrer, ENTÃO nenhuma sessão autenticada é criada e a resposta não confirma existência do e-mail.
- **CA-IAM-008:** DADO usuário autenticado, QUANDO logout ocorrer, ENTÃO sua sessão corrente deixa de autenticar requisições.
- **CA-IAM-009:** DADO usuário sem sessão, QUANDO acessar recurso protegido, ENTÃO o backend nega o acesso.
- **CA-IAM-010:** DADO perfil com permissão e exceção `INHERIT`, QUANDO autorizar, ENTÃO permite pelo perfil.
- **CA-IAM-011:** DADO perfil sem permissão e exceção `INHERIT`, QUANDO autorizar, ENTÃO nega.
- **CA-IAM-012:** DADO perfil sem permissão e exceção `ALLOW`, QUANDO autorizar, ENTÃO permite.
- **CA-IAM-013:** DADO perfil com permissão e exceção `DENY`, QUANDO autorizar, ENTÃO nega.
- **CA-IAM-014:** DADO usuário autenticado, QUANDO trocar corretamente a própria senha, ENTÃO a nova credencial passa a valer e a troca obrigatória é removida.
- **CA-IAM-015:** DADO usuário sem autorização administrativa, QUANDO tentar redefinir senha alheia, ENTÃO o backend nega e não altera credencial.
- **CA-IAM-016:** DADO Dono autorizado, QUANDO redefinir senha alheia, ENTÃO gera credencial temporária entregue uma vez, ativa troca obrigatória e audita sem segredo.
- **CA-IAM-017:** DADA sessão ativa, QUANDO o mesmo usuário fizer novo login válido, ENTÃO a sessão anterior é invalidada.
- **CA-IAM-018:** DADA sessão sem atividade por oito horas, QUANDO reutilizada, ENTÃO não autentica.
- **CA-IAM-019:** DADA sessão com atividade dentro do limite, QUANDO usada, ENTÃO o prazo de inatividade é renovado conforme Spring Security Session.
- **CA-IAM-020:** DADA operação mutável autenticada sem proteção CSRF válida, QUANDO chamada, ENTÃO é rejeitada.
- **CA-IAM-021:** DADO ambiente de produção, QUANDO o cookie for emitido, ENTÃO possui `HttpOnly` e `Secure`.
- **CA-IAM-022:** DADA ação auditável, QUANDO concluída ou falhar conforme especificado, ENTÃO produz trilha persistente sem senha, cookie ou credencial temporária.
- **CA-IAM-023:** DADO PostgreSQL real, QUANDO constraints de usuários/perfis/permissões/exceções forem exercitadas, ENTÃO duplicidades e estados inválidos são rejeitados.
- **CA-IAM-024:** DADO usuário autenticado, QUANDO consultar a sessão atual, ENTÃO recebe sua identidade e permissões efetivas, nunca hash ou segredo.
- **CA-IAM-025:** DADA rota administrativa, QUANDO chamada por usuário sem permissão efetiva, ENTÃO é negada mesmo que o cliente monte a requisição manualmente.

## Dependências e decisões

```text
TASK-0002 — DONE
DR-0002 — DECIDED
Decision Requests impeditivas — nenhuma
```
