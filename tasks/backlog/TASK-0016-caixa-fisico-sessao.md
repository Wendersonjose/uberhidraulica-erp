# TASK-0016 — Caixa físico / sessão de caixa

## Identificação

- Status: `IN_PROGRESS`
- Prioridade: alta
- Criada em: `2026-09-22`
- Origem: `DR-0015`, F-10 — decisão do Owner de não aceitar `DINHEIRO` sem sessão de caixa
- Decisão complementar: `DR-0018 — Caixa físico / sessão de caixa` (`APROVADA`)
- Proprietário principal: Financeiro — `AG-06`

## Motivo

O `AG-06` §14 proíbe movimentação em dinheiro sem sessão de caixa aberta. A `TASK-0015` mantém `DINHEIRO` no catálogo de formas de pagamento, marcado com `cash_session_required`, e recusa seu uso com `409 CASH_SESSION_REQUIRED`. Esta Task remove essa recusa somente quando existir sessão válida e auditável.

## Escopo aprovado

Conforme `AG-06` §§13–17 e `DR-0018`:

- no máximo uma sessão aberta por oficina/tenant;
- abertura com saldo anterior sugerido e confirmação do saldo físico;
- fechamento manual com saldo esperado, saldo contado e divergência auditável;
- fechamento automático às 23:59 como `FECHADO_AUTOMATICAMENTE` / `NAO_CONFERIDO`;
- nova sessão pode abrir antes da conferência da sessão fechada automaticamente;
- `SUPRIMENTO` e `SANGRIA` com motivo obrigatório;
- troco como saída explícita vinculada ao recebimento;
- estornos por movimentos compensatórios, nunca por exclusão;
- `receipt` em `DINHEIRO` gera entrada física de caixa;
- `payable_payment` em `DINHEIRO` gera saída física de caixa;
- custódia física separada da contabilização financeira, sem duplicar receita/despesa.

## Permissões aprovadas

- `CASH_SESSION_OPEN`
- `CASH_SESSION_CLOSE`
- `CASH_SUPPLY`
- `CASH_WITHDRAWAL`
- `CASH_REVERSAL`

## Critérios de aceite

- [ ] migration Flyway posterior à V20 cria modelo de caixa e permissões;
- [ ] banco impede mais de uma sessão aberta simultaneamente;
- [ ] abertura/fechamento/conferência preservam trilha de auditoria;
- [ ] suprimento e sangria são imutáveis e justificadas;
- [ ] dinheiro em recebíveis e contas a pagar exige sessão aberta;
- [ ] movimentos guardam operador e origem financeira quando aplicável;
- [ ] estorno cria movimento compensatório vinculado ao original;
- [ ] troco é saída explícita vinculada ao recebimento;
- [ ] endpoints protegidos pelas permissões aprovadas;
- [ ] UI permite operar e conferir o caixa;
- [ ] testes unitários, integração PostgreSQL e frontend verdes;
- [ ] sem alteração no schema `public` do módulo de salários.

## Histórico

- 2026-09-22 — Criada a partir da F-10 da `DR-0015`.
- 2026-09-30 — `DR-0018` aprovada pelo Owner; Task liberada para implementação.
