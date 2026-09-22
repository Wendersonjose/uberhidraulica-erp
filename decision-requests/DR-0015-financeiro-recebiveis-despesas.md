# DR-0015 — Financeiro: recebíveis da OS, recebimentos, contas a pagar e fluxo de caixa

- Tipo: `FINANCIAL`
- Status: `DECIDED`
- Task: `TASK-0015`
- Origem: `AG-06 — Financeiro`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Reescrita em: `2026-09-22`
- Decidida em: `2026-09-22`, pelo Owner, após revisão das perguntas F-01 a F-13

## Por que esta DR foi reescrita

A versão de `2026-09-17` registrava oito regras como `DECIDED_PROVISIONALLY` (vencimento, valor do
recebível, estorno, baixa parcial, formas de pagamento, regime do fluxo de caixa). O `AG-06` é
explícito: "não pode inventar regra financeira", e o Owner determinou em `2026-09-22` que regra
financeira material não é implementada como decisão provisória. Aquela versão fica **revogada**. Nada
dela foi implementado.

Esta versão separa o que já está decidido por fonte aprovada do que só o Owner pode decidir.

---

## 1. O que já está decidido por fonte aprovada (não é pergunta)

| Regra | Fonte |
| --- | --- |
| Recebível pode referenciar a OS de origem | cartão "Contas a receber da ordem de serviço" |
| Recebível preserva valor original, descontos/acréscimos e saldo | idem |
| Finalizar a OS não implica pagamento; OS pode fechar com recebível em aberto | idem; `AG-06` §8 |
| Cancelamento respeita o histórico financeiro e usa estorno quando necessário | idem |
| Recebimento total ou parcial, com data do recebimento | cartão "Registro de recebimentos e formas de pagamento" |
| Formas: dinheiro, PIX, cartão e outras **configuráveis** | idem |
| Uma OS pode ser paga com várias formas ao mesmo tempo | `AG-06` §9 |
| Atualizar saldo sem apagar lançamentos; registrar usuário responsável | cartão de recebimentos |
| Estorno é registro próprio e auditável; preserva a venda original | cartão de recebimentos; `AG-06` §43 |
| Conta a pagar: descrição/fornecedor, categoria, valor, vencimento, situação | cartão "Contas a pagar e despesas" |
| Cancelamento de conta a pagar não apaga histórico | idem |
| Fluxo de caixa: filtros por período, categoria e situação; previsto ≠ realizado | cartão "Fluxo de caixa e visão financeira" |
| Valores derivados calculados dos lançamentos, sem segunda fonte de verdade | idem |
| Competência ≠ conta ≠ pagamento ≠ movimentação bancária ≠ conciliação | `README`; `AG-06` §4 |
| Dinheiro em `BigDecimal`/`NUMERIC`; 4 casas internas, 2 cobradas, `HALF_UP`, soma de parcelas arredondadas | `DR-0007` |
| Oficina única, sem multi-tenant | `DR-0009` |

## 2. O que esta DR **não** pergunta, por estar fora da TASK-0015

Conciliação bancária e de adquirente (`AG-07`), taxas de cartão/Rede, fatura de cartão corporativo,
contas bancárias e transferências, centro de custo e rateio, despesa recorrente, comissão,
rentabilidade, Simples Nacional, NFS-e, plano de contas/vínculo contábil. Proposta: nenhuma dessas
existe na TASK-0015 e nenhuma estrutura é criada "para depois". **Pergunta F-12** confirma o corte.

---

## 3. Perguntas ao Owner

Cada pergunta traz opções e uma recomendação. **Recomendação não é decisão.**

### F-01 — Quando o recebível da OS nasce

- **A** — automaticamente ao **finalizar** a OS.
- **B** — automaticamente ao **entregar** a OS.
- **C** — manualmente, por ação "gerar cobrança" na OS finalizada.

Recomendação: A. Finalizar é o momento em que o serviço está concluído (`AG-06` §10) e o cartão já
diz que finalizar não é receber; o recebível nasce em aberto.

### F-02 — Com que valor o recebível da OS nasce

Hoje existem **dois** valores comerciais que não estão ligados entre si: o total dos itens lançados
na OS (serviços + itens físicos, sem desconto) e o total do orçamento aprovado (com desconto por item
e aprovação parcial). O vínculo item de orçamento × item da OS é a `DR-0008`, ainda `OPEN`.

- **A** — total dos itens lançados na OS.
- **B** — total aprovado do orçamento da OS.
- **C** — informado pelo usuário ao gerar a cobrança, com o total da OS como sugestão.

