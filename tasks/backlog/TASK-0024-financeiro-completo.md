# TASK-0024 — Financeiro completo: painel, relatórios e lacunas

## Identificação
- Status: `IN_PROGRESS` (etapa A); etapa B depende da `DR-0024`
- Criada em: 2026-11-18
- Origem: briefing do proprietário de 2026-11-18
- Proprietário: `AG-06`; apoio: `AG-07`, `AG-05`, `AG-09`, `AG-10`, `AG-11`, `AG-12`, `AG-13`
- Branch: `feat/financeiro-completo` (a partir de `2178e4a`, que está em produção)

## O que já existe (inspeção de 2026-11-18; não reconstruir)
- **Recebíveis por OS:** recebimento parcial/total idempotente, desconto e acréscimo manuais, mudança de vencimento,
  estornos e cancelamento (`TASK-0015`).
- **Contas a pagar:** categoria, pagamento parcial/total, estorno, cancelamento; parcelas vindas da NF-e de Compras
  (`TASK-0021`).
- **Caixa físico:** abertura com saldo sugerido, suprimento, sangria, troco, estorno, fechamento manual e automático
  às 23:59, conferência e divergência com motivo (`TASK-0016`).
- **Formas de pagamento** configuráveis (7 de seed), categorias de despesa, fluxo de caixa previsto × realizado e painel
  gerencial (faturamento, recebido, a receber, vencido, custo de peças, despesas, lucro e margens).
- **Permissões:** `FINANCE_VIEW`, `FINANCE_RECEIVE`, `FINANCE_PAYABLE`, `FINANCE_ADJUST`, `FINANCE_REVERSE`,
  `FINANCE_CONFIG`, `CASH_*`.

## Etapa A — sem decisão nova (somente leitura sobre dados existentes; sem migration)
| # | Entrega | Critério de aceite |
|---|---|---|
| A1 | Painel: saldo do caixa aberto, contas a receber e a pagar vencidas e a vencer (7 e 30 dias), gráfico de fluxo diário e filtros por período | valores batem com as listas de recebíveis e contas; sem caixa aberto, mostra "caixa fechado" |
| A2 | Inadimplência: saldo em aberto por faixa de atraso (a vencer, 1–30, 31–60, 61–90, >90) e por cliente | a soma das faixas é igual ao total em aberto; cancelado e quitado ficam fora |
| A3 | Despesas por categoria: cadastrado no período e pago no período | a soma é igual à das despesas do painel e ao realizado de saídas do fluxo |
| A4 | Resultado por OS: receita, descontos, acréscimos, líquido, recebido, em aberto, custo de peças e resultado | o custo de peças é o histórico das movimentações (devoluções descontadas) |
| A5 | Demonstrativo gerencial (competência e caixa) | receita − descontos + acréscimos − custo de peças − despesas = resultado; mesmos números do painel |
| A6 | Contas a receber e a pagar no relatório: listas por período, com totais | exportação CSV no navegador |
| A7 | Tela "Relatórios" no frontend, com estados de carregando, vazio e erro, seguindo o design system | testes de componente |

## Etapa B — depende da DR-0024
Parcelas de recebível (Q1), recebível avulso (Q2), juros e multa sugeridos (Q3), taxas e parcelas de cartão (Q4),
parcelas consolidadas e aprovação de contas a pagar (Q5), vínculo com fornecedor (Q6), contas financeiras e conciliação
manual (Q7).

## Testes exigidos
Unidade, integração com PostgreSQL real (Testcontainers), permissões (`FINANCE_VIEW` obrigatório; perfil sem ele recebe
403), arquitetura modular (Spring Modulith `verify`), regressão completa e frontend (Vitest).
