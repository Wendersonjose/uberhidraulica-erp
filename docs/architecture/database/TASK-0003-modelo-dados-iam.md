# TASK-0003 — Modelo de dados conceitual do IAM

## Parecer AG-10

```text
STATUS: DATA_APPROVED FOR SPECIFICATION
DECISÕES: DR-0002 e DR-0003 — DECIDED
BANCO: PostgreSQL
MIGRATION NESTA FASE: NÃO
MIGRATION NA IMPLEMENTAÇÃO: SIM
PRONTO PARA AG-11: SIM
```

## Schema e propriedade

Recomenda-se schema lógico `iam`, sujeito à validação física na implementação. Somente o adapter de persistência IAM acessa suas tabelas. Auditoria IAM pode residir no mesmo schema nesta primeira entrega, preservando separação conceitual para evolução.

O banco representa uma única oficina. Nenhuma tabela IAM recebe `tenant_id`, e não será criada tabela `tenant` ou constraint/índice de isolamento multi-tenant nesta Task.

## Relações conceituais

```text
Profile 1 ── N User
Profile N ── N Permission, por ProfilePermission
User N ── N Permission, por UserPermissionException
User 1 ── 1 Credential vigente
User 1 ── N IamAuditEvent
User 1 ── 0..1 sessão ativa efetiva no MVP
```

## Estruturas conceituais

### `iam_profile`

Identificador técnico, código estável restrito a `DONO|GERENTE_ADMINISTRATIVO|GERENTE_FINANCEIRO`, metadado descritivo sem semântica adicional e timestamps. Catálogo é fixo e idempotente; perfil não é excluído, renomeado ou transformado.

### `iam_permission`

Identificador técnico, código único, descrição e estado. Código é contrato técnico; renomeação exige compatibilidade.

### `iam_profile_permission`

Perfil, permissão e metadados de concessão. Par perfil/permissão é único.

### `iam_user`

Identificador técnico, e-mail original, chave normalizada única para autenticação, perfil, estado `ACTIVE|INACTIVE` e timestamps. A modelagem física da normalização deve ser única entre aplicação e banco. Não existe exclusão física.

### `iam_user_permission_exception`

Usuário, permissão, resolução `INHERIT|ALLOW|DENY` e timestamps/ator quando disponível. Par usuário/permissão é único. `INHERIT` pode ser persistido ou representado pela ausência da linha somente se o contrato escolhido preservar de forma inequívoca a decisão aprovada; a migration deve documentar a escolha técnica.

### `iam_credential`

Usuário único, hash codificado completo pelo encoder, flag `must_change_password`, timestamps de criação/alteração e informação técnica de versão do mecanismo quando necessária. Não contém senha em claro ou credencial temporária.

Na criação de usuário ou reset, o valor temporário existe somente em memória até o resultado imediato; apenas o hash entra na transação e na persistência.

### sessão

Persistência compatível com Spring Security Session, com índice por principal e expiração. A estrutura física poderá usar tabelas fornecidas pelo componente aprovado. Deve permitir localizar/inutilizar sessão anterior e aplicar uma sessão ativa por usuário.

### `iam_audit_event`

Identificador, instante com timezone, tipo de ação, ator opcional, alvo/recurso, resultado, correlation id e detalhes estruturados sem segredo. Antes/depois sensível deve ser minimizado.

## Constraints mínimas

- PK em todas as estruturas;
- e-mail normalizado único e não nulo;
- perfil do usuário referenciado e não nulo no recorte aprovado;
- código de perfil e de permissão únicos e não nulos;
- `CHECK` ou proteção equivalente para somente os três códigos fixos de perfil;
- `CHECK` de estado do usuário em `ACTIVE`, `INACTIVE` ou enum PostgreSQL avaliado tecnicamente;
- unicidade perfil/permissão;
- unicidade usuário/permissão para exceção;
- `CHECK` da resolução em `INHERIT`, `ALLOW`, `DENY` ou enum PostgreSQL avaliado tecnicamente;
- uma credencial vigente por usuário;
- hash e flag de troca obrigatória não nulos;
- FKs sem cascade destrutivo que apague histórico indevidamente;
- timestamps técnicos com `TIMESTAMPTZ`;
- proteção transacional/constraint que impeça dois primeiros Donos no bootstrap.
- proteção que impeça inativar o último Dono `ACTIVE`, inclusive em transações concorrentes.

A forma física exata da proteção do bootstrap deve ser demonstrada sob concorrência; não se aceita somente “contar e inserir” sem proteção.

## Índices relevantes

- chave normalizada de e-mail (único);
- código de perfil e permissão (únicos);
- associações por perfil e permissão;
- exceções por usuário e permissão;
- sessão por principal e expiração;
- auditoria por instante, ator, alvo e tipo conforme consultas aprovadas.

## Histórico e exclusão

Não apagar auditoria. Usuários, perfis e permissões com relevância histórica devem usar estado, não exclusão física arbitrária. A semântica completa de desativação não está no escopo funcional desta Task; a implementação não deve inventar efeitos além do necessário para os casos aprovados.

## Seeds conceituais e bootstrap

- seed idempotente dos três perfis fixos;
- seed idempotente dos códigos estáveis de permissões administrativas IAM;
- associação idempotente dessas permissões ao perfil `DONO`;
- ausência de associação administrativa automática aos dois perfis de gerente;
- bootstrap consistente cria o primeiro Dono vinculado ao `DONO`, com hash e `must_change_password = true`;
- senha inicial não integra seed ou migration.

SQL definitivo, técnica de upsert e numeração Flyway pertencem à implementação futura.

## Concorrência

Testar bootstrap simultâneo, unicidade de e-mail, alteração concorrente de exceção, substituição de sessão e inativação concorrente de Donos. A estratégia física deve serializar ou bloquear adequadamente a decisão de inativação para que commits concorrentes nunca deixem zero Donos `ACTIVE`. Locking/constraint final será definido na implementação e provado com PostgreSQL/Testcontainers.

## Migration futura

A implementação deverá criar migration Flyway versionada depois de revisão AG-10. Este documento não define nome/versão da migration nem produz SQL.