Recomendação: nenhuma segura enquanto a `DR-0008` estiver aberta. A é o único valor sempre existente;
B é o que o cliente aprovou. **Se B, a DR-0008 precisa ser decidida antes.**

### F-03 — Um ou mais recebíveis por OS; parcelamento

- **A** — um recebível por OS, sem parcelamento no MVP.
- **B** — um recebível por OS, dividido em N parcelas com vencimentos próprios.
- **C** — vários recebíveis livres por OS.

Se B: parcelas iguais com a diferença de centavos na **última** parcela, ou na **primeira**?

Recomendação: A no MVP, desde que o recebimento parcial (F-05) cubra o pagamento em partes.

### F-04 — Vencimento

- **A** — vencimento = data de finalização da OS (à vista).
- **B** — vencimento informado pelo usuário, obrigatório.
- **C** — prazo padrão configurável em dias, editável por recebível.

Mesma pergunta para conta a pagar: vencimento sempre obrigatório? (o cartão lista vencimento como
informação, sem dizer se é obrigatório).

### F-05 — Recebimento parcial e excedente

O parcial está aprovado. Falta decidir o **excedente**:

- **A** — recebimento maior que o saldo é recusado.
- **B** — excedente vira troco (só em dinheiro) e não é registrado como receita.
- **C** — excedente vira crédito do cliente.

Recomendação: A. B e C criam saldo fora do recebível.

Para contas a pagar, o cartão diz "baixa total/parcial **quando aplicável**": parcial é sempre
permitido, ou só em algumas categorias?

### F-06 — Desconto financeiro, juros e multa

O cartão exige preservar "descontos/acréscimos". Não diz quem aplica nem quando.

- Desconto financeiro no recebível (diferente do desconto comercial do orçamento): permitido? Exige
  motivo? Exige permissão própria (como `QUOTE_DISCOUNT`)? Tem limite?
- Acréscimo manual: permitido? Com motivo?
- Juros e multa por atraso: **calculados automaticamente** (com taxa configurável) ou **não existem no
  MVP**?

Recomendação: desconto e acréscimo manuais, com motivo e permissão; **sem** juros e multa automáticos
no MVP.

### F-07 — Estorno

- Estorno de recebimento é sempre **total** do recebimento, ou pode ser parcial?
- Motivo obrigatório?
- Quem pode estornar (permissão própria)?
- Estorno reabre o saldo do recebível automaticamente?
- Recebimento registrado com data errada: estorno + novo registro (sem edição), confirma?

Recomendação: estorno sempre total do recebimento, motivo obrigatório, permissão própria, saldo
reaberto; correção sempre por estorno + novo registro.

### F-08 — Cancelamento da OS com dinheiro já recebido

- **A** — cancelar a OS é recusado enquanto houver recebimento não estornado; o usuário estorna antes.
- **B** — cancelar a OS estorna automaticamente todos os recebimentos.
- **C** — cancelar a OS mantém o recebível e o dinheiro recebido vira crédito/reembolso a tratar.

E sem recebimento: cancelar a OS **cancela** o recebível em aberto (histórico preservado)?

Recomendação: A, e cancelamento automático do recebível sem recebimentos. Dinheiro recebido nunca
some por mudança de status.

### F-09 — Formas de pagamento

O cartão diz "configuráveis".

- Lista inicial: `DINHEIRO`, `PIX`, `CARTAO_DEBITO`, `CARTAO_CREDITO`, `BOLETO`, `TRANSFERENCIA`,
  `OUTRO` — confirma, remove ou acrescenta?
- "Configurável" significa cadastrar/inativar formas pela tela, ou só ativar/desativar as da lista?
- Crédito parcelado na maquininha: registrar o número de parcelas agora (sem efeito de caixa, que é
  da conciliação), ou fica para a conciliação?

### F-10 — Dinheiro e caixa físico

O `AG-06` §14 diz: "É proibida movimentação em dinheiro sem sessão aberta". Sessão de caixa
(abertura, conferência, fechamento automático às 23:59) **não** está nos cartões da TASK-0015.

- **A** — implementar sessão de caixa dentro da TASK-0015.
- **B** — TASK-0015 não aceita recebimento em `DINHEIRO`; sessão de caixa vira Task própria.
- **C** — aceitar `DINHEIRO` sem sessão até existir o caixa (contraria o `AG-06` §14; só com decisão
  explícita).

Recomendação: B, ou A se o caixa for prioridade para a operação.

### F-11 — Regime do fluxo de caixa e inadimplência

- Fluxo **realizado** = soma de recebimentos e pagamentos pela **data efetiva**; fluxo **previsto** =
  saldo em aberto pela **data de vencimento**. Confirma?
