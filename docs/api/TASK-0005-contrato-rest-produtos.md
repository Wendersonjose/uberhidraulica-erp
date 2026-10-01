# Contrato REST — Catálogo de Produtos

- Origem: `TASK-0005`
- Base: `/api/products`
- Autenticação: sessão Spring Security obrigatória em todos os métodos
- CSRF: obrigatório em `POST` e `PUT`
- Status: `APPROVED` para o escopo da TASK-0005
- Data: `2026-09-14`

## 1. Representação

```json
{
  "id": "0f6a1f6e-4a1e-4a35-9d7a-2f0a3f2b9c11",
  "description": "Óleo ATF Dexron III",
  "internalCode": "ATF-D3",
  "category": "Fluidos",
  "type": "SUPPLY",
  "unit": "LITRO",
  "referenceCost": 28.50,
  "salePrice": 42.90,
  "minimumStock": 20.000,
  "active": true,
  "createdAt": "2026-09-14T13:00:00Z",
  "updatedAt": "2026-09-14T13:00:00Z"
}
```

Campos opcionais ausentes são omitidos do JSON (`null`), nunca substituídos por `0`.

Domínios fechados:

```text
type  PART | SUPPLY | COMPONENT | KIT | INTERNAL_USE_MATERIAL
unit  UNIDADE | LITRO | METRO | QUILOGRAMA
```

## 2. `POST /api/products`

Cria um produto. **Não aceita `active`**: todo produto nasce ativo.

```json
{
  "description": "Óleo ATF Dexron III",
  "internalCode": "atf-d3",
  "category": "Fluidos",
  "type": "SUPPLY",
  "unit": "LITRO",
  "referenceCost": "28.50",
  "salePrice": "42.90",
  "minimumStock": "20.000"
}
```

Obrigatórios: `description`, `type`, `unit`.

Resposta `201 Created` com `Location: /api/products/{id}` e o produto criado. `internalCode` volta normalizado em maiúsculas e sem espaços nas extremidades.

## 3. `GET /api/products`

Lista todos os produtos, ativos e inativos, ordenados por descrição e depois por identificador. Sem paginação e sem filtro nesta versão. Resposta `200`.

## 4. `GET /api/products/{id}`

Resposta `200` com o produto, ou `404` com `PRODUCT_NOT_FOUND`.

## 5. `PUT /api/products/{id}`

Substitui os campos do produto. Mesmo corpo do `POST`, acrescido de `active` opcional; quando `active` é omitido, a situação atual é preservada.

`id` e `createdAt` nunca são alterados. `updatedAt` é sempre regravado.

Resposta `200` com o produto atualizado.

## 6. Ausência de exclusão

Não existe `DELETE`. Produto historicamente utilizado não é removido; a retirada de uso é feita com `active: false`.

## 7. Erros

| Situação | HTTP | `code` |
| --- | --- | --- |
| Campo obrigatório ausente, tamanho excedido ou valor negativo | `400` | `VALIDATION_FAILED` |
| Enum fora do domínio ou corpo ilegível | `400` | `INVALID_REQUEST` |
| Invariante de domínio violada, inclusive código interno malformado | `400` | `INVALID_PRODUCT` |
| Produto inexistente | `404` | `PRODUCT_NOT_FOUND` |
| Código interno já utilizado por outro produto | `409` | `PRODUCT_INTERNAL_CODE_ALREADY_EXISTS` |
| Sem sessão | `401` | `AUTHENTICATION_REQUIRED` |
| Sem token CSRF em mutação | `403` | `ACCESS_DENIED` |

Formato do erro, idêntico ao restante da API:

```json
{ "code": "PRODUCT_NOT_FOUND", "message": "Produto não encontrado", "details": [] }
```

`VALIDATION_FAILED` preenche `details` com `campo: mensagem`.

## 8. Contrato interno entre módulos

Consumo por outro módulo **não** ocorre por HTTP. A interface Java `ProductCatalogQuery` é o único ponto de acesso entre módulos:

```java
Optional<ProductReference> product(UUID id);
record ProductReference(UUID id, String description, String internalCode, String unit,
                        BigDecimal salePrice, boolean active) {}
```

`salePrice` pode ser nulo. O contrato não expõe saldo de estoque em nenhuma forma.
