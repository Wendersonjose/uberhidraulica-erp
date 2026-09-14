# DR-0006 — Precisão e fracionamento das quantidades de itens físicos

- Tipo: `BUSINESS`
- Status: `OPEN`
- Task: `TASK-0005`, `TASK-0006`
- Origem: `AG-04 — Catálogo & Estoque`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-14`

## Problema

O catálogo passa a registrar itens cuja unidade-base pode ser fracionável (`LITRO`, `METRO`, `QUILOGRAMA`). Nenhum documento vigente define qual precisão decimal a oficina realmente utiliza para quantidades de item físico, nem se quantidades fracionadas são permitidas ao lançar itens em OS, orçamento, compra e estoque.

A documentação aprovada é explícita apenas no sentido negativo: consumo fracionado não faz parte do MVP e a exigência de quantidade inteira, quando existir, deve ser validada no domínio (AG-04, seções 25 e 26). Isso não responde qual precisão armazenar nem qual regra vale para lançamento comercial.

## Contexto

A decisão afeta, em ordem de risco:

1. quantidade de item físico lançada em OS e orçamento — impacta o valor cobrado do cliente;
2. quantidade movimentada em estoque — impacta saldo físico, reservado e disponível;
3. custo médio ponderado — impacta rentabilidade;
4. estoque mínimo do catálogo — impacta apenas alerta operacional futuro.

Somente o item 4 pertence à TASK-0005. Os itens 1 a 3 pertencem a Tasks posteriores.

## Opções

- **A — quantidade inteira na unidade-base:** todo lançamento usa múltiplos inteiros da unidade-base; um fluido comprado em embalagem de 5 L entra como 5 unidades de litro. Simples e alinhado à seção 25, mas impede registrar meio metro de mangueira.
- **B — três casas decimais em toda a cadeia:** quantidade `NUMERIC(15,3)` em catálogo, OS, compra e estoque, com regra de arredondamento única definida no domínio. Suporta fluidos e metragem sem inventar conversão.
- **C — precisão por unidade:** `UNIDADE` aceita somente inteiros; `LITRO`, `METRO` e `QUILOGRAMA` aceitam três casas. Mais fiel à operação, porém exige validação condicional e uma regra explícita por unidade.

## Recomendação

Opção C no médio prazo, com a Opção B como forma de armazenamento. A recomendação não é decisão.

## Implementação conservadora adotada enquanto a decisão não existe

Para não bloquear a TASK-0005, apenas o estoque mínimo do catálogo foi implementado, como `NUMERIC(15,3)` não negativo e opcional. Ele é um parâmetro de cadastro: nenhum alerta é emitido, nenhum saldo é calculado e nenhum valor monetário é derivado dele. Nenhuma quantidade de lançamento comercial ou de estoque foi criada nesta Task.

## Impacto e bloqueio

Não bloqueia a TASK-0005. Bloqueia a definição final da quantidade de item físico em OS/orçamento, em compras e em estoque. Enquanto estiver `OPEN`, qualquer Task que introduza quantidade comercial ou movimentação deve registrar explicitamente a precisão adotada e tratá-la como provisória.

## Pergunta final

Quantidades de itens físicos devem ser sempre inteiras na unidade-base, sempre fracionadas com três casas decimais, ou fracionadas apenas para as unidades contínuas (`LITRO`, `METRO`, `QUILOGRAMA`) e inteiras para `UNIDADE`?

## Decisão final do Owner

- Data: `-`
- Opção escolhida: `-`
- Decisão: `PENDENTE`
