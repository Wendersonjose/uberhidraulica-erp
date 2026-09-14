# Contrato REST — Itens físicos da Ordem de Serviço

- Origem: `TASK-0006`
- Base: `/api/work-orders`
- Autenticação: sessão Spring Security obrigatória
- CSRF: obrigatório na mutação
- Status: `APPROVED` para o escopo da TASK-0006
- Data: `2026-09-14`

## 1. `POST /api/work-orders/{id}/products`

```json
{ "productId": "0f6a1f6e-4a1e-4a35-9d7a-2f0a3f2b9c11", "quantity": 2.500 }
```

Ambos obrigatórios. `quantity` é estritamente positiva e aceita até três casas decimais.

Resposta `201 Created` com a OS completa, já contendo o item lançado.

O corpo **não** aceita descrição, unidade nem preço: todos vêm do catálogo. Permitir que o cliente informe o preço transformaria o snapshot em campo livre e abriria caminho para cobrar um valor que o catálogo nunca teve.

## 2. Item na representação da OS

`GET /api/work-orders` e `GET /api/work-orders/{id}` passam a incluir `products`:

```json
{
  "id": "…", "number": 7, "status": "ABERTA",
  "services": [ … ],
  "products": [
    {
      "id": "…",
      "productId": "0f6a1f6e-4a1e-4a35-9d7a-2f0a3f2b9c11",
      "description": "Óleo ATF Dexron III",
      "internalCode": "ATF-D3",
      "unit": "LITRO",
      "quantity": 2.500,
      "unitPrice": 42.90,
      "addedAt": "2026-09-14T13:10:00Z"
    }
  ]
}
```

Os itens vêm ordenados por instante de inclusão. `internalCode` é omitido quando o produto não possui código interno.

**Não existe campo de total.** A OS devolve quantidade e preço unitário; qualquer soma é responsabilidade de quem apresenta, enquanto a regra de arredondamento comercial não estiver decidida (`DR-0006`).

## 3. Erros

| Situação | HTTP | `code` |
| --- | --- | --- |
| `productId` ausente, `quantity` ausente, zero, negativa ou com mais de três casas | `400` | `VALIDATION_FAILED` |
| OS inexistente | `404` | `WORK_ORDER_NOT_FOUND` |
| Produto inexistente | `404` | `PRODUCT_NOT_FOUND` |
| Produto inativo no catálogo | `409` | `PRODUCT_INACTIVE` |
| Produto sem preço de venda definido | `409` | `PRODUCT_WITHOUT_SALE_PRICE` |
| Sem sessão | `401` | `AUTHENTICATION_REQUIRED` |
| Sem token CSRF | `403` | `ACCESS_DENIED` |

Os dois `409` são conflito de estado, não erro de formato: o pedido está correto, mas o catálogo não permite o lançamento naquele momento.

## 4. Imutabilidade

Não existe `PUT` nem `DELETE` de item lançado. Alterar ou remover um item já registrado é uma decisão sobre histórico e correção operacional que ainda não foi tomada e será tratada no workflow da OS.

Alteração posterior do produto no catálogo — inclusive inativação e mudança de preço — não altera nenhum item já lançado.

## 5. Efeito em estoque

Nenhum. O lançamento não consulta disponibilidade, não reserva e não baixa saldo. O módulo Estoque não existe, e simular reserva aqui criaria uma fonte de verdade falsa sobre o que a oficina realmente possui.
