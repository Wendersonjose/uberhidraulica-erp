# TASK-0003 — Arquitetura do módulo IAM

## Parecer AG-02

```text
STATUS: APPROVED
MÓDULO: IAM
DR: DR-0002 — DECIDED
PRONTO PARA AG-10/AG-11: SIM, após especificação de dados
```

## Fronteira modular

IAM encapsula usuários, credenciais, perfis, permissões, exceções, autenticação, sessões e auditoria própria. Deve ser um módulo Spring Modulith identificável. Nenhum módulo pode importar entity, repository, adapter ou tabela interna do IAM.

O MVP atende uma única oficina. A arquitetura não contém `Tenant`, tenant context, resolução de tenant no login, autorização por tenant ou abstração especulativa para multi-tenancy.

Outros módulos podem depender somente de contratos públicos estáveis do IAM e podem declarar códigos de permissão de suas próprias capacidades sem acessar internals do IAM.

## Camadas obrigatórias

```text
REST Controller / Security Filter
↓
Application / Use Case
↓
Domain
↓
Repository Port
↓
Persistence Adapter
```

- **API:** traduz HTTP, obtém identidade autenticada e não contém regra.
- **Application:** coordena transações, casos de uso, auditoria e ports.
- **Domain:** resolve permissão e protege invariantes de usuário/credencial/bootstrap.
- **Ports:** abstraem persistência, sessões, hashing, geração segura e relógio.
- **Adapters:** Spring Security, Spring Session e JPA/PostgreSQL.

Entidades JPA não são DTOs REST e não atravessam a fronteira pública.

## Contratos públicos conceituais

```text
CurrentIdentity currentIdentity()
boolean hasPermission(UserId, PermissionCode)
void requirePermission(UserId, PermissionCode)
```

O formato Java final pertence à implementação, desde que preserve o contrato sem expor internals. Contratos de administração permanecem nos casos de uso internos expostos pela API IAM; outros módulos não administram IAM por repository.

## Responsabilidade pela sessão

Spring Security autentica e Spring Security Session mantém estado server-side. IAM configura autenticação, limite de uma sessão, expiração por inatividade, invalidação no logout e restrição durante troca obrigatória. O repositório de sessões não é repository de domínio acessível por outros módulos.

## Transações

- bootstrap: verificação/criação/credencial/auditoria em unidade consistente, protegida contra concorrência;
- alteração de senha e reset: atualização de credencial, flag e auditoria na mesma transação;
- alteração de perfil/permissão/exceção: mudança e auditoria atômicas;
- login/logout: integram ciclo de sessão e evidência sem ampliar transações com chamadas externas.

## Eventos e integração

Eventos IAM são fatos, não comandos, e nunca carregam segredo. Nesta Task não há integração externa nem exigência de outbox. Eventos públicos futuros só serão promovidos quando consumidor e contrato forem aprovados.

## Autorização

Um único serviço de domínio/aplicação resolve permissões efetivas. O enforcement ocorre no backend, preferencialmente antes do caso de uso e novamente dentro da fronteira apropriada quando uma invariante exigir. Não hardcode comportamento funcional por nome de perfil, exceto a capacidade aprovada e explicitamente validada do Dono para redefinir senha alheia.

## Decisões técnicas de segurança

- manter CSRF habilitado para sessão baseada em cookie;
- usar `PasswordEncoder` adaptativo aprovado e parâmetros revisados na implementação, sem algoritmo caseiro;
- renovar identificador de sessão após autenticação;
- usar comparação e mensagens que não revelem usuário existente;
- configuração de cookie e HTTPS externalizada por ambiente;
- segredo temporário retornado uma única vez e excluído de serialização/log posterior.

Essas escolhas implementam controles técnicos e não ampliam o escopo funcional. Mudança que altere operação ou exposição de credencial exige nova análise/DR.

## Proibições

- Controller → JpaRepository;
- acesso a tabela/repository IAM por outro módulo;
- senha/hash em DTO de leitura, log ou auditoria;
- JWT/localStorage como substituto da sessão aprovada;
- desabilitar CSRF globalmente para simplificar;
- permissão aplicada apenas por frontend;
- criação do primeiro Dono por migration.
- `tenant_id` ou qualquer dependência de tenant em entidades, tabelas, sessão, autenticação, autorização ou contratos IAM.

## Riscos residuais

Parâmetros exatos de hash, nomes finais de cookies e forma física de persistência de sessões são decisões técnicas reversíveis a validar durante a implementação. Não alteram critérios funcionais e não constituem Decision Request bloqueadora.