- Visão por **competência** (receita no período em que a OS foi concluída, `AG-06` §10): entra na
  TASK-0015, ou fica para Rentabilidade?
- Saldo inicial de caixa: existe? Sem ele, o fluxo mostra variação do período, não saldo.
- Inadimplência: situação `VENCIDO` derivada (vencimento passado e saldo > 0), **sem** bloqueio
  automático do cliente — confirma? Ou vencido bloqueia nova OS?

Recomendação: realizado/previsto como descrito; competência fora; sem saldo inicial; `VENCIDO`
derivado e sem bloqueio.

### F-12 — Contas a pagar: fornecedor, categoria e corte de escopo

- Fornecedor: **texto livre** no MVP, ou cadastro de fornecedores (que ainda não existe)?
- Categoria: lista plana configurável, ou categoria + subcategoria (`AG-06` §26)?
- Confirma **fora** da TASK-0015: centro de custo, rateio, recorrência, parcelamento de conta a
  pagar, contas bancárias, conciliação, taxas, vínculo contábil (seção 2)?

### F-13 — Permissões

O cartão "Perfis e permissões de acesso" ainda não foi implementado. Existem os perfis fixos `DONO`,
`GERENTE_ADMINISTRATIVO` e `GERENTE_FINANCEIRO`.

- Financeiro exige permissões próprias já nesta Task (por exemplo `FINANCE_VIEW`, `FINANCE_RECEIVE`,
  `FINANCE_REVERSE`, `FINANCE_DISCOUNT`, `FINANCE_PAYABLE`), ou segue como o resto do sistema
  (sessão + CSRF) até o cartão de perfis?
- Se próprias: quais perfis recebem cada uma?

Recomendação: permissões próprias já nesta Task. Dinheiro é a área em que "qualquer usuário
autenticado" é o risco maior.

---

## 4. Decisão final do Owner

- Data: `2026-09-22`
- Decisão: `DECIDED`

| Pergunta | Escolha | Regra decidida |
| --- | --- | --- |
| F-01 | **A** | O recebível nasce automaticamente ao **finalizar** a OS, em aberto. Finalizar não é pagar. Entregar não cria outro recebível. |
| F-02 | **B** | Valor = decisões comerciais **efetivas** do orçamento de faturamento (`billing_quote_id`), congeladas no instante da geração. Nunca o total bruto da OS. |
| F-03 | **A** | Exatamente um recebível principal por OS, sem parcelas. Recebimento parcial contra o mesmo recebível. Parcelamento de cartão não é parcelamento do recebível. |
| F-04 | **C** | Configuração `default_receivable_due_days`, inicial `0` (vencimento = data da finalização). Todo recebível tem vencimento. Ajuste individual auditável enquanto aberto. Conta a pagar: `due_date` obrigatório. |
| F-05 | **A** | Recebimento ou pagamento maior que o saldo é recusado. Sem troco, crédito ou saldo avulso. Parcial permitido em receber e em pagar. |
| F-06 | — | Desconto e acréscimo financeiros **manuais**, com valor, motivo, usuário, instante e permissão própria. Sem juros, multa ou correção automáticos. Valor ajustado nunca negativo. |
| F-07 | — | Estorno sempre **total** do lançamento; motivo, usuário e instante do servidor obrigatórios; permissão própria; registro novo; um estorno por lançamento; reabre o saldo. Correção de valor, data ou forma = estorno + novo lançamento. Vale igual para pagamentos. |
| F-08 | **A** | Com recebimento não estornado, a OS **não** é cancelada (conflito pedindo estorno prévio). Sem recebimentos, cancelar a OS cancela o recebível, preservando o histórico. |
| F-09 | — | Catálogo configurável de formas (cadastrar, renomear, inativar; nunca excluir a usada; lançamento guarda snapshot do nome). Seed: `DINHEIRO`, `PIX`, `CARTAO_DEBITO`, `CARTAO_CREDITO`, `BOLETO`, `TRANSFERENCIA`, `OUTRO`. Sem parcelas de cartão, NSU, adquirente, taxa, antecipação ou conciliação. |
| F-10 | **B** | `DINHEIRO` existe no catálogo, mas novo recebimento ou pagamento em dinheiro é recusado com conflito claro até existir sessão de caixa (Task própria). Sem exceção temporária. |
| F-11 | — | Realizado = lançamentos não estornados pela data efetiva; previsto = saldo aberto pelo vencimento; nunca misturados. Sem competência e sem saldo inicial: a tela mostra **entradas, saídas e variação líquida**. `VENCIDO` derivado (`due_date < hoje` e saldo > 0), sem bloquear o cliente. |
| F-12 | — | Fornecedor em texto livre. Categoria em lista plana configurável (cadastrar, inativar; nunca excluir com histórico). Fora: subcategoria, centro de custo, rateio, recorrência, parcelas de conta a pagar, contas bancárias, transferências, conciliação, adquirentes, taxas, vínculo contábil, plano de contas, fiscal, comissão, rentabilidade. |
| F-13 | — | Permissões `FINANCE_VIEW`, `FINANCE_RECEIVE`, `FINANCE_REVERSE`, `FINANCE_ADJUST`, `FINANCE_PAYABLE`, `FINANCE_CONFIG`. `DONO` e `GERENTE_FINANCEIRO`: todas. `GERENTE_ADMINISTRATIVO`: `FINANCE_VIEW` e `FINANCE_RECEIVE`. Exceções individuais do IAM continuam. Toda mutação protegida no backend. |

