# DR-0024 — Financeiro completo: itens que a DR-0015 deixou fora

- Status: `OPEN` (aguarda o proprietário)
- Aberta em: 2026-11-18
- Origem: briefing do proprietário "Desenvolvimento completo do Financeiro" (2026-11-18)
- Task: `TASK-0024`
- Responsáveis: `AG-06` (Financeiro), `AG-07` (Conciliação), `AG-05` (Compras), `AG-09` (Segurança)

## Problema

O briefing pede parcelas, juros, multas, taxas e parcelamento de cartão, aprovações de contas a pagar e conciliação.
A `DR-0015`, decidida pelo proprietário, deixou explicitamente fora do Financeiro:

- **F-03:** um recebível por OS, sem parcelas;
- **F-06:** sem juros nem multa automáticos;
- **F-09:** sem parcelas de cartão, adquirente ou taxa;
- **F-12:** sem parcelas de conta a pagar, contas bancárias, conciliação ou aprovação.

Pela regra de precedência, a decisão explícita do proprietário prevalece. O briefing amplia o escopo, mas não diz **como**
cada regra funciona, e cada escolha muda modelo de dados, valores cobrados e evidência financeira. Por isso estas perguntas
ficam formalizadas antes de qualquer código. O que não depende delas, como o painel e os relatórios, segue na `TASK-0024`,
etapa A.

## Perguntas, opções e recomendação

### Q1 — Parcelas do recebível da OS
- **A:** manter um recebível por OS, com recebimento parcial (como hoje).
- **B:** um recebível por OS dividido em N parcelas com vencimentos próprios; cada parcela tem saldo, vencimento e
  status. A soma das parcelas é igual ao valor aprovado e a diferença de centavos vai na última parcela (`DR-0007`).
- Recomendação: **B**, com parcelas definidas no faturamento e alteráveis só enquanto não houver recebimento.

### Q2 — Recebível avulso (sem OS)
- **A:** não existe; todo recebível vem de uma OS.
- **B:** cadastro manual de conta a receber, com cliente, descrição, valor, vencimento e categoria de receita, para venda de
  balcão, reembolso etc.
- Recomendação: **B**, com permissão `FINANCE_RECEIVABLE_MANUAL`.

### Q3 — Juros e multa por atraso
- **A:** só acréscimo manual com motivo (como hoje).
- **B:** cálculo sugerido automaticamente na hora do recebimento: multa de X% uma única vez e juros de Y% ao mês
  *pro rata die*, com taxas configuráveis. O operador confirma ou dispensa com motivo, e o lançamento vira acréscimo
  registrado.
- **C:** cobrança automática sem confirmação.
- Recomendação: **B**, com padrão de 2% de multa e 1% ao mês de juros, configurável e podendo ser zero. Nunca altera
  o valor original.

### Q4 — Taxas e parcelamento de cartão
- **A:** sem taxa.
- **B:** cada forma de pagamento guarda a taxa percentual por número de parcelas e o prazo de repasse. O recebimento
  registra o valor bruto, a taxa e o líquido previsto, e a taxa vira despesa financeira no demonstrativo. Nenhuma
  integração com adquirente.
- Recomendação: **B**, sem integração real com a Rede (bloqueada pela `DR-0021`).

### Q5 — Parcelas e aprovação de contas a pagar
- Parcelas: **A**, cada parcela é uma conta (como hoje e como Compras já faz); ou **B**, um grupo de parcelas com
  visão consolidada. Recomendação: **B**, reaproveitando o agrupamento por compra da `TASK-0022`.
- Aprovação: **A**, sem aprovação; ou **B**, exigir aprovação de um segundo usuário acima de um valor configurável,
  com o Dono podendo aprovar sozinho. Recomendação: **B**, seguindo o padrão da aprovação segregada de Compras (V25).

### Q6 — Fornecedor na conta a pagar
- **A:** texto livre (como hoje).
- **B:** vínculo opcional com `purchasing.supplier`, mantendo o texto como snapshot.
- Recomendação: **B**.

### Q7 — Conciliação manual
Ainda não há contas bancárias nem extrato.
- **A:** adiar até haver layouts reais (`DR-0021`).
- **B:** criar contas financeiras (caixa físico, conta bancária, conta de cartão) e uma conciliação manual: o operador marca
  cada recebimento ou pagamento como conciliado contra uma referência de extrato (data, valor, identificador). Divergência
  exige motivo, e a regra de unicidade da referência impede conciliar o mesmo lançamento duas vezes.
- Recomendação: **B**, sem importar OFX ou CSV nesta etapa.

### Q8 — Demonstrativo gerencial
Confirmar que é **gerencial**, não contábil: receita bruta das OS, descontos, acréscimos, custo de peças, despesas por
categoria e resultado. Mão de obra não é custeada. Regime: faturamento pela data de emissão e despesa pela data de
cadastro (como o painel atual), com uma visão alternativa "caixa" (recebido × pago).

## Impactos

- **Q1, Q3, Q4 e Q5:** migrations novas em `finance` e mudança de contrato da API de recebíveis e contas a pagar.
- **Q7:** tabelas novas de contas e de conciliação.
- **Segurança:** permissões novas e segregação de função na aprovação.

## Decisão final

_Pendente._
