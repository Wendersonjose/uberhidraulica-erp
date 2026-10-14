# DR-0023 — Conciliação entre pedido de compra e NF-e

## Status

`OPEN` — **proposta, sem decisão do proprietário.** As opções C1–C4 abaixo não estão implementadas. O que existe hoje é um
**bloqueio de segurança** (fail closed), descrito em "Estado atual": só a compra nascida de NF-e recebe mercadoria.

- Criada em: 2026-10-14
- Task: `TASK-0021` (I5 entregue; esta decisão bloqueia o I5b — ligação pedido × nota — e o I6)
- Origem: `AG-05` §163 (divergência, consolidação de documentos, substituição de item), levantada pelo `AG-11` ao fechar o I5
- Responsável pela decisão: proprietário do produto

## Contexto

O I5 entregou **pedido de compra** e **compra direta**: rascunho, aprovação segregada (DR-0022 D8) e, depois de aprovado,
recebimento físico (I4). O I3 já entregava a **NF-e importada**, que cria compra própria, já aprovada, com contas a pagar.

Pedido e nota são **duas compras independentes**, sem ligação. Se os dois pudessem receber:

- o pedido aprovado daria entrada em estoque ao **custo estimado do pedido** (preço combinado + rateio das despesas
  informadas; sem IPI nem ICMS-ST, que só existem na nota);
- a nota importada criaria **outra** compra, com contas a pagar, que também daria entrada ao custo real da nota.

Dois riscos concretos (**ambos reais, e por isso o caminho do pedido está fechado — ver "Estado atual"**):

1. **Estoque em dobro.** Se o operador receber a mercadoria no pedido e de novo na compra da nota, o saldo entra duas vezes.
2. **Custo divergente.** Receber no pedido entra com custo sem tributos; receber na nota entra com o custo de aquisição da D1.
   O mesmo produto teria custos diferentes conforme o caminho.

Pedido **não** gera conta a pagar (decisão de desenho do I5): gerar conta no pedido e importar a nota depois contaria a
obrigação duas vezes. Isso fica como está até esta decisão.

## Estado atual (bloqueio, não aviso)

O proprietário determinou em 2026-10-14: **não habilitar dois caminhos independentes de recebimento para a mesma aquisição sem
controle de identidade e idempotência.** Verificação do estado do código naquela data: o risco de duplicar estoque era
**real** — pedido aprovado e compra de NF-e eram ambos recebíveis, sem nada que ligasse os dois.

Cada caminho tem hoje:

| Caminho | Identidade da aquisição | Idempotência | Estado |
| --- | --- | --- | --- |
| Compra de NF-e importada | chave de acesso de 44 posições, **única entre documentos não anulados** (índice parcial); XML alterado com a mesma chave é bloqueado | `Idempotency-Key` na importação e no recebimento; `uq_stock_movement_purchase_entry` impede duas entradas para o mesmo item recebido; compra travada (`FOR UPDATE`) serializa recebimentos | **recebe** |
| Pedido de compra / compra direta | **nenhuma** (não há chave, nota ou vínculo) | `Idempotency-Key` protege só o retry do mesmo recebimento, **não** a mesma mercadoria chegando por outra compra | **não recebe** |

Implementação: `Purchase.requireReceivable()` recusa o recebimento de `ORDER` e `DIRECT` com `409 PURCHASE_RECEIPT_REQUIRES_INVOICE`,
depois de checar a situação (`PURCHASE_NOT_RECEIVABLE`). O pedido continua existindo para **registrar e aprovar a intenção e o valor
da compra**; estorno de recebimento já existente segue permitido (não prende estoque). A tela mostra a recusa e manda receber na
compra da nota.

O que **não** muda: a entrada manual do Estoque (`INVENTORY_MOVE`) é um caminho à parte, sem relação com Compras; usá-la para um
material que também será recebido pela nota duplicaria o saldo, e só a disciplina do operador evita isso.

