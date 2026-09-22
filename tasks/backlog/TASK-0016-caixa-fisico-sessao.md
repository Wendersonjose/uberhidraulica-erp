# TASK-0016 — Caixa físico / sessão de caixa

## Identificação

- Status: `BACKLOG`
- Prioridade: a definir pelo Owner
- Criada em: `2026-09-22`
- Origem: `DR-0015`, F-10 — decisão do Owner de não aceitar `DINHEIRO` sem sessão de caixa
- Proprietário principal: Financeiro — `AG-06`

## Motivo

O `AG-06` §14 proíbe movimentação em dinheiro sem sessão de caixa aberta. A `TASK-0015` mantém
`DINHEIRO` no catálogo de formas de pagamento, marcado com `cash_session_required`, e recusa seu uso
em recebimento e pagamento com `409 CASH_SESSION_REQUIRED`. Esta Task existe para remover essa recusa
da forma correta, e não por exceção temporária.

## Escopo de partida (a especificar antes de qualquer código)

Conforme `AG-06` §§13–17:

- abertura, movimentações, fechamento e conferência da sessão;
- mais de um usuário autorizado na mesma sessão, com operador em cada lançamento;
- fechamento automático às 23:59 como `FECHADO_AUTOMATICAMENTE` / `NAO_CONFERIDO`, pelo saldo esperado;
- na abertura seguinte, saldo físico contado e justificativa obrigatória em caso de divergência;
- vínculo de recebimento e pagamento em `DINHEIRO` com a sessão aberta.

## Pré-requisitos

- Decision Request própria para o que o `AG-06` não define (troco, suprimento/sangria, quem abre e
  fecha, relação com o fluxo de caixa da `TASK-0015`).
- Nenhuma regra desta Task pode ser implementada como decisão provisória (instrução do Owner de
  2026-09-22 para regras financeiras materiais).

## Histórico

- 2026-09-22 — Criada a partir da F-10 da `DR-0015`.
