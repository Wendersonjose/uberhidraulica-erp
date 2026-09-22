# DR-0017 — Correção de desconto ou acréscimo financeiro lançado por engano

- Tipo: `FINANCIAL`
- Status: `OPEN`
- Task: `TASK-0015`
- Origem: `AG-06 — Financeiro`, na formalização da `DR-0015`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-22`

## Problema

A `DR-0015` (F-06) permite desconto e acréscimo financeiros manuais no recebível, com valor, motivo,
usuário, instante e permissão `FINANCE_ADJUST`, e proíbe sobrescrever o valor original. A F-07 define
estorno **de recebimento e de pagamento**. Nenhuma das duas diz como corrigir um **ajuste** lançado
errado — por exemplo, desconto de R$ 50,00 digitado como R$ 500,00.

As saídas possíveis mudam o histórico financeiro de formas diferentes, por isso não é detalhe técnico.

## Opções

- **A — estorno do ajuste:** registro novo, ligado ao ajuste original, total, com motivo e permissão
  (mesmo desenho da F-07). O ajuste estornado deixa de compor o valor ajustado.
- **B — ajuste compensatório:** corrigir desconto errado lançando acréscimo do mesmo valor. Não exige
  estrutura nova, mas registra como "acréscimo" algo que foi correção, e distorce os totais de
  descontos e acréscimos concedidos.
- **C — ajuste é definitivo:** não há correção no MVP.

## Comportamento enquanto a DR estiver aberta

Nada foi inventado: o ajuste é **imutável e sem estorno**. Não existe endpoint de correção. Os
ajustes aparecem no histórico do recebível com autor, instante e motivo.

## Recomendação

Opção A, pela simetria com a F-07. A recomendação não é decisão.

## Pergunta final

Desconto ou acréscimo financeiro lançado por engano deve ser (A) estornado por registro próprio,
(B) compensado por ajuste inverso ou (C) mantido sem correção no MVP?

## Decisão final do Owner

- Data: `-`
- Opção escolhida: `-`
- Decisão: `PENDENTE`
