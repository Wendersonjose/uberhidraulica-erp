# DR-0018 — Caixa físico / sessão de caixa

## Status

`PENDENTE DE DECISÃO DO OWNER`

## Contexto

A `TASK-0016` existe para remover a recusa atual de operações em `DINHEIRO` com `409 CASH_SESSION_REQUIRED` somente depois que o domínio de sessão de caixa estiver definido de forma explícita.

O `AG-06` já estabelece os princípios obrigatórios:

- não permitir movimentação em dinheiro sem sessão de caixa aberta;
- abertura, movimentações, fechamento e conferência da sessão;
- mais de um usuário autorizado na mesma sessão, registrando o operador de cada lançamento;
- fechamento automático às 23:59 como `FECHADO_AUTOMATICAMENTE` / `NAO_CONFERIDO`, pelo saldo esperado;
- na abertura seguinte, informar saldo físico contado e exigir justificativa quando houver divergência;
- recebimentos e pagamentos em `DINHEIRO` devem ficar vinculados à sessão aberta.

Ainda faltam regras materiais que não podem ser inventadas pela implementação.

## Decisões necessárias

### D1 — Quem pode abrir e fechar uma sessão de caixa?

Escolher uma política:

- A. somente `OWNER`/administrador;
- B. perfis financeiros autorizados por permissão específica;
- C. qualquer usuário com permissão de movimentar caixa.

Registrar também se abertura e fechamento usam permissões distintas.

### D2 — Pode existir mais de uma sessão de caixa aberta ao mesmo tempo?

Definir se o MVP terá:

- A. uma única sessão de caixa aberta por oficina/tenant;
- B. uma sessão por usuário;
- C. uma sessão por caixa físico/posto de atendimento.

Se houver múltiplas sessões, definir como o sistema escolhe a sessão em recebimentos/pagamentos em dinheiro.

### D3 — Saldo inicial

Definir se a abertura:

- A. sempre recebe saldo inicial contado manualmente;
- B. sugere o saldo final esperado da sessão anterior, mas exige confirmação;
- C. carrega automaticamente o saldo esperado anterior, permitindo ajuste justificado.

### D4 — Troco

Definir se troco de venda/recebimento:

- A. é tratado apenas como valor líquido recebido, sem lançamento separado;
- B. gera movimentação explícita de saída de caixa vinculada ao recebimento;
- C. fica fora do MVP.

### D5 — Suprimento e sangria

Definir se o MVP permitirá movimentações manuais de caixa além de recebimentos/pagamentos:

- `SUPRIMENTO` (entrada manual);
- `SANGRIA` (retirada manual).

Se sim, definir se motivo/observação é obrigatório e quais perfis podem executar cada operação.

### D6 — Fechamento manual e conferência

Definir o fluxo desejado:

- saldo esperado calculado pelo sistema;
- usuário informa saldo físico contado;
- divergência exige justificativa;
- decidir se uma divergência impede o fechamento ou apenas gera registro/auditoria;
- decidir se o mesmo usuário que movimentou pode conferir/fechar.

### D7 — Fechamento automático às 23:59

Já está definido que a sessão deve ficar `FECHADO_AUTOMATICAMENTE` / `NAO_CONFERIDO` pelo saldo esperado.

Falta decidir:

- se uma nova sessão pode ser aberta no dia seguinte antes da conferência da anterior;
- se a conferência posterior altera apenas o status da sessão ou também gera ajuste de caixa quando houver divergência.

### D8 — Cancelamento/estorno de movimentação em dinheiro

Definir se estorno de recebimento/pagamento em dinheiro:

- deve ocorrer obrigatoriamente na mesma sessão enquanto ela estiver aberta;
- pode ocorrer em sessão posterior, com vínculo à movimentação original;
- exige permissão adicional ou justificativa.

A recomendação técnica é nunca apagar movimentações de caixa; correções devem ser registradas por movimentos compensatórios/auditáveis.

### D9 — Relação com o fluxo financeiro da TASK-0015

Confirmar que:

- `receipt` em `DINHEIRO` cria uma entrada de caixa na sessão aberta;
- `payable_payment` em `DINHEIRO` cria uma saída de caixa na sessão aberta;
- a movimentação de caixa guarda referência à operação financeira de origem;
- o fluxo de caixa contábil/financeiro continua sendo derivado das operações de `finance`, e a sessão de caixa representa a custódia física do dinheiro, sem duplicar receita/despesa.

## Proposta técnica após aprovação

Sem implementar antes da decisão do Owner, a tendência é introduzir no schema `finance` entidades equivalentes a:

- `cash_session`;
- `cash_movement`;
- tipos/status explícitos para abertura, movimento, fechamento e conferência;
- FKs opcionais para `receipt` / `payable_payment` e referência auditável da origem;
- migration Flyway nova, posterior à V20;
- permissões IAM específicas para abrir, movimentar, conferir e fechar caixa;
- endpoints e UI de caixa;
- testes unitários e de integração PostgreSQL para invariantes de saldo e concorrência.

Essa estrutura é apenas direção técnica; nomes finais, cardinalidades e permissões serão definidos depois das decisões acima.

## Impacto da não decisão

Enquanto esta DR não for respondida, `DINHEIRO` deve continuar recusado quando não houver sessão de caixa válida, preservando a regra atual de segurança financeira da `TASK-0015`.

## Origem

- `TASK-0016 — Caixa físico / sessão de caixa`
- `DR-0015 — Financeiro / recebíveis / despesas`
- `AG-06 — Financeiro`, §§13–17

## Histórico

- 2026-09-30 — DR criada para explicitar as decisões financeiras materiais necessárias antes da implementação da TASK-0016.
