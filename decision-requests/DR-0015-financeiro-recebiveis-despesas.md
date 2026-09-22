# DR-0015 — Financeiro: recebíveis da OS, recebimentos, contas a pagar e fluxo de caixa

- Tipo: `FINANCIAL`
- Status: `OPEN` — aguardando decisão do Owner; nenhuma linha de código do Financeiro existe
- Task: `TASK-0015`
- Origem: `AG-06 — Financeiro`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Reescrita em: `2026-09-22`

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

## 4. O que pode avançar sem esta DR

Somente especificação e o que não fixa regra financeira: estrutura do módulo `finance`, fronteiras
Modulith, contrato de evento da OS (`WorkOrderEvents.Finished` / `Cancelled`, que já existem), tipos
monetários conforme a `DR-0007`. **Nenhuma migration, entidade ou endpoint financeiro será criado
antes da decisão**, porque o esquema depende de F-02, F-03, F-04, F-06 e F-09.

## 5. Decisão final do Owner

- Data: `-`
- Decisão: `PENDENTE`

| Pergunta | Escolha |
| --- | --- |
| F-01 | - |
| F-02 | - |
| F-03 | - |
| F-04 | - |
| F-05 | - |
| F-06 | - |
| F-07 | - |
| F-08 | - |
| F-09 | - |
| F-10 | - |
| F-11 | - |
| F-12 | - |
| F-13 | - |
