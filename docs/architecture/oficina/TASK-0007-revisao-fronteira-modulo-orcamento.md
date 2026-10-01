# Revisão de arquitetura — Fronteira do módulo Orçamento

- Origem: `TASK-0007`
- Revisa: `docs/architecture/oficina/TASK-0001-aprovacao-parcial-orcamento.md`, seções 3 e 7
- Agente proprietário: `AG-02 — Arquitetura`
- Status: `APPROVED` para o escopo da TASK-0007
- Data: `2026-09-15`

> Este documento **não substitui** a arquitetura aprovada da TASK-0001. Ele registra explicitamente
> onde a implementação se afastou dela e por quê, conforme a regra de não reescrever documento
> histórico para fazê-lo parecer consistente com código novo.

## 1. O que a TASK-0001 aprovou

A arquitetura aprovada atribui `Quote`, `QuoteRevision`, `QuoteItem`, `QuoteItemRevision`,
`PublicQuoteAccess`, `QuoteDecisionSubmission` e `QuoteDecision` ao **módulo Oficina**, e o modelo de
dados usa o schema `workshop`.

Naquele momento nenhum módulo executável existia: a TASK-0001 era especificação, e "Oficina" era um
nome conceitual para o domínio de OS e orçamento juntos.

## 2. O que a implementação fez

O orçamento foi implementado como **módulo Spring Modulith próprio**:

```text
br.com.uberhidraulica.erp.quote
@ApplicationModule(displayName = "Orçamento",
                   allowedDependencies = {"workorder", "iam"})
```

O schema de banco permanece `workshop`, exatamente como aprovado.

## 3. Por que a divisão

Quando a TASK-0004 implementou a OS, ela criou o módulo `workorder`. Colocar o orçamento dentro dele
produziria um módulo com duas razões distintas para mudar:

```text
workorder   registro operacional do atendimento
quote       condição comercial apresentada e decidida pelo cliente
```

O orçamento é o módulo que vai receber acesso público por token, submissão de decisão do cliente,
evidências e idempotência — superfície de segurança que não tem relação alguma com abrir uma OS.
Mantê-los juntos faria o `workorder` herdar essa superfície sem precisar dela.

A divisão também tornou explícito, e verificável pelo `ModularityTest`, que o orçamento **lê** a OS por
contrato e nunca alcança sua persistência.

## 4. O que a divisão exigiu

Duas interfaces públicas novas, ambas mínimas:

```java
br.com.uberhidraulica.erp.workorder.WorkOrderQuery
    Optional<WorkOrderReference> workOrder(UUID id);
    List<ServiceItemReference> serviceItems(UUID workOrderId);

br.com.uberhidraulica.erp.iam.CurrentUser
    Optional<UUID> id();
    UUID requireId();
```

`CurrentUser` existe porque `created_by` é obrigatório em quatro tabelas do modelo aprovado. Sem ele, o
módulo Orçamento precisaria do `IamPrincipal`, que é interno ao IAM. A interface entrega apenas a
identidade do autor: autorização continua sendo `IamAuthorization`, e nada nela concede acesso.

## 5. O que não mudou

```text
schema workshop                          preservado
tabelas e colunas aprovadas              preservadas
FKs compostas contra cross-quote         preservadas
UNIQUE(id, quote_id) nas três tabelas    preservado
proibição de is_stale/current            preservada
DR-0001 opção B                          preservada
validade comercial de 7 dias             preservada
```

## 6. Consequência para a próxima Task

O acesso público (`PublicQuoteAccess`) e a decisão do cliente (`QuoteDecisionSubmission`,
`QuoteDecision`) pertencem a este mesmo módulo `quote`, e não ao `workorder`. O token público nunca
deve conceder acesso a nada do módulo `workorder`, o que a fronteira agora impede estruturalmente.

## 7. Ponto que permanece aberto

O modelo aprovado prevê `quote_item.work_order_service_id`, mas não há equivalente para o item físico
da OS, que só passou a existir na TASK-0006. Registrado em `DR-0008`, sem alterar o modelo aprovado
por conta própria.
