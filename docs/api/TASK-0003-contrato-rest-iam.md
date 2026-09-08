# TASK-0003 — Contrato REST conceitual do IAM

## Status

```text
Responsáveis: AG-02 / AG-11 / AG-09
Status: APPROVED FOR IMPLEMENTATION DESIGN
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

Contratos conceituais cobrem identidade, e-mail, perfil, estado e troca obrigatória sem segredo. Listagem deve ser paginável. A semântica completa de desativação não é definida nesta Task; o `PATCH` somente poderá implementar campos e transições aprovados no requisito/implementação detalhada.

## Administração de perfis e permissões

```http
GET    /api/iam/profiles
POST   /api/iam/profiles
GET    /api/iam/profiles/{profileId}
PATCH  /api/iam/profiles/{profileId}
PUT    /api/iam/profiles/{profileId}/permissions/{permissionCode}
DELETE /api/iam/profiles/{profileId}/permissions/{permissionCode}
GET    /api/iam/permissions
```

Permissões são identificadas por código estável. Operações exigem permissão administrativa efetiva; esta especificação não cria matriz dos gerentes.

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
```

Lista e nomes são proposta técnica rastreável. Mensagens não expõem segredos nem permitem enumeração no login.

## Permissões administrativas

Códigos específicos de administração IAM serão definidos como contrato técnico durante a implementação e catalogados no módulo. Não se presume que Gerente Administrativo ou Gerente Financeiro os possuam. A capacidade de reset exige simultaneamente identidade Dono e autorização efetiva, conforme decisão aprovada.

## Pendências não bloqueadoras de desenho

Formato exato de UUID/identificadores, paginação, envelope e nomes finais de DTOs/códigos HTTP são detalhes técnicos a fechar pelo AG-11 sob revisão AG-02/AG-09/AG-13. Qualquer alteração funcional deve retornar ao fluxo de decisão.

Frontend permanece fora do escopo. O contrato não foi derivado de interface inexistente; futura Task de frontend IAM/login deverá consultar o Figma aprovado antes da implementação.
