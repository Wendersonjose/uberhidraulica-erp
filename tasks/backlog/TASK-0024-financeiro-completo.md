# TASK-0024 — Financeiro completo: painel, relatórios e lacunas

## Identificação
- Status: `IN_PROGRESS` — etapa A entregue; etapa B em andamento (`DR-0024` decidida em 2026-11-18)
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

## Etapa B — DR-0024 decidida (todas as recomendações)

| # | Entrega | Migration | Regras e critério de aceite |
|---|---|---|---|
| B1 | Parcelas do recebível (Q1) | V28 | Todo recebível tem um plano de parcelas versionado (o legado vira plano 1 com parcela única). A soma é igual ao valor original, conferida pelo banco no fim da transação. Novo plano só sem recebimento ativo, com motivo, e o anterior fica no histórico. Recebimento e desconto quitam as parcelas mais antigas primeiro. Acréscimo fica à parte, pago depois do principal. O vencimento corrente é o da primeira parcela com saldo. Vencimento alterável por parcela, com histórico |
| B2 | Recebível avulso (Q2) | V28 | Cliente, descrição, categoria de receita, valor e vencimento, com permissão `FINANCE_RECEIVABLE_MANUAL`. Cancelamento só sem recebimento ativo. Entra no painel e no demonstrativo como "outras receitas", fora do resultado por OS |
| B3 | Juros e multa sugeridos (Q3) | V29 | Multa (padrão 2%) uma vez por parcela e juros (padrão 1% a.m.) *pro rata die* sobre o principal vencido, em HALF_UP (DR-0007). Os juros contam a partir do último cálculo decidido. Aplicar vira acréscimo de origem `LATE_CHARGE`; dispensar exige motivo. O servidor recalcula e recusa se o valor mudou. O valor original nunca muda. Estornar o acréscimo devolve a parcela à sugestão |
| B4 | Taxas e parcelas de cartão (Q4) | V30 | Tabela de taxas versionada por forma de pagamento: número de parcelas, taxa % e prazo de repasse. O recebimento grava parcelas, taxa, valor da taxa, líquido e data prevista do repasse. A taxa entra no demonstrativo como despesa financeira. Sem adquirente |
| B5 | Parcelas e aprovação de contas a pagar (Q5) | V31 | Lançamento parcelado cria um grupo com N contas e visão consolidada. Acima do limite configurável (0 desliga), a conta exige aprovação de outro usuário com `FINANCE_PAYABLE_APPROVE`; quem tem `FINANCE_PAYABLE_APPROVE_OWN` (Dono) aprova a própria. Conta não aprovada não é paga. As contas de Compras já passaram pela aprovação do pedido e ficam dispensadas |
| B6 | Fornecedor na conta a pagar (Q6) | V31 | `supplier_id` opcional, validado por contrato que o Financeiro declara e Compras implementa (sem ciclo de módulos). O nome fica como snapshot |
| B7 | Contas financeiras e conciliação manual (Q7) | V32 | Contas de caixa, banco e cartão. A conciliação liga um recebimento ou pagamento a uma referência de extrato (data, valor, identificador). Diferença exige motivo. O mesmo lançamento não é conciliado duas vezes, e a mesma referência não se repete na conta. Desfazer é estorno. Lançamento conciliado não é estornado antes de desfazer a conciliação. Permissão `FINANCE_RECONCILE` |
| B8 | Painel e demonstrativo unificados (Q8) | — | O painel passa a calcular lucro e resultado sobre a receita líquida (descontos e acréscimos), igual ao demonstrativo |
| B9 | Frontend | — | Telas e formulários de B1 a B7, com testes de componente |

## Testes exigidos
Unidade, integração com PostgreSQL real (Testcontainers), permissões (`FINANCE_VIEW` obrigatório; perfil sem ele recebe
403), arquitetura modular (Spring Modulith `verify`), regressão completa e frontend (Vitest).
