# Frontend — TASK-0001 — Aprovação Parcial de Orçamento

## 1. Identificação

Task:

```text
TASK-0001
```

Requisito:

```text
REQ-ORC-001
```

Decision Request:

```text
DR-0001
```

Decisão:

```text
OPÇÃO B
```

Agente:

```text
AG-12 — Frontend React
```

Status:

```text
FRONTEND_CONTRACT_APPROVED
```

Revisão:

```text
2
```

Data:

```text
2026-09-08
```

Implementação React:

```text
NÃO
```

---

## 2. Objetivo

Definir a interface pública e interna para:

```text
aprovação;
rejeição;
pendência;
complemento;
reabertura;
stale;
expiração;
conflito.
```

---

## 3. Stack futura

```text
React
TypeScript
React Router
TanStack Query
React Hook Form
Zod
```

---

## 4. Página pública

Rota conceitual:

```text
/quote/:token
```

---

## 5. Dados exibidos

Nesta Task exibir:

```text
Uber-Hidráulica;
número da revisão;
validade;
itens;
descrição;
quantidade;
valor;
estado.
```

---

## 6. Fora do contrato público atual

Não depender nesta Task de:

```text
veículo;
placa;
dados administrativos da OS.
```

Esses dados poderão entrar quando a vertical slice Cliente/Veículo/OS for especificada.

---

## 7. Estados da decisão

```text
PENDENTE
APROVADO
REJEITADO
```

---

## 8. Disponibilidade

Além do estado, tratar:

```text
DECIDABLE
ALREADY_DECIDED
SUPERSEDED
```

---

## 9. Item decidível

Mostrar:

```text
[ Aprovar ]

[ Rejeitar ]
```

---

## 10. Item aprovado

Mostrar:

```text
Aprovado
```

Sem novos controles.

---

## 11. Item rejeitado

Mostrar:

```text
Rejeitado
```

Sem novos controles.

---

## 12. Item SUPERSEDED

Mostrar:

```text
Este item foi atualizado e esta versão não aceita mais novas decisões.
```

Sem:

```text
Aprovar
Rejeitar
```

---

## 13. Atualização

Quando existir nova versão apresentada:

o cliente precisa utilizar a proposta/link adequado fornecido pela oficina.

---

## 14. DR-0001

Nova versão somente em DRAFT:

```text
não deve alterar a página pública atual.
```

O cliente continua podendo decidir a versão anteriormente apresentada.

---

## 15. Nova versão PRESENTED

Se backend indicar:

```text
SUPERSEDED
```

a versão anterior deixa de possuir controles de decisão.

---

## 16. Complemento

Se nova revisão reutiliza:

```text
A-v1
```

o estado de A-v1 permanece coerente.

Não marcar como stale apenas porque:

```text
revisionNumber aumentou.
```

---

## 17. Fonte de verdade

Frontend nunca calcula stale baseado apenas em:

```text
revisionNumber.
```

Usar:

```text
decisionAvailability
```

do backend.

---

## 18. Seleção local

Antes de confirmar:

```text
APROVAR
REJEITAR
SEM DECISÃO
```

são intenções locais.

---

## 19. Item omitido

Não enviar no `decisions[]`.

---

## 20. Aceite

Checkbox obrigatório e inicialmente:

```text
desmarcado.
```

---

## 21. Identificação

Campos:

```text
Nome
CPF/CNPJ
```

---

## 22. Resumo

Antes de confirmar:

```text
Aprovar:
...

Rejeitar:
...

Sem decisão:
...
```

---

## 23. RequestId

Gerar:

```text
UUID
```

para submissão.

---

## 24. Retry técnico

Timeout:

```text
mesmo payload
+
mesmo requestId.
```

---

## 25. Mudança de conteúdo

Nova intenção:

```text
novo requestId.
```

---

## 26. Stale durante POST

Backend:

```text
409
QUOTE_ITEM_REVISION_STALE.
```

Frontend:

```text
descarta seleção daquele estado;
refetch;
mostra condição atual.
```

---

## 27. Already decided

Backend:

```text
409
QUOTE_ITEM_ALREADY_DECIDED.
```

Frontend:

```text
refetch.
```

---

## 28. Expiração

