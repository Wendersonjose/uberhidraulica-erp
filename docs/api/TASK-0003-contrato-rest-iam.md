# TASK-0003 — Contrato REST conceitual do IAM

## Status

```text
Responsáveis: AG-02 / AG-11 / AG-09
Status: APPROVED FOR IMPLEMENTATION DESIGN
Decisões: DR-0002 e DR-0003 — DECIDED
Natureza: contrato conceitual; DTOs e códigos HTTP finais serão fechados tecnicamente na implementação
```

## Convenções

- base conceitual: `/api/iam`;
- sessão por cookie, não por token exposto ao JavaScript;
- DTOs não expõem entidades JPA, hash, cookie ou credenciais persistidas;
- erros seguem envelope consistente do projeto;
- falha de login não revela existência do e-mail;
- operações mutáveis autenticadas exigem CSRF válido;
- códigos HTTP finais devem seguir semântica HTTP/Spring Security e testes, sem criar comportamento funcional novo.
- rotas, DTOs, sessão e autenticação não recebem identificador/header/contexto de tenant; o MVP atende uma única oficina.

## Operações públicas de autenticação

### Login

```http
POST /api/iam/auth/login
```

Entrada conceitual: e-mail e senha. Saída: identidade mínima, indicador de troca obrigatória e permissões efetivas, além do cookie de sessão. Não retorna hash.

### Logout

```http
POST /api/iam/auth/logout
```

Invalida a sessão corrente. É operação autenticada e protegida contra CSRF.

## Operações autenticadas

### Sessão atual

```http
GET /api/iam/session
```

Retorna identidade atual, perfil, permissões efetivas e `mustChangePassword`. Sem sessão válida, informa ausência de autenticação conforme convenção técnica.

### Troca da própria senha

```http
POST /api/iam/password/change
```

Entrada conceitual: senha atual e nova senha. Remove troca obrigatória após sucesso. Não ecoa senhas.

### Redefinição administrativa

```http
POST /api/iam/users/{userId}/password-reset
```

Exige Dono autenticado e permissão administrativa efetiva. Gera credencial temporária, marca troca obrigatória e retorna o valor temporário somente nesta resposta. Não permite recuperar o valor depois.

## Administração de usuários

```http
GET    /api/iam/users
POST   /api/iam/users
GET    /api/iam/users/{userId}
PATCH  /api/iam/users/{userId}
```

`POST` cria usuário `ACTIVE`, gera senha temporária por fonte criptograficamente segura, persiste somente o hash com `mustChangePassword = true` e retorna o valor em claro somente no resultado imediato. Consultas posteriores nunca retornam a temporária.

Contratos conceituais cobrem identidade, e-mail, perfil fixo e estado. `PATCH` pode alterar `ACTIVE|INACTIVE` quando autorizado. Inativação invalida a sessão; reativação não restaura sessão. Não existe endpoint de exclusão física. Listagem deve ser paginável.

## Administração de perfis e permissões

```http
GET    /api/iam/profiles
GET    /api/iam/profiles/{profileId}
PUT    /api/iam/profiles/{profileId}/permissions/{permissionCode}
DELETE /api/iam/profiles/{profileId}/permissions/{permissionCode}
GET    /api/iam/permissions
```

O `DELETE` acima remove somente uma associação configurável perfil-permissão; não exclui perfil nem permissão do catálogo.

Os únicos perfis são `DONO`, `GERENTE_ADMINISTRATIVO` e `GERENTE_FINANCEIRO`. A API não cria/exclui tipos, não renomeia códigos e não transforma um perfil em outro. Permissões são identificadas por código estável. Operações de associação exigem permissão administrativa efetiva; os gerentes não recebem essa capacidade automaticamente.

## Exceções individuais

```http
GET /api/iam/users/{userId}/permission-exceptions
PUT /api/iam/users/{userId}/permission-exceptions/{permissionCode}
```

Entrada conceitual do `PUT`: `resolution = INHERIT|ALLOW|DENY`. Saída de consulta diferencia resultado efetivo, fonte (`PROFILE` ou `USER_EXCEPTION`) e resolução configurada, sem duplicar a regra no cliente.

## Erros conceituais

```text
AUTHENTICATION_FAILED
AUTHENTICATION_REQUIRED
ACCESS_DENIED
PASSWORD_CHANGE_REQUIRED
CURRENT_PASSWORD_INVALID
USER_NOT_FOUND (somente em contexto administrativo autorizado)
DUPLICATE_USER_EMAIL
INVALID_PERMISSION_RESOLUTION
BOOTSTRAP_NOT_APPLICABLE
INVALID_USER_STATE
LAST_ACTIVE_OWNER_REQUIRED
```

Lista e nomes são proposta técnica rastreável. Mensagens não expõem segredos nem permitem enumeração no login.

## Permissões administrativas

Códigos específicos de administração IAM serão definidos como contrato técnico estável durante a implementação e catalogados no módulo. O bootstrap os associa ao perfil `DONO`. Não se presume que Gerente Administrativo ou Gerente Financeiro os possuam. A capacidade de reset exige simultaneamente identidade Dono e autorização efetiva.

`LAST_ACTIVE_OWNER_REQUIRED` é o erro conceitual da tentativa de inativar o último Dono `ACTIVE`; nome e código HTTP finais podem ser ajustados tecnicamente sem alterar a regra.

## Pendências não bloqueadoras de desenho

Formato exato de UUID/identificadores, paginação, envelope e nomes finais de DTOs/códigos HTTP são detalhes técnicos a fechar pelo AG-11 sob revisão AG-02/AG-09/AG-13. Qualquer alteração funcional deve retornar ao fluxo de decisão.

Na implementação, `GET /api/iam/users` aceita `page` (base zero, padrão `0`) e `size` (padrão `20`, intervalo técnico `1..100`) e retorna `content`, `page`, `size`, `totalElements` e `totalPages`. A ordenação interna estável por nome e identificador serve somente à consistência da paginação; não foi criado filtro funcional novo nem foi exposto tipo JPA no contrato.

Frontend permanece fora do escopo. O contrato não foi derivado de interface inexistente; futura Task de frontend IAM/login deverá consultar o Figma aprovado antes da implementação.
