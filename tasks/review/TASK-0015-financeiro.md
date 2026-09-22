# TASK-0015 — Financeiro: contas a receber da OS, recebimentos, contas a pagar e fluxo de caixa

## Identificação

- Status: `REVIEW` — F1–F4 corrigidos; checkpoint PostgreSQL pendente
- Prioridade: `HIGH`
- Criada em: `2026-09-22`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer", cartões 31 a 34
- Proprietário principal: Financeiro — `AG-06`
- Revisão da especificação: `AG-02`, `AG-06`, `AG-08` — `docs/review/TASK-0015-revisao-especificacao.md`

## Cartões Trello cobertos

| Cartão | Escopo |
| --- | --- |
| Contas a receber da ordem de serviço | recebível ligado à OS e ao orçamento de faturamento; valor original congelado, descontos/acréscimos e saldo; finalizar ≠ receber; cancelamento com histórico |
| Registro de recebimentos e formas de pagamento | recebimento total/parcial com data efetiva, forma e usuário; estorno total, próprio e auditável; formas configuráveis |
| Contas a pagar e despesas | descrição, fornecedor em texto livre, categoria configurável, valor, vencimento obrigatório, situação; pagamento total/parcial; cancelamento preserva histórico |
| Fluxo de caixa e visão financeira | realizado × previsto por período e categoria; entradas, saídas e variação líquida; tudo derivado |

## Decisões

- `DR-0015` — `DECIDED` (F-01 a F-13), com as interpretações técnicas da seção 5.
- `DR-0008` — `DECIDED`, opção A: vínculo opcional `quote_item.work_order_product_id`.
- `DR-0017` — `DECIDED`, opção A: ajuste lançado por engano é corrigido por estorno próprio, total e único,
  com motivo, `Idempotency-Key` e `FINANCE_REVERSE`; ajuste estornado deixa de compor os derivados.
- `DR-0007` — política monetária. `DR-0009` — oficina única. `DR-0012` — OS finalizada não é cancelada.

## Especificação

Modelo de domínio, banco, idempotência, fluxo de caixa e contrato REST:
`docs/architecture/financeiro/TASK-0015-modelo-financeiro.md`.

## Entregas, em commits separados

1. `V17` + Orçamento: vínculo do item comercial com o item físico da OS (`DR-0008`).
2. Orçamento: contrato público `QuoteBillingQuery` (base comercial efetiva).
3. `V18` + módulo `finance`: recebível, recebimentos, ajustes, vencimento, contas a pagar, formas,
   categorias, configuração, permissões, fluxo de caixa; ouvintes da finalização e do cancelamento.
4. OS: finalização aceita `billingQuoteId`; evento `Finished` leva o orçamento escolhido.
5. Frontend: telas do Financeiro, seleção do orçamento na finalização, autorização refletida.
6. Testes e checkpoint PostgreSQL.

## Critérios de aceite

### Recebível da OS

1. Finalizar a OS gera exatamente um recebível em aberto; nenhum recebimento é registrado.
2. O valor original é a soma dos itens com decisão `APPROVE` na versão corrente do orçamento de
   faturamento; item rejeitado, pendente ou substituído não entra. Nunca o total bruto da OS.
3. Um candidato → seleção automática; dois ou mais sem escolha → `409 BILLING_QUOTE_SELECTION_REQUIRED`;
   nenhum → `409 WORK_ORDER_WITHOUT_BILLING_BASIS`; orçamento de outra OS ou sem aprovação →
   `409 BILLING_QUOTE_NOT_ELIGIBLE`. Em todos os casos a OS continua em execução.
4. O recebível guarda o orçamento de faturamento e o snapshot das linhas; decisão comercial posterior
   não o altera.
5. Vencimento = data da finalização + `default_receivable_due_days` (inicial 0).
6. Entregar a OS não cria outro recebível; repetir a finalização não cria outro recebível.
7. Recusa do Estoque na finalização desfaz também o recebível, e vice-versa.

### Recebimento e estorno

8. Recebimento parcial permitido; valor acima do saldo → `409 AMOUNT_EXCEEDS_BALANCE`.
9. `DINHEIRO` → `409 CASH_SESSION_REQUIRED`; forma inativa → `409 PAYMENT_METHOD_INACTIVE`.
10. Data efetiva não futura; padrão hoje (`America/Sao_Paulo`).
11. `Idempotency-Key` obrigatório; retry com a mesma chave não duplica; chave reutilizada com outro
    conteúdo → `409 IDEMPOTENCY_KEY_REUSED`.
