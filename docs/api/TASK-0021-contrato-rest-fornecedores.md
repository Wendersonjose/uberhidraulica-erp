# Contrato REST — Fornecedores (Compras)

- Origem: `TASK-0021`, incremento `I2`
- Base: `/api/purchasing/suppliers`
- Autenticação: sessão Spring Security obrigatória em todos os métodos
- CSRF: obrigatório em `POST`, `PUT` e `DELETE`
- Permissões: `PURCHASE_VIEW` para consultar; `PURCHASE_SUPPLIER_MANAGE` para escrever
- Status: `APPROVED` para o escopo do `I2`
- Data: `2026-10-13`

A autorização é do **backend**. Esconder ou desabilitar botão no frontend é conforto de interface e não
substitui a proteção do endpoint (`AG-09`).

## 1. Permissões por perfil (`V22`)

```text
DONO                     PURCHASE_VIEW + PURCHASE_SUPPLIER_MANAGE
GERENTE_ADMINISTRATIVO   PURCHASE_VIEW + PURCHASE_SUPPLIER_MANAGE
GERENTE_FINANCEIRO       PURCHASE_VIEW
```

Exceções individuais de permissão do IAM continuam valendo e têm precedência sobre o perfil.

## 2. Representação

```json
{
  "id": "0f6a1f6e-4a1e-4a35-9d7a-2f0a3f2b9c11",
  "personType": "PJ",
  "legalName": "Hidráulica Central Ltda",
  "tradeName": "Hidrocentral",
  "document": "10670677000178",
  "documentMasked": false,
  "stateRegistration": "0012345670012",
  "phone": "3432001000",
  "email": "vendas@exemplo.test",
  "address": {
    "zipCode": "38400-000", "street": "Av. Rondon Pacheco", "number": "1200",
    "complement": "Galpão 3", "district": "Tibery", "city": "Uberlândia", "state": "MG"
  },
  "commercialTerms": {
    "paymentTerms": "28/35/42 dias", "preferredPaymentMethod": "BOLETO",
    "usualDueDay": 10, "creditLimit": 1500.50, "notes": "Entrega própria"
  },
  "relatedParty": false,
  "relatedPartyNote": null,
  "status": "ACTIVE",
  "inactivatedAt": null,
  "inactivationReason": null,
  "version": 1,
  "createdAt": "2026-10-13T13:00:00Z",
  "updatedAt": "2026-10-13T13:00:00Z"
}
```

Domínios fechados:

```text
personType  PF | PJ
status      ACTIVE | INACTIVE
```

`document` é guardado **somente em dígitos**: a máscara enviada é normalizada e a unicidade vale
independente dela. Fornecedor sem documento é aceito, e vários podem coexistir sem documento.

**Dado pessoal (`AG-09`).** O CPF de pessoa física só sai completo para quem tem
`PURCHASE_SUPPLIER_MANAGE`. Para quem só tem `PURCHASE_VIEW` ele volta mascarado e
`documentMasked` fica `true`. CNPJ não é mascarado — é dado público de pessoa jurídica.

`relatedParty` e `primary` (contatos) são **opcionais** no corpo; ausente significa `false`.

## 3. `POST /api/purchasing/suppliers`

Cadastra um fornecedor. Exige `PURCHASE_SUPPLIER_MANAGE`. Ignora `version`.

```json
{
  "personType": "PJ",
  "legalName": "Hidráulica Central Ltda",
  "document": "10.670.677/0001-78",
  "email": "vendas@exemplo.test",
  "address": { "zipCode": "38400-000", "city": "Uberlândia", "state": "MG" },
  "commercialTerms": { "paymentTerms": "28/35/42 dias", "usualDueDay": 10, "creditLimit": "1500.50" }
}
```

Obrigatórios: `personType`, `legalName`.

Resposta `201 Created` com `Location: /api/purchasing/suppliers/{id}` e o fornecedor criado. Todo
fornecedor nasce `ACTIVE` com `version = 1`.

Cadastro simultâneo do mesmo documento cria **exatamente um**: o índice único do banco resolve a corrida
e o segundo recebe `409 SUPPLIER_DOCUMENT_DUPLICATE`.

## 4. `GET /api/purchasing/suppliers`

