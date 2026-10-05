# DR-0019 — Item físico rejeitado pelo cliente: destino do estoque e do custo de peças

## 1. Identificação

```text
ID: DR-0019
Título: Item físico lançado na OS e rejeitado pelo cliente — estoque, custo e remoção
Tipo: BUSINESS / FINANCIAL
Status: OPEN
Criado em: 2026-10-05
Criado por: AG-00 — Orquestrador (auditoria de fechamento do MVP, TASK-0019)
Última atualização: 2026-10-05
```

## 2. Task relacionada

```text
Task principal: TASK-0019 — Fechamento do MVP/piloto para homologação
Outras tasks impactadas: TASK-0006, TASK-0007, TASK-0008, TASK-0013, TASK-0014, TASK-0015, TASK-0018
Bloqueia a Task: NÃO (o MVP segue para homologação com a limitação abaixo documentada)
```

## 3. Origem

```text
Agente que identificou: AG-13 (QA) / AG-04 (Catálogo & Estoque) durante o E2E de fechamento
Módulos: Oficina (OS), Orçamento, Estoque, Financeiro (painel)
```

## 4. Problema

A baixa de estoque de uma peça acontece **no lançamento do item na OS** (`ITEM_LAUNCH`, padrão decidido na
`DR-0014`), antes de o cliente decidir o orçamento. Quando o cliente aprova só parte do orçamento (a
aprovação parcial é o coração da `TASK-0001`), o item físico **rejeitado** continua:

1. baixado do estoque (o saldo do sistema fica menor do que a prateleira real);
2. contado no **custo de peças** do painel financeiro (`partsCost`), embora não tenha sido usado nem
   faturado — o lucro bruto do painel sai subestimado;
3. impossível de remover: não existe operação para retirar um item da OS. O estorno manual da baixa
   (`POST /api/inventory/movements/{id}/reverse`, `INVENTORY_ADJUST`) devolve o saldo; até a `TASK-0019` ele
   não saía do custo de peças (o cálculo só descontava `WORK_ORDER_RETURN`), o que foi **corrigido como defeito
   técnico** (teste de integração no PostgreSQL). Continua sendo uma ação manual, sem vínculo com a decisão do
   cliente nem com o item da OS, que segue listado na OS e no recibo interno.

Só o **cancelamento da OS inteira** devolve estoque (`WORK_ORDER_RETURN`). Uma OS parcialmente aprovada e
executada não tem como devolver o item recusado.

Evidência (E2E `scripts/e2e/workshop_flow.py`, PostgreSQL real): OS com serviço R$ 350 + 2 bombas
(R$ 150 de custo cada) + 1 kit de reparo (R$ 40 de custo). Cliente aprova serviço e bombas e rejeita o kit.
Recebível = R$ 950,00 (correto, o kit não é faturado). Custo de peças no painel = R$ 340,00 (inclui os
R$ 40,00 do kit rejeitado). Saldo do kit no estoque = 9 (o item rejeitado não voltou).

## 5. Contexto

- `DR-0014`: baixa pela OS configurável (`ITEM_LAUNCH` padrão, `WORK_ORDER_FINISH`, `DISABLED`);
  cancelamento devolve estoque; "reserva formal de estoque" ficou fora e "a política poderá ser
  reavaliada" quando existir.
- `DR-0015` (F-02): o recebível considera somente item aprovado; item rejeitado/pendente não entra.
- `DR-0012`: a execução pode iniciar a partir de qualquer etapa operacional exceto `REPROVADA`; a OS não
  rastreia execução por item.
- Fora desta DR: o modo `WORK_ORDER_FINISH` baixa **todos** os itens físicos da OS na finalização, aprovados
  ou não (o evento `Finished` carrega todas as linhas), então tem o mesmo problema.

## 6. Motivo

- [x] regra de negócio ausente;
- [x] risco financeiro (custo e lucro do painel);
- [ ] regra contraditória.

## 7. Opções

### Opção A — Devolver o estoque automaticamente quando o item é rejeitado

Ao registrar a decisão `REJECT` de um item de orçamento vinculado a um item físico da OS
(`workOrderProductId`), o Estoque devolve a baixa daquele item (`WORK_ORDER_RETURN`, ligado ao movimento
original). Se uma nova revisão aprovar o item depois, ele precisa ser baixado de novo (a baixa passa a ser
idempotente por item e por "ciclo").

- Vantagem: saldo e custo corretos sem ação manual; segue a regra "aprovação pertence à versão apresentada".
- Risco: item rejeitado e depois reapresentado exige nova baixa e pode falhar por saldo; dois itens físicos da
  mesma OS sem vínculo com o orçamento (lançados e nunca orçados) continuam baixados.

### Opção B — Permitir remover da OS um item físico não aprovado (ação manual com motivo)

Nova operação `DELETE /api/work-orders/{id}/products/{itemId}` (permissão `WORKORDER_MANAGE`), só enquanto o
item não estiver aprovado nem a OS encerrada, devolvendo o estoque (`WORK_ORDER_RETURN`) e registrando motivo e
usuário. A rejeição do cliente não remove nada sozinha.

- Vantagem: ação explícita e auditável, sem automatismo; resolve também o lançamento por engano.
- Risco: depende de disciplina operacional; o painel fica incorreto até alguém remover.

### Opção C — Baixar somente o que foi aprovado (mudar a política de baixa)

Substituir `ITEM_LAUNCH` por baixa na aprovação (ou na execução) apenas dos itens aprovados, com reserva
formal no lançamento.

- Vantagem: modelo mais fiel ao fluxo real; elimina o problema na origem.
- Risco: exige o conceito de **reserva** (físico / reservado / disponível, README) que a `DR-0014` deixou fora;
  é a maior mudança, e reabre decisão já ratificada pelo proprietário.

### Opção D — Manter como está e documentar

Aceitar a limitação no piloto; o operador corrige por ajuste manual de estoque.

- Risco: o custo do painel continua incorreto (o ajuste manual não é descontado); não recomendado.

## 8. Recomendação

**Opções A + B juntas**, nessa ordem de implementação: B primeiro (menor, destrava a operação e o lançamento
por engano), A em seguida (automatiza o caso mais comum). C só quando a reserva formal for priorizada.
O cálculo de custo de peças já desconta o estorno manual de baixa (correção técnica da `TASK-0019`), então o
painel não diverge do estoque quando o operador estorna.

## 9. Impactos

```text
Funcional: OS, orçamento e estoque (devolução de item rejeitado, remoção de item).
Dados: nova migration aditiva (tipo de movimento/vínculo de devolução por item); nenhuma migration antiga muda.
Segurança/Auditoria: remoção e devolução com usuário, motivo e data.
Financeiro: custo de peças e lucro bruto do painel passam a refletir só o que foi usado/faturado.
Frontend: ação "remover item" na OS e saldo atualizado após rejeição.
Testes: E2E parcial (aprova 2, rejeita 1) com devolução; concorrência entre decisão e finalização.
```

## 10. Responsável pela decisão

```text
Responsável pela decisão final: Proprietário do produto
Decisão final: PENDENTE
Data: -
```

## 11. Limitação vigente até a decisão (para a homologação)

Em OS com aprovação parcial, o operador deve **estornar a baixa do item rejeitado**
(`POST /api/inventory/movements/{id}/reverse`, permissão `INVENTORY_ADJUST`, motivo obrigatório): o saldo
volta e o item sai do custo de peças do painel. O item continua listado na OS (não existe remoção) mas não é
faturado (o recebível só considera item aprovado). Sem o estorno, o painel conta o custo do item rejeitado.