12. Estorno total, com motivo; reabre o saldo; segundo estorno → `409 RECEIPT_ALREADY_REVERSED`.
13. Nenhum recebimento é editado ou apagado.
14. Dois recebimentos simultâneos cuja soma excede o saldo: só um passa (PostgreSQL real).

### Ajustes e vencimento

15. Desconto e acréscimo manuais com motivo e `FINANCE_ADJUST`; valor original preservado; desconto
    maior que o saldo recusado; valor ajustado nunca negativo.
16. Mudança de vencimento com motivo, registrada com valor anterior, só com saldo em aberto.

### Cancelamento

17. Cancelar OS cujo recebível tem recebimento não estornado → `409 RECEIVABLE_HAS_RECEIPTS`; sem
    recebimentos, o recebível é cancelado e preservado. (No fluxo atual a OS finalizada não é
    cancelada — `DR-0012`; a regra é testada no Financeiro diretamente.)

### Contas a pagar

18. Criação com descrição, categoria ativa, valor > 0 e vencimento obrigatórios; fornecedor opcional.
19. Pagamento parcial; acima do saldo recusado; `DINHEIRO` recusado; estorno total; idempotência.
20. Cancelamento com motivo; com pagamento não estornado → `409 PAYABLE_HAS_PAYMENTS`.

### Configuração, fluxo e permissões

21. Formas e categorias: cadastrar, renomear, inativar; nunca excluir; lançamento guarda o nome da forma.
22. Fluxo de caixa: realizado e previsto separados, entradas, saídas e variação líquida, série diária,
    filtro de categoria para saídas; sem "saldo".
23. `VENCIDO` derivado, sem bloquear abertura de OS do cliente.
24. Toda rota respeita a permissão da tabela do modelo; `GERENTE_ADMINISTRATIVO` recebe mas não estorna,
    não ajusta, não lança conta a pagar e não configura.
25. Dinheiro em `BigDecimal`/`NUMERIC`; ArchUnit proíbe `float`/`double` no módulo.

### DR-0008

26. Item comercial pode apontar um item físico da mesma OS; outra OS → `404 WORK_ORDER_PRODUCT_NOT_FOUND`;
    duas vezes no mesmo orçamento → `409 QUOTE_ITEM_PRODUCT_ALREADY_LINKED`; orçamentos diferentes podem
    repetir; o PostgreSQL recusa a associação cross-OS mesmo por SQL direto.

## Implementação

| Entrega | Commit |
| --- | --- |
| `DR-0008`: `V17`, vínculo item comercial × item físico, editor de revisão | `78256b5` |
| `QuoteBillingQuery` (base comercial efetiva) + testes unitários | `eb9144f` |
| `V18` + módulo `finance` + finalização com `billingQuoteId` | `3494724` |
| Telas do Financeiro, finalização com escolha, recebível na OS | `5841e4b` |
| Testes de integração e ajustes em testes existentes | `f3f48f6` |

Ajuste de modelo feito durante a implementação: desconto e acréscimo também exigem
`Idempotency-Key` (coluna `idempotency_key UNIQUE` em `receivable_adjustment`), porque são mutações
financeiras sujeitas a duplo clique. Retries concorrentes com a mesma chave são serializados por
`pg_advisory_xact_lock` sobre a chave, antes do bloqueio da linha do agregado.

## Efeito colateral conhecido

Finalizar uma OS passa a exigir base comercial aprovada. Testes existentes que finalizavam OS sem
orçamento (Estoque) recebem um orçamento aprovado como fixture — consequência direta da F-02, não
afrouxamento de teste.

## Checkpoint PostgreSQL (2026-09-22)

Desenvolvimento com Docker fechado. Docker aberto só para integração e suíte completa, e fechado em
seguida.

| Gate | Resultado |
| --- | --- |
| Sem Docker: `mvn compile`, `FinanceDomainTest` (12), `QuoteBillingServiceTest` (5), `FinanceMonetaryArchitectureTest`, `ModularityTest` | verdes |
| `Task0015FinanceIntegrationTest` (PostgreSQL) | 18 testes, 0 falhas — inclui recebimentos concorrentes, retries concorrentes com a mesma chave, estornos concorrentes e as FKs de cross-OS e de decisão aprovada |
| `Task0014InventoryIntegrationTest` | 15 testes, 0 falhas; prova que a recusa do Estoque desfaz o recebível |
| `mvn test` — suíte backend completa | **204 testes, 0 falhas, 0 erros, 0 ignorados, `BUILD SUCCESS`** |
| `npm test -- --run` | 12 arquivos, 106 testes |
| `npx tsc --noEmit`, `npm run build`, `npm run lint` | limpos (aviso de chunk > 500 kB preexistente) |
| `git diff --check` | limpo |

