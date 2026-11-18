# Homologação — TASK-0024 etapa B (DR-0024: parcelas, avulso, juros e multa, cartão, aprovação, fornecedor e conciliação)

Branch: `feat/financeiro-completo`. Decisão: `DR-0024` aceita integralmente pelo proprietário em 2026-11-18.
**Nada disto foi aplicado na produção.** As migrations V28 a V32 só rodaram em PostgreSQL descartável (Testcontainers e Docker local).

## Funcionalidades

| # | Recurso | Endpoints | Tela |
|---|---|---|---|
| B1 | Plano de parcelas versionado; vencimento por parcela; saldo e situação por parcela | `PUT /api/finance/receivables/{id}/installments`, `PUT …/due-date` (com `installmentNumber`) | Recebível → Parcelas / Parcelar / Alterar vencimento |
| B2 | Conta a receber avulsa, com categoria de receita (`FINANCE_RECEIVABLE_MANUAL`) | `POST /api/finance/receivables`, `POST …/{id}/cancel`, `/api/finance/revenue-categories` | Contas a receber → Nova conta a receber |
| B3 | Juros e multa sugeridos, aplicados ou dispensados com motivo | `GET …/{id}/late-charge?on=`, `POST …/{id}/late-charge`, `GET …/{id}/late-charges` | Recebível → Juros e multa por atraso |
| B4 | Taxa e parcelas por forma de pagamento; taxa congelada no recebimento | `GET/PUT /api/finance/payment-methods/{id}/fees`, `installments` no recebimento | Configurações → Taxas e parcelamento; Registrar recebimento |
| B5 | Despesa parcelada em grupo; aprovação segregada acima do limite | `POST /api/finance/payables/installments`, `GET/POST /api/finance/payable-groups/{id}[/approval]`, `POST /api/finance/payables/{id}/approval` | Nova conta a pagar (Parcelas); Conta → Aguardando aprovação; Grupo |
| B6 | Fornecedor do cadastro de Compras, com nome em snapshot | `supplierId` em `POST /api/finance/payables` | Nova conta a pagar → Fornecedor |
| B7 | Contas financeiras e conciliação manual (`FINANCE_RECONCILE`) | `/api/finance/accounts`, `GET /api/finance/reconciliation/entries`, `POST /api/finance/reconciliations[/{id}/reversal]` | Financeiro → Conciliação; Configurações → Contas financeiras |
| B8 | Painel e demonstrativo com a mesma regra: receita líquida, outras receitas e taxas de cartão | `/api/finance/dashboard`, `/api/finance/reports/income-statement` | Resumo financeiro; Relatórios |

## Migrations

| Versão | Conteúdo | Proteções no banco |
|---|---|---|
| V28 | `revenue_category`; recebível com `origin`, descrição, categoria e chave; plano de parcelas; vencimento por parcela; origem do ajuste; `FINANCE_RECEIVABLE_MANUAL` | soma do plano = valor original (trigger adiada); plano sem parcela recusado; valor e número da parcela imutáveis; campos por origem conferidos por CHECK |
| V29 | Configurações de multa (2%), juros (1% a.m.) e limite de aprovação (0); cálculo decidido e as suas parcelas | faixas e formato das configurações; aplicado ⇔ acréscimo; total = multa + juros |
| V30 | Tabela de taxas versionada; parcelas, taxa, valor da taxa e repasse no recebimento | taxa < 100%; conjunto completo ou nada; taxa < valor |
| V31 | Grupo de contas a pagar; fornecedor; origem; aprovação; `FINANCE_PAYABLE_APPROVE` e `_OWN` | pagamento de conta pendente recusado por trigger; uma aprovação por conta |
| V32 | Contas financeiras; conciliação; `FINANCE_RECONCILE` | um lançamento e uma referência de extrato com no máximo uma conciliação ativa; diferença exige motivo; linha só muda para estornar, uma vez; estorno de lançamento conciliado recusado |

Rollback manual sem uso: `scripts/ops/rollback-v28-v32.sql`. Ele recusa e não muda nada se a etapa B já foi usada, e é
coberto pelo `MigrationUpgradeFromProductionIntegrationTest`. Esse teste aplica V22–V32 sobre um banco na V21 com dados,
volta para V27 e depois para V21 sem resíduo, e promove de novo.

## Regras que a homologação deve conferir

- **Parcelas:** recebimentos e descontos quitam as parcelas mais antigas primeiro. O acréscimo é saldo à parte, pago depois do principal.
  O vencimento do recebível é o da primeira parcela com saldo. Novo plano só sem recebimento ativo.
- **Juros e multa:** a base é o principal vencido de cada parcela. A multa incide uma vez por parcela. Os juros contam do
  vencimento ou do último cálculo decidido, em mês comercial de 30 dias, com HALF_UP (DR-0007). Estornar o acréscimo devolve
  a sugestão. O servidor recusa se o total mudou desde a tela (`LATE_CHARGE_CHANGED`).
- **Cartão:** forma sem tabela de taxas não parcela (`INSTALLMENTS_NOT_ALLOWED`). Dinheiro não tem taxa. Trocar a tabela
  não reescreve recebimentos.
- **Aprovação:** limite 0 desliga. Acima do limite, quem lançou não aprova (403 `PAYABLE_APPROVAL_SEGREGATION`), salvo o
  Dono. As contas vindas de Compras ficam dispensadas, porque o pedido já foi aprovado (V25). No grupo, o limite vale para o total.
- **Conciliação:** o valor esperado do cartão é o líquido da taxa. Desfeita a conciliação, a referência pode ser reutilizada.

## Testes

Os números finais estão no relatório de entrega e na descrição do PR.
- Backend: `Task0024FinanceStageBIntegrationTest` (13 cenários, PostgreSQL real), `ReceivableInstallmentDomainTest`,
  `MigrationUpgradeFromProductionIntegrationTest`, `ModularityTest` e a suíte completa.
- Frontend: `finance-stage-b.test.tsx` (14 cenários) e a suíte completa, além de `tsc`, `oxlint` e build.

## Como homologar localmente

1. `bash scripts/dev/demo-financeiro.sh up`. No Windows, se o Tomcat acusar loopback, use `-Djdk.net.unixdomain.tmpdir=D:\tmp`.
2. `bash scripts/dev/demo-financeiro.sh seed` e depois `bash scripts/dev/demo-financeiro.sh seed-b`.
3. `bash scripts/dev/demo-financeiro.sh check-b` deve imprimir só linhas `OK`.
4. `cd frontend && npm run dev` e entrar com as credenciais **locais** do cabeçalho do script. Conferir:
   - o recebível parcelado;
   - a sugestão de juros e multa;
   - o recebimento em 3x;
   - a venda avulsa;
   - o grupo pendente de aprovação: aprovar com outro usuário;
   - a conciliação.
5. `bash scripts/dev/demo-financeiro.sh down` apaga tudo.
