# DR-0018 — Caixa físico / sessão de caixa

## Status

`APROVADA PELO OWNER`

## Contexto

A `TASK-0016` existe para remover a recusa atual de operações em `DINHEIRO` com `409 CASH_SESSION_REQUIRED` somente depois que o domínio de sessão de caixa estiver definido de forma explícita.

O `AG-06` já estabelece os princípios obrigatórios:

- não permitir movimentação em dinheiro sem sessão de caixa aberta;
- abertura, movimentações, fechamento e conferência da sessão;
- mais de um usuário autorizado na mesma sessão, registrando o operador de cada lançamento;
- fechamento automático às 23:59 como `FECHADO_AUTOMATICAMENTE` / `NAO_CONFERIDO`, pelo saldo esperado;
- na abertura seguinte, informar saldo físico contado e exigir justificativa quando houver divergência;
- recebimentos e pagamentos em `DINHEIRO` devem ficar vinculados à sessão aberta.

## Decisões aprovadas

### D1 — Abertura e fechamento

Perfis financeiros autorizados operam caixa por permissões específicas e distintas:

- `CASH_SESSION_OPEN` — abrir sessão;
- `CASH_SESSION_CLOSE` — fechar/conferir sessão.

A autorização é por permissão, não por perfil fixo.

### D2 — Cardinalidade da sessão

No MVP existe no máximo **uma sessão de caixa aberta por oficina/tenant**. Recebimentos e pagamentos em dinheiro usam essa sessão aberta, sem escolha manual de caixa.

### D3 — Saldo inicial

Na abertura, o sistema sugere o saldo final esperado da sessão anterior, mas o usuário deve informar/confirmar o saldo físico inicial contado. Divergência em relação ao valor sugerido exige justificativa e fica auditável.

### D4 — Troco

Troco é uma movimentação explícita de saída de caixa vinculada ao recebimento original. O recebimento e o troco permanecem rastreáveis separadamente.

### D5 — Suprimento e sangria

O MVP permite:

- `SUPRIMENTO` — entrada manual de dinheiro;
- `SANGRIA` — retirada manual de dinheiro.

Ambos exigem motivo obrigatório e permissões específicas:

- `CASH_SUPPLY`;
- `CASH_WITHDRAWAL`.

### D6 — Fechamento manual e conferência

No fechamento manual:

- o sistema calcula o saldo esperado;
- o usuário informa o saldo físico contado;
- divergência exige justificativa;
- divergência não impede o fechamento, mas fica registrada e auditável;
- o mesmo usuário que movimentou o caixa pode fechar, desde que tenha `CASH_SESSION_CLOSE`.

### D7 — Fechamento automático às 23:59

A sessão é marcada como `FECHADO_AUTOMATICAMENTE` / `NAO_CONFERIDO`, com saldo esperado calculado pelo sistema.

No dia seguinte:

- uma nova sessão pode ser aberta antes da conferência da anterior;
- a conferência posterior não reescreve movimentos antigos;
- divergência fica registrada na sessão anterior;
- eventual correção física entra na sessão atualmente aberta como ajuste auditável, com justificativa.

### D8 — Estorno/cancelamento de movimento em dinheiro

Movimentos de caixa nunca são apagados. Estorno:

- gera movimento compensatório vinculado ao original;
- pode ocorrer em sessão posterior;
- exige justificativa;
- exige `CASH_REVERSAL`.

### D9 — Relação com o Financeiro

- `receipt` em `DINHEIRO` cria uma entrada de caixa na sessão aberta;
- `payable_payment` em `DINHEIRO` cria uma saída de caixa na sessão aberta;
- a movimentação de caixa guarda referência à operação financeira de origem;
- o fluxo financeiro/contábil continua derivado das operações de `finance`;
- a sessão de caixa representa a custódia física do dinheiro e não duplica receita ou despesa.

## Direção técnica aprovada

A implementação da `TASK-0016` deve permanecer no módulo/schema `finance`, com:

- `cash_session`;
- `cash_movement`;
- tipos/status explícitos;
- referências auditáveis para `receipt` e `payable_payment` quando aplicável;
- migration Flyway posterior à V20;
- permissões IAM específicas de caixa;
- endpoints e UI de caixa;
- testes unitários e de integração PostgreSQL para saldo, invariantes e concorrência.

Regras adicionais não documentadas aqui não devem ser inventadas pela implementação.

## Origem

- `TASK-0016 — Caixa físico / sessão de caixa`
- `DR-0015 — Financeiro / recebíveis / despesas`
- `AG-06 — Financeiro`, §§13–17

## Histórico

- 2026-09-30 — DR criada para explicitar as decisões financeiras materiais necessárias antes da implementação da TASK-0016.
- 2026-09-30 — Owner aprovou D1=B, D2=A, D3=B, D4=B, D5=A, D6=A, D7=A, D8=A e D9=A; implementação liberada.
