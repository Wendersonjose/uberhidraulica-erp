# DR-0015 — Financeiro: geração do recebível da OS, estornos e fluxo de caixa

- Tipo: `FINANCIAL`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0015`
- Origem: `AG-06 — Financeiro`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner

## Problema

Os cartões financeiros pedem contas a receber ligadas à OS, registro de recebimentos com formas de
pagamento, contas a pagar/despesas e uma visão de fluxo de caixa que diferencie previsto de
realizado. O README acrescenta: competência não é caixa, finalizar a OS não é receber, e valores
derivados devem ser calculados a partir dos lançamentos.

Faltam decisões sobre quando o recebível nasce, com que valor, e o que acontece quando a OS muda.

## Decisão provisória

1. **O recebível nasce na finalização da OS**, com valor igual ao total praticado da OS
   (serviços + itens físicos, cada linha já arredondada em duas casas). Finalizar não recebe nada:
   o recebível nasce em aberto.
2. **Um recebível por OS.** Se a OS já tiver recebível, finalizar de novo não cria outro.
   Recebíveis avulsos (sem OS) podem ser criados manualmente.
3. **Cancelar a OS** cancela o recebível ainda sem recebimentos; com recebimento registrado, o
   recebível permanece e o cancelamento exige estorno explícito do recebimento — dinheiro recebido
   não desaparece por mudança de status.
4. **Recebimento** registra valor, forma de pagamento, data do recebimento, usuário e observações.
   Pagamento parcial é permitido; a soma dos recebimentos nunca ultrapassa o saldo do recebível.
   O saldo é sempre derivado: `valor original − descontos + acréscimos − recebido`.
5. **Estorno** de recebimento é um registro próprio, ligado ao original, que devolve o saldo; o
   recebimento original nunca é apagado nem editado.
6. **Contas a pagar e despesas** seguem o mesmo desenho: lançamento com descrição, fornecedor,
   categoria, valor, vencimento; baixas totais ou parciais com forma de pagamento; estorno próprio;
   cancelamento preserva o histórico.
7. **Fluxo de caixa** é sempre calculado: no período pedido, "previsto" soma vencimentos em aberto e
   "realizado" soma recebimentos e pagamentos efetivos, sem nenhuma coluna de saldo consolidado.
8. **Formas de pagamento** fixas nesta Task: `DINHEIRO`, `PIX`, `CARTAO_CREDITO`, `CARTAO_DEBITO`,
   `TRANSFERENCIA`, `BOLETO`, `OUTRO`.

## Pergunta final

Confirma o recebível criado na finalização da OS pelo total praticado, um por OS, e o cancelamento
bloqueado quando já existe recebimento?
