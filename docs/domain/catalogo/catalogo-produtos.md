# Domínio — Catálogo de Produtos Físicos

- Origem: `TASK-0005`
- Agente proprietário: `AG-04 — Catálogo & Estoque`
- Status: `APPROVED` para o escopo da TASK-0005
- Data: `2026-09-14`

## 1. Conceito

Produto é o item físico que a oficina identifica, compra, guarda e aplica: peça, fluido, insumo, componente ou kit.

O catálogo responde **o que o item é**. Ele não responde **quanto existe**. Saldo físico, saldo reservado, saldo disponível, movimentação, reserva, inventário, perda, ajuste e custo médio pertencem ao módulo Estoque e não aparecem neste domínio.

## 2. Identidade

O produto possui identidade interna própria (`UUID`), independente de qualquer fornecedor.

O código interno é um rótulo operacional opcional. Quando existe, identifica o produto de forma única no catálogo, mas não substitui o identificador técnico e não é o código de fornecedor. Códigos de fornecedor serão modelados em relação própria quando o módulo Compras existir (AG-04, seções 7 a 9).

## 3. Classificação

Duas dimensões independentes classificam o produto:

```text
tipo funcional  → o que o item é, funcionalmente
categoria       → agrupamento gerencial livre
```

Tipo é obrigatório e fechado nos valores iniciais aprovados:

```text
PART                   peça
SUPPLY                 insumo, inclusive fluidos
COMPONENT              componente de uma peça principal
KIT                    conjunto comercializado como item único
INTERNAL_USE_MATERIAL  material de uso interno da oficina
```

Categoria é opcional, textual e livre. Ela não é um enum porque representa agrupamento gerencial que a oficina ajusta com o tempo.

## 4. Unidade-base

Unidade-base é obrigatória e representa a quantidade efetivamente controlada do item:

```text
UNIDADE
LITRO
METRO
QUILOGRAMA
```

Para fluidos a unidade-base é `LITRO`, e não a embalagem. Uma embalagem de 20 L é uma forma de compra, não uma unidade de estoque. Fator de conversão por forma de compra pertence a Compras e não existe neste domínio.

## 5. Referências comerciais

O produto pode registrar:

```text
custo de referência   valor indicativo de aquisição
preço de venda        valor indicativo de cobrança
```

Ambos são opcionais e não negativos, com duas casas decimais. **Ausência significa "não definido" e nunca zero.** Um produto sem preço de venda não pode ser cobrado; a regra de bloqueio pertence a quem realiza a cobrança, não ao catálogo.

Nenhum dos dois é custo real: custo real de aquisição e custo médio ponderado nascem de entradas de estoque e pertencem ao módulo Estoque.

## 6. Estoque mínimo

Estoque mínimo é um parâmetro opcional do cadastro, expresso na unidade-base do item.

Ele **não** gera alerta neste domínio. O alerta depende do saldo disponível (AG-04, seção 109), que não existe até o módulo Estoque ser implementado. A precisão adotada é provisória e está registrada em `DR-0006`.

## 7. Situação

```text
ATIVO    pode ser utilizado em novos lançamentos
INATIVO  permanece no catálogo, mas não deve ser oferecido em novos lançamentos
```

Todo produto nasce ativo. A inativação é uma decisão explícita e posterior.

## 8. Invariantes

1. Descrição é obrigatória e não pode ser vazia.
2. Tipo e unidade-base são obrigatórios e restritos aos valores aprovados.
3. Código interno, quando informado, é único no catálogo após normalização; quando ausente, não gera colisão.
4. Custo de referência e preço de venda são não negativos quando informados.
5. Estoque mínimo é não negativo quando informado.
6. Produto não é excluído fisicamente.
7. Alteração no catálogo não modifica nenhum registro histórico já emitido.

## 9. Snapshot histórico

Quando um produto é lançado em OS, orçamento, compra ou movimentação, os valores relevantes devem ser **copiados** para o registro de destino (AG-04, seções 38 e 87).

A consequência é que uma alteração de descrição ou de preço no catálogo nunca reescreve o passado. O catálogo é a fonte de verdade do presente; o snapshot é a fonte de verdade do que foi acordado.

## 10. Fora deste domínio

```text
saldo físico, reservado e disponível
movimentação, reserva, consumo
inventário, ajuste, perda
custo médio ponderado
fornecedor e código de fornecedor
forma de compra e fator de conversão
aplicação veicular
equivalência entre itens
estrutura de componentes com quantidade
grupo de veículos
sugestão de peça por serviço
```