Consulta paginada. Exige `PURCHASE_VIEW`.

```text
q             razão social, nome fantasia e, com 3+ caracteres alfanuméricos, o documento
personType    PF | PJ
status        ACTIVE | INACTIVE
relatedParty  true | false
page          padrão 0, negativo é tratado como 0
size          padrão 20, mínimo 1, máximo 100
```

```json
{ "items": [], "totalItems": 0, "page": 0, "size": 20, "totalPages": 0 }
```

O `q` ignora máscara ao procurar documento (compara só os alfanuméricos) e o caractere curinga do
`like` é escapado, então `%` digitado na busca é texto, não curinga. O limite de `size` é aplicado no
servidor: pedir uma página gigante devolve 100, não derruba a consulta. A ordenação é estável
(`legal_name`, depois `id`).

## 5. `GET /api/purchasing/suppliers/{id}`

Exige `PURCHASE_VIEW`. `404 SUPPLIER_NOT_FOUND` quando não existe.

## 6. `PUT /api/purchasing/suppliers/{id}`

Altera o cadastro. Exige `PURCHASE_SUPPLIER_MANAGE`. O corpo é o mesmo do `POST` **mais `version`
obrigatório**: é o bloqueio otimista do cadastro.

```text
version ausente   400 SUPPLIER_VERSION_REQUIRED
version antiga    409 SUPPLIER_VERSION_CONFLICT  (nada é sobrescrito)
```

Resposta `200 OK` com o fornecedor já na versão nova.

## 7. `POST /api/purchasing/suppliers/{id}/inactivate`

Exige `PURCHASE_SUPPLIER_MANAGE`. Motivo obrigatório.

```json
{ "reason": "Encerrou as atividades" }
```

Inativar **não apaga nada**: o fornecedor e todo o histórico continuam consultáveis, com
`status = INACTIVE`, `inactivatedAt` e `inactivationReason` preenchidos. Repetir devolve
`409 SUPPLIER_ALREADY_INACTIVE`.

## 8. `POST /api/purchasing/suppliers/{id}/reactivate`

Exige `PURCHASE_SUPPLIER_MANAGE`. Sem corpo. Repetir em fornecedor ativo devolve
`409 SUPPLIER_ALREADY_ACTIVE`.

## 9. `GET /api/purchasing/suppliers/{id}/events`

Trilha de auditoria paginada do cadastro. Exige `PURCHASE_SUPPLIER_MANAGE` — é mais restrito que a
consulta porque mostra valor anterior e valor novo de cada campo.

```json
{
  "items": [{
    "id": "…", "type": "UPDATED", "actorId": "…", "occurredAt": "2026-10-13T13:10:00Z",
    "reason": null, "changes": { "email": { "before": "antigo@exemplo.test", "after": "novo@exemplo.test" } }
  }],
  "totalItems": 1, "page": 0, "size": 20, "totalPages": 1
}
```

O CPF de pessoa física aparece mascarado também aqui. O banco recusa apagar ou reescrever evento de
auditoria, mesmo por SQL direto.

## 10. Contatos

```text
GET    /api/purchasing/suppliers/{id}/contacts?includeInactive=false   PURCHASE_VIEW
POST   /api/purchasing/suppliers/{id}/contacts                        PURCHASE_SUPPLIER_MANAGE
PUT    /api/purchasing/suppliers/{id}/contacts/{contactId}            PURCHASE_SUPPLIER_MANAGE
DELETE /api/purchasing/suppliers/{id}/contacts/{contactId}            PURCHASE_SUPPLIER_MANAGE
```

```json
{ "name": "Vendedor", "role": "vendas", "phone": "34999990000", "email": "v@exemplo.test", "primary": true }
```

Obrigatório: `name`.

Há **no máximo um contato principal ativo**. Marcar um novo como `primary` rebaixa o anterior; a regra é
garantida também por constraint, não só pela aplicação.

`DELETE` **apenas desativa** o contato (`204 No Content`, idempotente). O contato desativado só aparece com
`includeInactive=true`, e tentar alterá-lo devolve `404 SUPPLIER_CONTACT_NOT_FOUND`.

## 11. Erros

