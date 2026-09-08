# Domínio IAM — Fundação do MVP

## Status e autoridade

```text
Task: TASK-0003
Requisito: REQ-SEG-001 — APPROVED
Decisão: DR-0002 — DECIDED pelo proprietário do produto
Responsável: AG-09 — Segurança & Auditoria
Status: SECURITY_APPROVED
Pronto para arquitetura: SIM
```

## Responsabilidade do domínio

IAM é proprietário de identidades internas, credenciais, perfis, permissões, exceções individuais, autenticação, sessões e auditoria de IAM. Outros módulos perguntam ao contrato público se o usuário possui uma permissão; não leem tabelas nem repositories internos do IAM.

O contexto é uma única oficina. `Tenant` não é conceito do domínio IAM nesta Task, e identidade, sessão e permissão não possuem resolução por tenant.

## Conceitos e invariantes

- **User:** identidade interna com e-mail, estado, perfil e metadados auditáveis.
- **Profile:** agrupamento configurável de permissões; perfis iniciais previstos: Dono, Gerente Administrativo e Gerente Financeiro.
- **Permission:** capacidade estável identificada por código técnico.
- **ProfilePermission:** concessão de uma permissão por um perfil.
- **UserPermissionException:** resolução individual `INHERIT`, `ALLOW` ou `DENY` para um par usuário/permissão.
- **Credential:** representação derivada da senha e indicador de troca obrigatória; nunca contém senha recuperável.
- **AuthenticatedSession:** sessão controlada pelo Spring Security Session, limitada a uma por usuário e expirada após oito horas de inatividade.
- **IamAuditEvent:** evidência persistente de ação relevante sem segredo.

Invariantes:

1. e-mail identifica unicamente um usuário segundo normalização técnica consistente;
2. usuário possui um perfil vigente no recorte desta Task;
3. há no máximo uma exceção por usuário/permissão;
4. exceção explícita `ALLOW` ou `DENY` prevalece sobre o perfil; `INHERIT` consulta o perfil;
5. senha em claro nunca é persistida ou auditada;
6. primeiro Dono somente nasce quando não existe usuário;
7. senha inicial ou redefinida impõe troca obrigatória;
8. durante troca obrigatória, acesso fica restrito à sessão atual, troca própria e logout;
9. novo login válido invalida sessão anterior;
10. somente Dono autenticado e autorizado redefine senha alheia.

## Casos de uso

```text
BootstrapFirstOwner
Authenticate
Logout
GetCurrentSession
ChangeOwnPassword
ResetUserPasswordAsOwner
CreateUser
UpdateUser
CreateProfile
UpdateProfile
AssignPermissionToProfile
SetUserPermissionException
ResolveEffectivePermission
```

Nomes são conceituais e não obrigam nomes de classes.

## Resolução de autorização

```text
entrada: usuário + código de permissão
↓
exceção ALLOW? permitir
exceção DENY? negar
INHERIT/ausente? consultar associação perfil-permissão
↓
resultado efetivo
```

## Credencial temporária

A geração usa fonte criptograficamente segura e formato técnico capaz de atender a política de senha. O valor em claro é apresentado uma única vez no resultado imediato da redefinição autorizada, nunca persistido ou logado. A persistência recebe somente a representação derivada e marca troca obrigatória.

## Sessão e segurança web

- sessão server-side gerenciada pela integração aprovada do Spring Security;
- cookie `HttpOnly`; `Secure` obrigatório em produção;
- proteção CSRF nas operações autenticadas mutáveis;
- `SameSite` escolhido tecnicamente de forma compatível com o cliente legítimo e testado antes da implementação ser aprovada;
- mensagem de falha de login não enumera usuários;
- senha/hash/cookie/credencial temporária não entram em logs, eventos ou respostas posteriores.

## Auditoria básica

Eventos mínimos: bootstrap, login bem-sucedido, login falho, logout, troca própria, reset administrativo, criação/alteração de usuário, alteração de perfil/permissão e exceção individual. A auditoria registra resultado e contexto suficiente sem segredo; login falho pode não possuir `actor_user_id`, mas preserva correlação e dados técnicos minimizados.

## Eventos de domínio conceituais

```text
FirstOwnerBootstrapped
UserCreated
UserUpdated
ProfileChanged
PermissionAssignmentChanged
UserPermissionExceptionChanged
PasswordChanged
PasswordResetByOwner
LoginSucceeded
LoginFailed
LogoutCompleted
```

Eventos não carregam senha, hash, cookie ou credencial temporária. Publicação externa/outbox não é exigida por esta Task; auditoria deve ser atômica com mudanças relevantes sempre que aplicável.

## Riscos e controles

- bootstrap concorrente: constraint/transação e teste concorrente;
- enumeração: mensagem uniforme e testes;
- session fixation: renovação do identificador no login pela configuração Spring Security;
- CSRF: proteção ativa e teste positivo/negativo;
- segredo em observabilidade: redaction e proibição explícita;
- autorização incorreta: algoritmo único no IAM e matriz de testes `INHERIT/ALLOW/DENY`.

## Fora do escopo

Frontend, e-mail, MFA, SSO/OAuth, matriz funcional de módulos futuros, links públicos, aprovação crítica genérica e multi-tenancy.