### Seleção do orçamento de faturamento (F-02)

- Não vale: último orçamento criado, último apresentado, soma de orçamentos, total bruto como fallback.
- Um candidato elegível → selecionado automaticamente. Dois ou mais → a finalização exige
  `billingQuoteId` explícito. Nenhum → conflito de domínio; nada é inventado.
- O orçamento escolhido tem de pertencer à OS finalizada.
- Decisão pública e decisão interna valem como evidência comercial, cada uma pelas suas regras.
- Alteração comercial posterior não reescreve o recebível.

### Invariantes e idempotência exigidas

Nunca: saldo negativo; pagamento maior que o saldo; apagar ou editar recebimento; apagar conta a
pagar com histórico; receber duas vezes a mesma requisição por retry; dois recebíveis principais para
a mesma OS; recebível com item rejeitado; recebível com orçamento de outra OS. Proteção por
constraint/índice único onde o PostgreSQL puder, e por bloqueio de linha onde a invariante atravessa
tabelas. Duplo clique, retry HTTP, timeout seguido de retry e requisições concorrentes são cobertos em
geração do recebível, recebimento, estorno e pagamento.

## 5. Interpretações técnicas registradas na formalização

Pontos em que a decisão exigiu leitura do domínio já aprovado. Nenhum cria regra financeira nova;
cada um é a leitura mais restritiva do que já está decidido.

1. **"Decisão comercial efetiva" de um item** (F-02). Pelo domínio aprovado de aprovação parcial
   (§§56–58), a condição comercial corrente de um item é a sua versão **apresentada e não substituída**;
   aprovação de versão anterior "continua como fato histórico" e "não é transferida". Logo, entra no
   valor somente o item cuja versão corrente tem decisão `APPROVE`. Versão corrente rejeitada ou ainda
   sem decisão não entra; versão substituída não entra, mesmo que tenha sido aprovada.
2. **Orçamento elegível** = orçamento da OS com ao menos um item nessas condições. Valor original =
   soma dos totais já arredondados desses itens (`DR-0007`, soma dos arredondados). Aprovação com total
   zero (desconto integral) gera recebível de valor zero, que nasce quitado — é o valor aprovado.
3. **F-08 no fluxo atual.** Pela `DR-0012`, OS finalizada não pode ser cancelada, e o recebível só nasce
   na finalização. Hoje, portanto, cancelar uma OS nunca encontra recebível. A regra F-08 é implementada
   e testada no Financeiro mesmo assim, para valer no dia em que o fluxo da OS mudar.
4. **Conta a pagar com pagamento não estornado** não pode ser cancelada — equivalência direta de F-08,
   que a instrução de F-07 estende aos pagamentos.
5. **Data efetiva** de recebimento e pagamento é informada pelo usuário, com hoje como padrão, e não pode
   ser futura. "Hoje" é a data no fuso `America/Sao_Paulo`, o da oficina (`DR-0009`), também usado no
   vencimento derivado de `default_receivable_due_days`.
6. **Ajustes financeiros** só em recebível não cancelado; desconto limitado ao saldo em aberto, para que
   o saldo nunca fique negativo; mudança de vencimento só com saldo em aberto e motivo obrigatório.
7. **Correção de ajuste lançado por engano** não está coberta por F-06/F-07 e virou a `DR-0017`
   (`OPEN`). Até lá, ajuste é imutável e não tem estorno; nada foi inventado.

## 6. O que continua fora

Tudo da seção 2, além de competência, saldo inicial, sessão de caixa (Task própria), parcelas de cartão
e bloqueio de inadimplente.
