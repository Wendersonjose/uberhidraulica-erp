# Homologação — TASK-0024 etapa A (painel ampliado e relatórios financeiros)

Branch: `feat/financeiro-completo`. Não há migration nesta etapa: os relatórios só leem as tabelas existentes.

## O que foi entregue

| Recurso | Endpoint (todos `GET`, exigem `FINANCE_VIEW`) | Tela |
|---|---|---|
| Posição de hoje: caixa aberto, a receber e a pagar (vencido, em 7 e em 30 dias, em aberto) | `/api/finance/reports/overview?from&to` | Financeiro → Resumo financeiro (topo) |
| Fluxo diário realizado (gráfico) | `/api/finance/cash-flow` (já existia) | Resumo financeiro (rodapé) |
| Demonstrativo gerencial: competência e caixa | `/api/finance/reports/income-statement?from&to` | Financeiro → Relatórios → Demonstrativo gerencial |
| Resultado por OS, com custo histórico de peças | `/api/finance/reports/work-orders?from&to` | Relatórios → Resultado por OS (CSV) |
| Despesas por categoria: cadastrado e pago | `/api/finance/reports/expenses-by-category?from&to` | Relatórios → Despesas por categoria (gráfico) |
| Inadimplência por faixa de atraso e por cliente | `/api/finance/reports/aging` | Relatórios → Inadimplência |
| Contas a receber e a pagar por vencimento | `/api/finance/reports/receivables` e `/payables?from&to` | Relatórios → Contas a receber / a pagar (CSV) |

## Testes

- **Backend:** suíte completa com **494 testes, 0 falhas** (PostgreSQL real via Testcontainers, incluindo `ModularityTest`).
  São 5 testes novos em `Task0024FinanceReportsIntegrationTest`:
  - os relatórios fecham com o painel e com os lançamentos;
  - estornos e cancelamentos ficam fora das somas;
  - períodos inválidos dão 400;
  - sem `FINANCE_VIEW` a resposta é 403, e sem sessão é 401.
- **Frontend:** **245 testes, 0 falhas** (Vitest), sendo 7 novos em `finance-reports.test.tsx`. O `tsc`, o `oxlint` e o build passaram.

## Evidência ponta a ponta (backend + frontend reais, banco local descartável, 2026-11-18)

Os dados foram criados pela API com `scripts/dev/demo-financeiro.sh seed`:
- 3 OS faturadas: R$ 1.850,00, R$ 920,00 (vencida há 45 dias) e R$ 380,00;
- desconto de R$ 50,00 e recebimentos de R$ 1.000,00 (PIX) e R$ 380,00 (dinheiro, com troco de R$ 20,00);
- 3 despesas: R$ 2.500,00 paga, R$ 640,35 vencendo em 5 dias e R$ 310,00 vencendo em 20 dias;
- caixa aberto com R$ 200,00 e sangria de R$ 100,00.

| Tela | Exibido | Conferência |
|---|---|---|
| Caixa físico | R$ 480,00 | 200 + 400 − 20 − 100 |
| A receber em aberto / vencido / 7 dias | R$ 1.720,00 / R$ 920,00 / R$ 800,00 | 1.850 − 50 − 1.000 = 800; 920 vencida |
| A pagar em aberto / 7 dias / 30 dias | R$ 950,35 / R$ 640,35 / R$ 950,35 | 640,35 + 310 |
| Demonstrativo | receita líquida R$ 3.100,00; despesas R$ 3.450,35; resultado −R$ 350,35; caixa −R$ 1.120,00 | 3.150 − 50; 1.380 − 2.500 |
| Inadimplência | A vencer R$ 800,00; 31–60 dias R$ 920,00; total R$ 1.720,00 | igual ao painel |

## Diferença conhecida (não é defeito novo)

O painel antigo (TASK-0018) calcula lucro bruto e resultado sobre o **faturamento bruto**, sem descontos. O demonstrativo usa a
**receita líquida**. Na demonstração, o painel mostra −R$ 300,35 e o demonstrativo −R$ 350,35. A diferença são os R$ 50,00 de
desconto. Unificar a regra depende da DR-0024, Q8.

## Como homologar

1. `bash scripts/dev/demo-financeiro.sh up`. No Windows, se o Tomcat acusar "Unable to establish loopback connection", rode
   o `java -jar` com `-Djdk.net.unixdomain.tmpdir=D:\tmp`.
2. `bash scripts/dev/demo-financeiro.sh seed`.
3. `cd frontend && npm run dev`, abrir http://localhost:5173 e entrar com as credenciais **locais** do cabeçalho do script.
4. Conferir a tabela acima; trocar o período; exportar os CSVs; testar com um usuário com `FINANCE_VIEW` negado.
5. `bash scripts/dev/demo-financeiro.sh down` apaga tudo.

## Pendências (etapa B, depende da DR-0024)

Parcelas de recebível, recebível avulso, juros e multa sugeridos, taxas e parcelas de cartão, parcelas e aprovação de contas
a pagar, vínculo com fornecedor e conciliação manual com contas financeiras.