```text
400  SUPPLIER_INVALID                     dado obrigatório ausente ou inconsistente
400  SUPPLIER_DOCUMENT_INVALID            CPF/CNPJ com dígito verificador errado
400  SUPPLIER_VERSION_REQUIRED            PUT sem version
400  SUPPLIER_CONTACT_INVALID             contato inconsistente
404  SUPPLIER_NOT_FOUND
404  SUPPLIER_CONTACT_NOT_FOUND
409  SUPPLIER_DOCUMENT_DUPLICATE          documento já cadastrado, com ou sem máscara
409  SUPPLIER_VERSION_CONFLICT            cadastro alterado por outra pessoa
409  SUPPLIER_ALREADY_INACTIVE
409  SUPPLIER_ALREADY_ACTIVE
409  SUPPLIER_CONTACT_PRIMARY_CONFLICT
```

Formato: `{ "code": "...", "message": "...", "details": [] }`.

Sem sessão: `401 AUTHENTICATION_REQUIRED`. Com sessão e sem a permissão: `403`, registrado em
`iam.audit_event`. Requisição de escrita sem o cabeçalho CSRF: `403`.

## 12. Como exercitar no Insomnia

A sessão é **cookie**, não `Authorization: Bearer`. O Insomnia precisa guardar cookies (é o padrão) e o
token CSRF fica preso à sessão, então a ordem importa.

1. `GET {{base}}/api/iam/csrf` — devolve `{"headerName","parameterName","token"}`. Guarde `token`.
2. `POST {{base}}/api/iam/auth/login` com o cabeçalho CSRF do passo 1 e corpo
   `{"email":"...","password":"..."}`. Responde a sessão e os `permissions` do usuário — confira que
   `PURCHASE_VIEW` e `PURCHASE_SUPPLIER_MANAGE` estão lá.
3. A partir daí o cookie `SESSION` vai sozinho. **Todo `POST`/`PUT`/`DELETE` precisa repetir o cabeçalho
   CSRF**; `GET` não precisa.

Use como nome do cabeçalho exatamente o `headerName` devolvido no passo 1 (hoje `X-CSRF-TOKEN`) em vez de
fixá-lo no Insomnia: se a configuração de CSRF mudar, o roteiro continua válido. Não existe
`Authorization: Bearer` nesta API.

```text
base = http://localhost:8080
cabeçalho CSRF = <headerName do passo 1>: <token do passo 1>
```

Roteiro mínimo para ver o módulo inteiro funcionando:

```text
POST   /api/purchasing/suppliers                      cadastra (201, guarde id e version)
GET    /api/purchasing/suppliers?q=Hidr               acha na busca
GET    /api/purchasing/suppliers/{id}                 detalhe
PUT    /api/purchasing/suppliers/{id}                 altera enviando version (200, version sobe)
PUT    /api/purchasing/suppliers/{id}                 repita a version antiga  → 409
POST   /api/purchasing/suppliers/{id}/contacts        contato principal (201)
GET    /api/purchasing/suppliers/{id}/contacts        um principal só
DELETE /api/purchasing/suppliers/{id}/contacts/{cid}  204, e o contato some da lista padrão
POST   /api/purchasing/suppliers/{id}/inactivate      com reason (200, INACTIVE)
GET    /api/purchasing/suppliers/{id}/events          trilha de auditoria
POST   /api/purchasing/suppliers/{id}/reactivate      volta a ACTIVE
```

Para conferir a autorização sem depender da interface: repita o `POST` de cadastro autenticado como
`GERENTE_FINANCEIRO` (só tem `PURCHASE_VIEW`) e o resultado deve ser `403`; repita sem o cookie e deve ser
`401`; repita sem o cabeçalho CSRF e deve ser `403`.

## 13. Telas correspondentes

```text
/compras/fornecedores             lista com busca e filtros        PURCHASE_VIEW
/compras/fornecedores/novo        cadastro                         PURCHASE_SUPPLIER_MANAGE
/compras/fornecedores/{id}        detalhe, contatos e histórico    PURCHASE_VIEW
/compras/fornecedores/{id}/editar edição                           PURCHASE_SUPPLIER_MANAGE
```

O item "Compras › Fornecedores" só aparece no menu para quem tem `PURCHASE_VIEW`.