```text
410
PUBLIC_QUOTE_EXPIRED.
```

Tela:

```text
Este orçamento não está mais disponível para novas decisões.
Entre em contato com a oficina.
```

---

## 29. Link inválido

```text
404
PUBLIC_QUOTE_NOT_AVAILABLE.
```

Tela:

```text
Este link não está disponível.
```

---

## 30. Replay

```text
replayed=true
```

tratar como:

```text
sucesso.
```

---

## 31. Sucesso

Mostrar:

```text
Decisões registradas com sucesso.
```

e refazer GET.

---

## 32. Mobile

Página pública:

```text
responsiva;
cards verticais;
botões com área de toque adequada.
```

---

## 33. Segurança

Não salvar token em:

```text
localStorage.
```

---

## 34. Logs

Não executar:

```text
console.log(token);
console.log(customer);
console.log(request);
```

em produção.

---

## 35. XSS

Não utilizar:

```text
dangerouslySetInnerHTML
```

para descrição de serviço.

---

## 36. Página interna

Seção da OS:

```text
Orçamento
```

Mostrar separadamente:

```text
estado comercial
estado operacional.
```

---

## 37. Item stale internamente

Pode indicar:

```text
Versão anterior
```

ou:

```text
Substituída
```

sem apagar o histórico.

---

## 38. Reabertura

Ação:

```text
Reabrir
```

somente para usuário autorizado.

Mensagem:

```text
A decisão anterior será preservada e uma nova versão será criada.
```

---

## 39. Alteração comercial

Editar:

```text
preço;
descrição;
quantidade
```

deve alertar:

```text
Esta alteração exigirá nova aprovação quando a nova versão for apresentada.
```

---

## 40. Importante sobre DRAFT

A mensagem não deve afirmar que a aprovação anterior já foi invalidada no momento do rascunho.

---

## 41. Texto correto

```text
Quando esta nova condição for apresentada ao cliente,
a versão comercial anterior deixará de aceitar novas decisões.
```

---

## 42. Alteração interna

Mudar:

```text
técnico;
fornecedor;
custo
```

não deve gerar alerta de nova aprovação comercial.

---

## 43. Histórico

Exibir:

```text
revisão;
item;
versão;
decisão;
data;
reabertura;
apresentação.
```

---

## 44. Evidência

Documento mascarado.

IP/User-Agent somente em detalhe autorizado.

---

## 45. Componentes conceituais

```text
PublicQuotePage
QuoteItemCard
QuoteDecisionSelector
CustomerIdentificationForm
DecisionConfirmationDialog
DecisionSuccessView
PublicQuoteErrorView

QuotePanel
QuoteItemsTable
QuoteRevisionHistory
ReopenQuoteItemDialog
```

---

## 46. Server state

Usar:

```text
TanStack Query.
```

---

## 47. Redux

Não introduzir.

---

## 48. Testes frontend novos

```text
SUPERSEDED sem botões;

DRAFT não muda estado público;

stale 409 causa refetch;

complemento não marca item reutilizado como stale;

timeout preserva requestId;

item omitido continua fora do request.
```

---

## 49. LOW-01

Resolvido removendo dependência de:

```text
veículo;
placa
```

do fluxo público atual.

---

## 50. Resultado

```text
TASK:
TASK-0001

STATUS:
FRONTEND_CONTRACT_APPROVED

REVISION:
2

DR-0001:
INCORPORATED

SUPERSEDED:
SUPPORTED

DRAFT:
DOES NOT INVALIDATE

PRESENTED NEW ITEM REVISION:
INVALIDATES OLD VERSION FOR NEW DECISIONS

LOW-01:
RESOLVED

REACT:
NOT IMPLEMENTED

READY_FOR_QA:
YES
```

---

## 51. Regra final

Frontend não decide se uma versão ficou stale.

Ele recebe essa informação do backend.

```text
revisionNumber maior
```

não é suficiente.

A pergunta correta é:

```text
ESSA VERSÃO COMERCIAL
AINDA ESTÁ DECIDÍVEL?
```

**ESTADO VISUAL DEVE REFLETIR O DOMÍNIO, NÃO RECONSTRUIR O DOMÍNIO NO BROWSER.**