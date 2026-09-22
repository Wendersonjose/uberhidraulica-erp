# TASK-0015 — Financeiro: contas a receber da OS, recebimentos, contas a pagar e fluxo de caixa

## Identificação

- Status: `BACKLOG` — especificação em andamento; implementação **bloqueada** pela `DR-0015`
- Prioridade: `HIGH`
- Criada em: `2026-09-22`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer", cartões 31 a 34
- Proprietário principal: Financeiro — `AG-06`
- Pré-condições cumpridas: `TASK-0014` em `DONE` e enviada para `feature/autonomous-sprint-catalog`

## Cartões Trello cobertos

| Cartão | Escopo |
| --- | --- |
| Contas a receber da ordem de serviço | recebível ligado à OS, valor original, descontos/acréscimos, saldo; finalizar ≠ receber; cancelamento com estorno |
| Registro de recebimentos e formas de pagamento | recebimento total/parcial, data, forma, usuário; estorno próprio e auditável |
| Contas a pagar e despesas | descrição/fornecedor, categoria, valor, vencimento, situação; baixa total/parcial; cancelamento preserva histórico |
| Fluxo de caixa e visão financeira | filtros por período, categoria e situação; previsto × realizado; tudo derivado dos lançamentos |

## Decisões

- `DR-0015` — `OPEN`. Treze perguntas (F-01 a F-13) sobre momento e valor do recebível, parcelamento,
  vencimento, excedente, desconto/juros/multa, estorno, cancelamento da OS, formas de pagamento, caixa
  físico, regime do fluxo, inadimplência, fornecedor/categoria e permissões.
- `DR-0008` — `OPEN`. Bloqueia F-02 se o valor do recebível for o do orçamento aprovado.
- `DR-0007` — decidida. Política monetária.
- `DR-0009` — decidida provisoriamente. Oficina única.

## Arquitetura prevista (não depende de regra financeira)

- Módulo novo `finance`, `allowedDependencies = {workorder, crm, iam}` — `quote` apenas se F-02 = B.
- A OS continua sem conhecer o Financeiro: o Financeiro reage a `WorkOrderEvents.Finished` e
  `WorkOrderEvents.Cancelled`, que já existem, na mesma transação da OS (mesmo desenho do Estoque),
  para que uma recusa (por exemplo, F-08 = A) desfaça a operação da OS.
- Nenhuma leitura de tabela de outro módulo; valores da OS por contrato público (`WorkOrderQuery`).
- Saldo, situação e totais do fluxo **sempre derivados** dos lançamentos; nenhuma coluna de saldo
  consolidado mantida à mão.
- Lançamentos imutáveis; correção por estorno ligado ao original, com índice único impedindo estorno
  duplo — mesmo padrão já testado no Estoque.
- Concorrência: bloqueio da linha do recebível (`SELECT … FOR UPDATE`) em recebimento e estorno, para
  que dois recebimentos simultâneos não ultrapassem o saldo. Teste real contra PostgreSQL obrigatório.
- Idempotência: um recebível por OS (se F-03 = A) garantido por índice único; reprocesso do evento de
  finalização não gera segundo recebível.

## Critérios de aceite (a completar depois da DR-0015)

Serão escritos a partir das escolhas de F-01 a F-13. Antes disso, só estes valem, porque vêm de fonte
aprovada:

1. Finalizar a OS nunca registra recebimento.
2. Nenhum lançamento financeiro é editado ou apagado; correção é estorno próprio, com usuário e instante.
3. Soma dos recebimentos não estornados nunca ultrapassa o valor devido do recebível.
4. Valores monetários em `BigDecimal`/`NUMERIC`, conforme `DR-0007`; ArchUnit proíbe `float`/`double` no módulo.
5. Fluxo de caixa distingue previsto de realizado e é calculado dos lançamentos.
6. Cancelar uma conta a pagar preserva o histórico.

## Gates previstos

Desenvolvimento com Docker fechado. No fim: testes de integração do Financeiro (incluindo
concorrência e idempotência), suíte backend completa, frontend (`npm test -- --run`, `tsc`, `build`,
`lint`), `git diff --check`.

## Histórico

- 2026-09-22 — Task aberta em `BACKLOG` após o fechamento da `TASK-0014`. `DR-0015` reescrita como
  `OPEN`: a versão provisória de 2026-09-17 foi revogada, sem nada implementado.
