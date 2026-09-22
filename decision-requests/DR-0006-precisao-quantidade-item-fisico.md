# DR-0006 — Precisão e fracionamento das quantidades de itens físicos

- Tipo: `BUSINESS`
- Status: `DECIDED`
- Task: `TASK-0005`, `TASK-0006`
- Origem: `AG-04 — Catálogo & Estoque`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-14`
- Decidida em: `2026-09-22`, pelo Owner, após revisão externa sobre a branch `feature/autonomous-sprint-catalog`

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

## Evidência acrescentada em 2026-09-15 (TASK-0007)

O orçamento passou a existir e trouxe duas informações novas que esta decisão precisa considerar:

1. O modelo de dados aprovado da TASK-0001 especifica `quantity NUMERIC(19,4)` para a versão comercial
   do item — **quatro** casas, não três. Existem hoje, portanto, duas precisões de quantidade no
   sistema: três casas no item físico da OS (`V7`) e quatro no item de orçamento (`V8`).
2. Com quantidade de três casas e preço de duas, o produto pode chegar a cinco casas decimais. A
   TASK-0007 recusa esse caso com `422 QUOTE_TOTAL_REQUIRES_ROUNDING_DECISION` em vez de arredondar,
   porque arredondar mudaria o valor cobrado do cliente por decisão de implementação.

A escala monetária e a regra de arredondamento do valor foram separadas nesta `DR-0007`. Esta DR
permanece responsável apenas pela precisão da **quantidade**.

## Efeito da DR-0007, decidida em 2026-09-15

A `DR-0007` fixou o **teto**: quantidade comercial aceita até quatro casas decimais, e quantidade
física já persistida não é reduzida em silêncio para caber em escala menor.

Consequências concretas:

- o item de orçamento passou a aceitar quatro casas, e o total já não depende mais desta DR;
- o item físico da OS (`V7`) continua em `NUMERIC(15,3)` e aceitando três casas. Ampliar essa coluna
  exigiria migration nova, e a `DR-0007` é explícita em só fazer isso diante de necessidade concreta.
  A necessidade depende justamente do que **esta** DR ainda não decidiu: se a oficina lança fração de
  litro, de metro e de quilo, e com que granularidade.

Portanto o que resta aqui é uma decisão **operacional**, não de persistência: qual fracionamento a
oficina realmente usa ao lançar item físico.

## Evidência acrescentada em 2026-09-18 (TASK-0014)

O estoque passou a existir, e com ele a movimentação — o item 2 da lista de risco acima deixou de ser
hipotético. Três fatos novos:

1. **O cartão "Entradas e ajustes de estoque" exige explicitamente que "Quantidades respeitam a
   unidade cadastrada".** Isso confirma que existe uma regra de compatibilidade entre unidade e
   quantidade, mas continua sem dizer qual é a granularidade. A parte da regra que já está garantida
   por construção é a ausência de soma entre unidades: toda quantidade — de catálogo, de OS e de
   movimentação — é expressa na unidade cadastrada do produto, e a API de movimentação não aceita
   unidade própria. O que falta é exatamente a fração.

2. **Duas unidades novas e inerentemente contáveis entraram no catálogo**: `GALAO_5L` e `BALDE_20L`
   (`DR-0014`, a partir do cartão "Cadastro de produtos, peças e fluidos"). Meio balde de 20 litros é
   um caso que a oficina precisa dizer se existe. Isso reforça a Opção C, sem decidi-la.

3. A `DR-0014` chegou a registrar como decidido que "unidades contáveis são inteiras; as demais
   aceitam três casas" — que é, literalmente, a **Opção C desta DR**. Uma DR de estoque não tem
   autoridade para fechar a pergunta de outra DR ainda `OPEN`. A revisão `AG-15` de 2026-09-18
   removeu essa afirmação da `DR-0014` e devolveu a pergunta para cá. O predicado
   `ProductUnit.countable()`, que existia no código sem nenhum chamador — isto é, a regra estava
   escrita mas não valia —, foi removido para não sugerir uma validação que não acontece.

**Precisão provisória em vigor, registrada conforme exige esta DR:** o estoque persiste quantidade em
`NUMERIC(15,3)` e aceita até três casas decimais para **qualquer** unidade, inclusive as contáveis.
É a mesma escala do item físico da OS (`V7`), escolhida para não criar uma terceira precisão no
sistema. Nada disso é definitivo.

### O que muda conforme a decisão

- **Opção A** (sempre inteiro): exige validação nova em catálogo, OS e estoque, e converte fluido
  para a unidade-base. A escala da coluna serve; o que muda é a regra.
- **Opção B** (três casas sempre): ratifica o que está implementado; nada muda no código.
- **Opção C** (fração só nas contínuas): exige validação por unidade nos três pontos de entrada de
  quantidade — catálogo (`minimumStock`), item físico da OS e movimentação de estoque. É a opção
  recomendada e a única que ainda não pode ser implementada sem a decisão.

---

## Impacto e bloqueio

Não bloqueia a TASK-0005. Bloqueia a definição final da quantidade de item físico em OS/orçamento, em compras e em estoque. Enquanto estiver `OPEN`, qualquer Task que introduza quantidade comercial ou movimentação deve registrar explicitamente a precisão adotada e tratá-la como provisória.

Desde a TASK-0014 o bloqueio deixou de ser prospectivo: existe movimentação de estoque em produção
com precisão provisória, e a `TASK-0014` registra isso como pendência aberta em vez de tratá-la como
fechada.

## Pergunta final

Quantidades de itens físicos devem ser sempre inteiras na unidade-base, sempre fracionadas com três casas decimais, ou fracionadas apenas para as unidades contínuas (`LITRO`, `METRO`, `QUILOGRAMA`) e inteiras para `UNIDADE`?

## Decisão final do Owner

- Data: `2026-09-22`
- Opção escolhida: **C — precisão por unidade**
- Decisão: `DECIDED`

### Regra definitiva

| Grupo | Unidades | Quantidade aceita | Válido | Inválido |
| --- | --- | --- | --- | --- |
| Contáveis | `UNIDADE`, `GALAO_5L`, `BALDE_20L` | somente inteira | `1`, `2`, `15` | `0.5 UNIDADE`, `1.5 GALAO_5L`, `2.25 BALDE_20L` |
| Contínuas | `LITRO`, `METRO`, `QUILOGRAMA` | até três casas decimais | `0.500 LITRO`, `2.750 METRO`, `1.125 QUILOGRAMA` | `0.0001 LITRO` |

Zeros à direita não contam como fração: `2.000 UNIDADE` é inteiro.

- **Persistência:** `NUMERIC(15,3)` permanece. Nenhuma migration foi criada para esta decisão.
- **Autoridade:** domínio/backend. O frontend ajusta o passo do campo e avisa antes do envio, mas não
  decide nada.
- **Pontos validados** (`INVALID_QUANTITY_FOR_UNIT`, `400`):
  - estoque mínimo do catálogo, na criação e na atualização do produto;
  - item físico lançado na OS;
  - movimentação manual de estoque — entrada, saída e ajuste.
- **Onde a regra não é reaplicada, de propósito:** baixa pela OS (herda a quantidade já validada no
  lançamento do item), estorno e devolução (repetem a quantidade do movimento original) e leitura de
  registros gravados antes da decisão. Revalidar esses casos travaria a correção de histórico legado.
- **Implementação:** `ProductUnit.countable()` / `ProductUnit.accepts()` no domínio do catálogo e o
  contrato público `ProductQuantityRule`, usado pela OS e pelo Estoque sem conhecer o enum interno.
- **Testes:** `ProductQuantityRuleTest` (unitário) e
  `Task0014InventoryIntegrationTest.countableUnitsAcceptOnlyIntegersAndContinuousUnitsUpToThreeDecimals`
  (PostgreSQL).

A quantidade do item de orçamento (`NUMERIC(19,4)`, `DR-0007`) não foi alterada por esta decisão.