Primeira execução da suíte completa: 1 falha em `Task0012WorkOrderWorkflowIntegrationTest`, que
finalizava OS sem orçamento. Não é regressão: é a F-02. O teste passou a verificar a recusa
`WORK_ORDER_WITHOUT_BILLING_BASIS` e depois finaliza com orçamento aprovado.

## Revisão externa (2026-09-22) — `CHANGES_REQUIRED`

Três findings (F1 BLOCKER, F2 HIGH, F3 HIGH), `DR-0017` decidida e hardening do vencimento. Todos
corrigidos em `93a6043`, `d0894d5`, `fbe5556` e `fdb6e33`, com a migration nova `V19`. Detalhes, testes e
varredura AG-15: `docs/review/TASK-0015-revisao-tecnica.md`.

**Checkpoint PostgreSQL da revisão: não executado** — o Docker Desktop não iniciou o engine nesta sessão
(`monitor exited: exit status 150`). Sem Docker ficaram verdes: compilação, 22 testes unitários de
domínio e base comercial, modularidade, ArchUnit monetário e o frontend inteiro (110 testes, build, lint).
Os testes de integração novos e a suíte completa precisam rodar antes de `DONE`.

## Nova revisão externa (2026-09-22) — F4

F1, F2 e F3 confirmados. Novo finding **F4 (MEDIUM, bloqueante para `DONE`)**: a identidade do pedido
idempotente estava incompleta — mesma chave com outro conteúdo podia ser tratada como replay. Corrigido
comparando o pedido normalizado com o que está persistido, sem migration nova:

| Operação | Identidade comparada |
| --- | --- |
| Ajuste | recebível, tipo, valor, motivo normalizado |
| Alteração de vencimento | recebível, novo vencimento, motivo normalizado |
| Estorno de recebimento, pagamento e ajuste | lançamento alvo, motivo normalizado |
| Recebimento e pagamento | dono, valor, forma, data efetiva, observação normalizada |
| Conta a pagar | descrição, fornecedor, categoria, valor, vencimento, observação (normalizados) |

Data omitida em recebimento/pagamento significa "hoje" no instante do pedido original: no replay ela é
reconstruída de `recorded_at` no fuso da oficina, então o retry da mesma requisição depois da meia-noite
devolve o lançamento original em vez de `IDEMPOTENCY_KEY_REUSED`.

Testes: `FinanceDomainTest` (identidade da liquidação com a virada da meia-noite e identidade da conta a
pagar) e seis testes novos em `Task0015FinanceIntegrationTest` cobrindo os oito casos da revisão e o retry
após a meia-noite.

## Pendências

- Rodar o checkpoint PostgreSQL da revisão (integração do Financeiro, Estoque, fluxo da OS, IAM e suíte completa).
- `TASK-0016` — Caixa físico / sessão de caixa (`BACKLOG`), pré-requisito para aceitar `DINHEIRO`.
- Sem CI remoto configurado nesta branch; tratado fora desta Task.

## Gates

Docker fechado no desenvolvimento. No checkpoint: integração do Financeiro e do Orçamento (incluindo
concorrência e idempotência), suíte backend completa, `npm test -- --run`, `tsc`, `build`, `lint`,
`git diff --check`. Docker fechado em seguida.

## Histórico

- 2026-09-22 — Task aberta em `BACKLOG`; `DR-0015` reescrita como `OPEN`.
- 2026-09-22 — Owner decidiu `DR-0015` e `DR-0008`. Especificação final, modelo de domínio e de banco e
  revisão `AG-02`/`AG-06`/`AG-08` concluídos; `DR-0017` aberta; Task em `IN_PROGRESS`.
- 2026-09-22 — Implementação concluída em commits separados; checkpoint PostgreSQL com 204 testes
  backend verdes; Task movida para `REVIEW`.
- 2026-09-22 — Revisão externa `CHANGES_REQUIRED`. F1, F2, F3, `DR-0017` e vencimento idempotente
  corrigidos (`V19`). Checkpoint PostgreSQL não executado por falha do Docker; Task mantida em `REVIEW`.
- 2026-09-22 — Nova revisão externa: F1–F3 confirmados; F4 (identidade do pedido idempotente) corrigido sem migration.
  Checkpoint PostgreSQL ainda pendente; Task mantida em `REVIEW`.
