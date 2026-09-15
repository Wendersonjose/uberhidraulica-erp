# DR-0007 — Política monetária global: escala e arredondamento

- Tipo: `FINANCIAL`
- Status: `OPEN`
- Task: `TASK-0007`, e toda Task posterior que calcule dinheiro
- Origem: `AG-10 — Banco de Dados` e `AG-06 — Financeiro`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-15`

## Problema

O sistema passou a ter **duas escalas monetárias diferentes**, ambas legitimamente originadas de documentos aprovados:

```text
NUMERIC(15,2)   catálogo de serviços (V3), OS (V5), catálogo de produtos (V6),
                item físico da OS (V7)

NUMERIC(19,4)   orçamento (V8), conforme o modelo de dados aprovado da TASK-0001
```

O próprio modelo aprovado da TASK-0001 registra que `NUMERIC(19,4)` é "sugestão inicial" e que "a política monetária global será consolidada antes da implementação definitiva". Essa consolidação nunca ocorreu, e agora existe código nas duas escalas.

Além da escala, falta a decisão mais sensível: **qual regra de arredondamento** se aplica quando um valor derivado não é exatamente representável.

## Contexto

Hoje a diferença é inofensiva porque a conversão ocorre na direção segura: um preço com duas casas cabe sem perda em quatro. O risco aparece quando:

1. um total derivado (`quantidade × preço`) precisa ser reduzido à escala de persistência;
2. um valor de orçamento com quatro casas precisar voltar para um campo de duas casas (nota fiscal, contas a receber, comissão);
3. somas de itens arredondados individualmente divergirem do arredondamento da soma.

A TASK-0007 evitou escolher: o total do item só é gravado quando o produto é exato em até quatro casas, e qualquer caso que exigiria arredondar é **recusado** com `QUOTE_TOTAL_REQUIRES_ROUNDING_DECISION` (HTTP 422). Isso não é uma solução, é uma trava para não inventar dinheiro.

## Opções

- **A — duas casas em toda a cadeia:** alinha tudo ao que já está implementado e ao centavo real. Exige migration para reduzir a escala do orçamento e perde a capacidade de representar preço unitário fracionado abaixo do centavo, comum em fluidos e parafusos vendidos por litro ou metro.
- **B — quatro casas em toda a cadeia:** adota o modelo aprovado da TASK-0001 como padrão e migra as tabelas existentes. Suporta preço unitário sub-centavo, mas exige regra explícita de arredondamento em todo ponto de apresentação e cobrança.
- **C — quatro casas internas, duas casas no que é cobrado:** preço unitário e cálculos intermediários em quatro casas; todo valor efetivamente cobrado, apresentado ao cliente ou lançado no financeiro é arredondado para duas casas por uma regra única. Mais fiel à operação e ao fiscal, e o mais trabalhoso: exige definir onde ocorre o arredondamento e garantir que ocorra só uma vez.

Para a regra de arredondamento, em qualquer das opções:

- **HALF_UP** — prática comercial mais comum no Brasil;
- **HALF_EVEN** — reduz viés acumulado em grandes volumes;
- **arredondar a soma, não as parcelas** — evita que a soma dos itens difira do total apresentado.

## Recomendação

Opção C com `HALF_UP` aplicado uma única vez, no momento em que o valor se torna cobrável, e totais calculados sobre a soma e não sobre parcelas já arredondadas. A recomendação não é decisão.

## Impacto e bloqueio

Não bloqueia o que já está implementado: nenhum valor é arredondado silenciosamente em nenhum ponto do sistema hoje.

Bloqueia: total persistido do orçamento em casos não exatos, desconto, acréscimo, imposto, comissão, contas a receber e rentabilidade. Qualquer Task que precise arredondar dinheiro deve parar aqui.

## Relação com a DR-0006

A `DR-0006` trata da precisão da **quantidade** de item físico. Esta DR trata da escala e do arredondamento do **valor**. As duas se encontram no total do item: hoje a quantidade aceita três casas e o preço duas, o que pode gerar produto com cinco casas — exatamente o caso recusado pela trava descrita acima.

## Pergunta final

O sistema deve usar duas casas decimais em toda a cadeia monetária, quatro casas em toda a cadeia, ou quatro casas internamente com arredondamento para duas no que é efetivamente cobrado — e qual é a regra de arredondamento oficial?

## Decisão final do Owner

- Data: `-`
- Opção escolhida: `-`
- Decisão: `PENDENTE`