**Consequência operacional (a decidir):** compra sem NF-e (balcão, fornecedor informal) não dá entrada pelo módulo Compras até a
decisão desta DR; use a entrada manual do Estoque ou importe a nota quando existir. Reabrir o recebimento de pedido exige a C1.

## Decisões pendentes

### C1 — Como pedido e nota se ligam

- **A (recomendada):** vínculo **explícito e confirmado por humano**. Na importação, o sistema lista os pedidos
  **aprovados** do mesmo fornecedor com saldo pendente; o operador escolhe um (ou nenhum). Vinculado, o pedido fica
  `ENCERRADO_POR_NOTA` (não recebe mais) e o recebimento acontece **sempre na compra da nota**, ao custo real da D1.
  Só se vincula pedido **sem recebimento ativo** (com recebimento, o operador estorna antes ou escolhe a alternativa C).
  Motivo: no fluxo comum o XML chega antes da mercadoria; receber na nota dá ao estoque o custo com tributos.
- B: a nota **anexa-se ao pedido** (um registro só): o documento e as contas a pagar passam a pertencer ao pedido, que
  vira a compra. Evita duas compras, mas exige recalcular custo de itens já recebidos quando os tributos chegam, o que a
  D1 proíbe fazer em silêncio.
- C: **sem ligação** (estado atual), só com o aviso. Não resolve os dois riscos; depende de disciplina.

### C2 — Divergência entre o pedido e a nota (preço, quantidade, itens)

- **A (recomendada):** na vinculação, a tela **mostra a diferença** item a item; qualquer diferença exige justificativa e
  `PURCHASE_DIVERGENCE_ACCEPT` (mesmo critério da D3), sem tolerância automática.
- B: tolerância percentual configurável abaixo da qual a diferença passa sem justificativa.

### C3 — Item da nota diferente do pedido (substituição)

- **A (recomendada):** tratado como divergência da C2, com justificativa; o pedido é encerrado e o histórico guarda o item
  original e o substituto.
- B: bloquear a vinculação e exigir cancelar o pedido e importar a nota sozinha.

### C4 — Solicitação, cotação multi-fornecedor e comparação entram no MVP?

O `AG-05` prevê solicitação → cotação → pedido. O I5 entregou o pedido e a compra direta, sem as duas etapas anteriores.

- **A (recomendada):** adiar para depois do piloto. A oficina compra, na prática, por pedido direto ao fornecedor de
  sempre; o histórico de preço por fornecedor (já gravado a cada nota) cobre a comparação inicial.
- B: entregar solicitação e cotação antes do piloto (nova migration e telas; estimativa de um incremento inteiro).

## Impactos

| Decisão | Banco | Estoque / custo | Financeiro | Reversível |
| --- | --- | --- | --- | --- |
| C1-A | coluna `invoice_purchase_id` e novo estado do pedido | sempre custo da nota | sem mudança | sim, é aditivo |
| C1-B | mover documento/parcelas entre compras | recalcular custo | contas passam ao pedido | difícil |
| C1-C | nenhum | risco de dobro e de custos diferentes | sem mudança | — |
| C2/C3 | eventos e justificativa | nenhum | nenhum | sim |
| C4 | tabelas novas | nenhum | nenhum | sim |

## Recomendação técnica

C1-A, C2-A, C3-A e C4-A. É a combinação que elimina o risco de entrada em dobro sem reescrever custo já gravado, mantém um
único lugar onde o estoque entra no fluxo comum (a compra da nota) e deixa a cotação para quando houver necessidade real.

## Riscos de não decidir

Com o bloqueio em vigor não há risco de estoque em dobro **dentro de Compras**. O custo de não decidir é de capacidade:
pedido aprovado não vira entrada em estoque, e compra sem nota fiscal precisa da entrada manual do Estoque. Para reabrir o
recebimento de pedido/compra direta é necessária a C1 (vínculo de identidade com a nota), com idempotência provada em teste.

## Decisão final

Pendente. Registrar aqui a decisão do proprietário, a data e eventuais condições.
