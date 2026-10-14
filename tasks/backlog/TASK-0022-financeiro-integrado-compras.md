# TASK-0022 — Financeiro integrado a Compras

## Identificação

- Status: `BACKLOG` (depende de `TASK-0021` I3)
- Criada em: `2026-10-13`
- Origem: briefing do proprietário de 2026-10-13; `DR-0021`
- Proprietário principal: Financeiro — `AG-06`

## Escopo (a detalhar em requisito/domínio antes de qualquer código)

Reaproveitar e ampliar o módulo `finance` existente (`TASK-0015`/`0016`), sem quebrar seus fluxos:

- contas a pagar originadas de compra, com parcelas e vencimentos agrupados por compra (hoje cada parcela é um `payable`);
- pagamentos parciais e integrais (já existem) com visão por compra;
- formas e condições de pagamento do fornecedor;
- contas bancárias (hoje só caixa físico) e categorias/centros de custo (centro de custo ainda não existe; `AG-06` §27);
- caixa diário (existe, `V21`);
- fluxo de caixa previsto × realizado (existe parcialmente);
- conciliação financeira com o Contábil (`TASK-0023`); despesas e relatórios gerenciais.

## Regras fixas

Competência ≠ conta a pagar ≠ pagamento ≠ movimentação bancária ≠ conciliação (`AG-06` §4). Parcelamento não divide o custo
econômico (`AG-06` §7). Dinheiro `BigDecimal`/`NUMERIC`; estorno, nunca exclusão; sem alteração retroativa silenciosa.

## Decision Requests previstas

Centro de custo e rateio (`AG-06` §28); política de pagamento parcial por obrigação; vínculo conta bancária × forma de pagamento.
