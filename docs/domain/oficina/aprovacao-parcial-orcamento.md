# Domínio Oficina — Aprovação Parcial de Orçamento

## 1. Identificação

Documento:

```text
Aprovação Parcial de Orçamento
```

Módulo:

```text
Oficina
```

Subdomínio:

```text
Orçamento
```

Task:

```text
TASK-0001
```

Requisito:

```text
REQ-ORC-001
```

Decision Request relacionada:

```text
DR-0001 — Momento de obsolescência de versão comercial pendente
```

Decisão:

```text
OPÇÃO B
```

Agente responsável:

```text
AG-03 — Domínio Oficina
```

Status:

```text
DOMAIN_APPROVED
```

Revisão:

```text
2
```

Data:

```text
2026-09-08
```

---

# 2. Objetivo

Definir o modelo de domínio necessário para permitir:

```text
aprovação individual;

rejeição individual;

pendência;

aprovação parcial;

versionamento comercial;

complementos;

reabertura;

expiração;

histórico;

evidências;

idempotência;

concorrência;

obsolescência comercial.
```

---

# 3. Princípio central

O orçamento não é aprovado como um bloco indivisível.

A decisão pertence a:

```text
UMA VERSÃO COMERCIAL ESPECÍFICA
DE UM ITEM ESPECÍFICO
```

apresentado ao cliente.

---

# 4. Conceitos principais

```text
WorkOrder

Quote

QuoteRevision

QuoteItem

QuoteItemRevision

QuoteDecisionSubmission

QuoteDecision

PublicQuoteAccess

DecisionEvidence
```

---

# 5. WorkOrder

Representa:

```text
Ordem de Serviço
```

O orçamento existe dentro do contexto de uma OS.

Relacionamento conceitual:

```text
WorkOrder
1
↓
0..N
Quote
```

---

# 6. Quote

Representa:

```text
identidade lógica do orçamento.
```

Não representa uma apresentação comercial específica.

Um Quote pode possuir:

```text
múltiplas QuoteRevision.
```

---

# 7. QuoteRevision

Representa:

```text
uma apresentação global do orçamento.
```

Exemplos:

```text
R1
R2
R3
```

Estados:

```text
DRAFT

PRESENTED
```

---

# 8. DRAFT

Significa:

```text
revisão em preparação interna.
```

Ainda não representa nova proposta efetivamente apresentada ao cliente.

---

# 9. PRESENTED

Significa:

```text
revisão efetivamente apresentada ao cliente.
```

Esse estado possui relevância comercial.

---

# 10. QuoteItem

Representa:

```text
identidade lógica permanente de um item comercial.
```

Exemplo:

```text
Serviço de reparo da caixa de direção
```

O mesmo QuoteItem pode possuir várias versões comerciais.

---

# 11. QuoteItemRevision

Representa:

```text
uma condição comercial específica do QuoteItem.
```

Inclui snapshot de:

```text
descrição;

quantidade;

preço unitário;

valor total.
```

---

# 12. Exemplo

```text
QuoteItem A

A-v1
Descrição X
Quantidade 1
Preço R$ 500

A-v2
Descrição X
Quantidade 1
Preço R$ 600
```

A-v1 e A-v2 são:

```text
fatos comerciais diferentes.
```

---

# 13. QuoteRevision e QuoteItemRevision

Uma QuoteRevision global referencia versões comerciais específicas.

Exemplo:

```text
R1
├── A-v1
└── B-v1
```

Nova revisão:

```text
R2
├── A-v1
├── B-v2
└── C-v1
```

---

# 14. Consequência

Uma nova QuoteRevision:

```text
não implica obrigatoriamente
nova QuoteItemRevision para todos os itens.
```

---

# 15. QuoteDecisionSubmission

Representa:

```text
uma ação pública de decisão confirmada pelo cliente.
```

Uma submissão pode conter:

```text
uma ou várias decisões.
```

---

# 16. Atomicidade da Submission

Exemplo:

```text
A → APPROVE

B → REJECT
```

A submissão é:

```text
ATÔMICA.
```

Ou ambas são consolidadas:

```text
ou nenhuma.
```

---

# 17. QuoteDecision

Representa:

```text
um fato histórico individual.
```

Tipos de decisão:

```text
APPROVE

REJECT
```

---

# 18. Pending

Não existe necessariamente um fato:

```text
PENDING
```

persistido como decisão.

Ausência de QuoteDecision significa:

```text
PENDENTE_APROVACAO
```

quando a versão ainda pode ser decidida.

---

# 19. PublicQuoteAccess

Representa:

```text
autorização pública limitada.
```

Vinculada a:

```text
Quote

QuoteRevision
```

específicos.

---

# 20. PublicQuoteAccess não é autenticação IAM

O cliente externo:

```text
não é usuário interno.
```

O token apenas autoriza determinado acesso público.

---

# 21. DecisionEvidence

Evidências associadas à submissão:

```text
nome informado;

CPF/CNPJ informado;

aceite explícito;

timestamp do servidor;

IP;

User-Agent;

revisão apresentada;

itens decididos.
```

---

# 22. Identidade informada

O domínio registra:

```text
identidade declarada pelo cliente.
```

Não afirma:

```text
identidade civil criptograficamente comprovada.
```

---

# 23. Agregado principal

Para as operações comerciais desta Task, o agregado conceitual principal é:

```text
Quote
```

com controle das:

```text
QuoteRevision

QuoteItem

QuoteItemRevision
```

necessárias às invariantes.

---

# 24. Histórico de decisão

`QuoteDecision` é fato histórico.

Não deve ser tratado como campo mutável em:

```text
QuoteItem.currentDecision
```

sobrescrito a cada mudança.

---

# 25. Estado comercial derivado

Para uma QuoteItemRevision:

```text
se existe APPROVE
→ APROVADO

se existe REJECT
→ REJEITADO

se não existe decisão
e ainda está decidível
→ PENDENTE_APROVACAO
```

---

# 26. Versão obsoleta sem decisão

Uma QuoteItemRevision pode não possuir decisão, mas já não aceitar novas decisões.

Isso não deve ser confundido com:

```text
REJEITADO.
```

---

# 27. Conceito de decidibilidade

Uma QuoteItemRevision está:

```text
DECIDÍVEL
```

somente se todas as condições relevantes forem verdadeiras.

---

# 28. Condições mínimas de decidibilidade

```text
1. pertence ao Quote correto;

2. foi efetivamente apresentada;

3. pertence à QuoteRevision autorizada pelo acesso;

4. não possui decisão efetiva;

5. proposta comercial ainda está válida;

6. PublicQuoteAccess ainda está válido;

7. PublicQuoteAccess não está revogado;

8. não foi comercialmente substituída conforme DR-0001.
```

---

# 29. Aprovação parcial

Dado:

```text
A-v1 pending
B-v1 pending
C-v1 pending
```

Comando:

```text
APPROVE A-v1
APPROVE B-v1
```

Resultado:

```text
A-v1 approved
B-v1 approved
C-v1 pending
```

---

# 30. Rejeição parcial

Comando:

```text
APPROVE A-v1
REJECT B-v1
```

Resultado:

```text
A-v1 approved
B-v1 rejected
C-v1 pending
```

---

# 31. Item omitido

Se C não estiver na Submission:

```text
nenhuma decisão é inferida.
```

Resultado:

```text
C permanece sem decisão.
```

---

# 32. Regra contra rejeição implícita

Invariante:

```text
AUSÊNCIA DE DECISÃO
NUNCA SIGNIFICA
REJEIÇÃO.
```

---

# 33. Uma decisão por QuoteItemRevision

No escopo da TASK-0001:

```text
cada QuoteItemRevision
possui no máximo
uma QuoteDecision efetiva.
```

---

# 34. Decisão já registrada

Dado:

```text
A-v1 APPROVED
```

Novo comando independente:

```text
REJECT A-v1
```

Resultado:

```text
REJEITAR COMANDO.
```

---

# 35. Mesmo comando novamente

Dado:

```text
A-v1 APPROVED
```

Nova submissão independente:

```text
APPROVE A-v1
```

Resultado:

```text
não criar segunda decisão.
```

---

# 36. Idempotência não é retratação

Replay da mesma Submission é diferente de:

```text
nova decisão.
```

---

# 37. Retratação

Não existe nesta Task.

Não permitir:

```text
APPROVED
→
REJECTED
```

na mesma QuoteItemRevision.

---

# 38. Reabertura

Um item rejeitado pode receber nova oportunidade através de:

```text
nova QuoteItemRevision.
```

---

# 39. Reabertura preserva rejeição

Antes:

```text
A-v1
REJECTED
```

Depois:

```text
A-v1
REJECTED — histórico

A-v2
PENDING
```

---

# 40. Reabertura com mesmos termos

Mesmo que:

```text
descrição;
quantidade;
preço
```

não mudem:

```text
nova QuoteItemRevision
```

é necessária porque existe:

```text
nova oportunidade de decisão.
```

---

# 41. Alteração comercial

Alterações customer-facing que exigem nova versão:

```text
descrição;

quantidade;

preço.
```

---

# 42. Alteração de preço

Antes:

```text
A-v1
R$ 500
```

Depois:

```text
A-v2
R$ 600
```

A-v1 e A-v2 são versões comerciais distintas.

---

# 43. Alteração de descrição

Mesmo princípio:

```text
nova QuoteItemRevision.
```

---

# 44. Alteração de quantidade

Mesmo princípio:

```text
nova QuoteItemRevision.
```

---

# 45. Alteração interna

Mudanças exclusivamente internas não criam automaticamente nova versão comercial.

Exemplos:

```text
técnico;

fornecedor;

custo;

referência operacional.
```

---

# 46. Snapshot

Depois da apresentação:

```text
QuoteItemRevision
```

representa snapshot histórico.

Não modificar seu conteúdo comercial para representar nova condição.

---

# 47. Complemento

Exemplo:

```text
R1
├── A-v1
└── B-v1
```

Cliente aprovou:

```text
A-v1.
```

Posteriormente:

```text
R2
├── A-v1
├── B-v1
└── C-v1
```

Resultado:

```text
A-v1 continua approved.

B-v1 mantém seu estado anterior.

C-v1 inicia pending.
```

---

# 48. Não duplicar aprovação

Reutilizar:

```text
A-v1
```

em R2 não cria nova aprovação.

---

# 49. Regra global versus item

A versão global:

```text
QuoteRevision
```

não determina sozinha a obsolescência de todos os itens anteriores.

A decisão é analisada por:

```text
QuoteItem
+
QuoteItemRevision.
```

---

# 50. DR-0001

Decisão oficial:

```text
OPÇÃO B
```

---

# 51. Regra DR-0001 — DRAFT

Se existir:

```text
A-v1
PRESENTED
PENDING
```

e for criada:

```text
A-v2
```

dentro de uma revisão:

```text
DRAFT
```

então:

```text
A-v1 NÃO se torna obsoleta.
```

---

# 52. Justificativa

DRAFT representa:

```text
preparação interna.
```

Ainda não existe nova proposta efetivamente apresentada ao cliente.

---

# 53. Regra DR-0001 — PRESENTED

Quando uma nova versão comercial do mesmo QuoteItem:

```text
A-v2
```

for incluída em uma QuoteRevision que se torna:

```text
PRESENTED
```

então, para novas decisões:

```text
A-v1 torna-se comercialmente obsoleta.
```

---

# 54. Consequência

Depois da apresentação de A-v2:

```text
A-v1
```

não pode receber:

```text
APPROVE
```

nem:

```text
REJECT
```

como nova decisão.

---

# 55. História permanece

Obsolescência não remove:

```text
QuoteItemRevision;

QuoteRevision;

QuoteDecision;

QuoteDecisionSubmission.
```

---

# 56. Aprovação anterior

Se A-v1 havia sido aprovada antes de A-v2 ser apresentada:

```text
A-v1 APPROVED
```

continua como fato histórico.

---

# 57. Estado comercial corrente após alteração

Depois da apresentação de:

```text
A-v2
```

a condição comercial que necessita nova decisão é:

```text
A-v2.
```

A aprovação histórica de A-v1:

```text
não é transferida para A-v2.
```

---

# 58. Rejeição anterior

Mesma regra:

```text
A-v1 REJECTED
```

não implica:

```text
A-v2 REJECTED.
```

---

# 59. Complemento e DR-0001

Se R2 apresenta:

```text
A-v1
B-v1
C-v1 novo
```

A-v1 não é substituída porque:

```text
nenhuma nova QuoteItemRevision de A
foi apresentada.
```

---

# 60. Critério formal de obsolescência

Uma `QuoteItemRevision X` sem decisão está obsoleta para novas decisões quando existir:

```text
outra QuoteItemRevision Y
```

tal que:

```text
Y pertence ao mesmo QuoteItem;

Y representa versão posterior;

Y foi efetivamente incluída em
uma QuoteRevision PRESENTED.
```

---

# 61. Nova revisão global não é suficiente

Não utilizar regra:

```text
latest QuoteRevision > current QuoteRevision
→ stale
```

porque isso quebraria complementos.

---

# 62. Regra correta

Utilizar semântica equivalente a:

```text
EXISTE VERSÃO COMERCIAL POSTERIOR
DO MESMO ITEM
EFETIVAMENTE APRESENTADA?
```

Se:

```text
SIM
→ versão anterior não aceita nova decisão.
```

---

# 63. PublicQuoteAccess não supera domínio

Mesmo que:

```text
token válido;
token não expirado;
token não revogado;
```

se a QuoteItemRevision estiver obsoleta:

```text
decisão deve ser rejeitada.
```

---

# 64. QuoteRevision ainda pode conter item histórico

Uma revisão histórica continua registrando:

```text
o que foi apresentado naquela época.
```

Isso não significa que todos os seus itens continuam indefinidamente decidíveis.

---

# 65. Validade comercial

`QuoteRevision.validUntil` representa:

```text
validade da proposta comercial.
```

---

# 66. Validade do acesso

`PublicQuoteAccess.validUntil` representa:

```text
validade da credencial pública.
```

---

# 67. Decisão exige ambas

Para nova decisão:

```text
validade comercial
E
validade do acesso
```

precisam permitir a operação.

---

# 68. Public access não amplia proposta

Invariante:

```text
PublicQuoteAccess.validUntil
não pode ampliar
QuoteRevision.validUntil.
```

---

# 69. Expiração

Se:

```text
Clock > QuoteRevision.validUntil
```

nova decisão pendente é bloqueada.

---

# 70. Decisão anterior após expiração

Permanece histórica e válida.

---

# 71. Clock

Tempo deve ser obtido de:

```text
Clock
```

controlável.

Não espalhar:

```text
Instant.now()
```

pelo domínio.

---

# 72. Comandos do domínio

Comandos conceituais:

```text
CreateQuote

CreateQuoteRevision

PresentQuoteRevision

RegisterQuoteDecisions

ReopenRejectedQuoteItem
```

---

# 73. CreateQuote

Cria:

```text
Quote
```

vinculado a:

```text
WorkOrder.
```

---

# 74. CreateQuoteRevision

Cria revisão:

```text
DRAFT.
```

Pode:

```text
reutilizar QuoteItemRevision existente;

criar nova QuoteItemRevision;

incluir novo QuoteItem.
```

---

# 75. Criar DRAFT não tem efeito sobre proposta atual

Regra DR-0001:

```text
CreateQuoteRevision
```

não invalida uma apresentação anterior apenas porque novas versões foram preparadas.

---

# 76. PresentQuoteRevision

Transição:

```text
DRAFT
→
PRESENTED
```

---

# 77. Efeito comercial da apresentação

Na apresentação, para cada QuoteItemRevision incluída:

o domínio deve determinar se ela representa:

```text
mesma versão já apresentada
```

ou:

```text
nova versão comercial do mesmo QuoteItem.
```

---

# 78. Mesma versão reutilizada

Se:

```text
A-v1
```

já existia e R2 reutiliza:

```text
A-v1
```

não ocorre substituição comercial de A.

---

# 79. Nova versão apresentada

Se R2 apresenta:

```text
A-v2
```

onde A-v1 já havia sido apresentada:

```text
A-v1 passa a não aceitar novas decisões.
```

---

# 80. RegisterQuoteDecisions

Responsabilidade:

```text
registrar uma Submission com uma ou mais decisões.
```

---

# 81. Validação antes da decisão

Para cada item:

```text
validar ownership;

validar revisão;

validar apresentação;

validar validade;

validar acesso;

validar decisão anterior;

validar obsolescência;

validar concorrência.
```

---

# 82. ReopenRejectedQuoteItem

Pré-condição:

```text
versão anterior possui REJECT.
```

Resultado:

```text
nova QuoteItemRevision.
```

---

# 83. Eventos do domínio

Eventos conceituais:

```text
QuoteCreated

QuoteRevisionCreated

QuoteRevisionPresented

QuoteItemApproved

QuoteItemRejected

QuoteItemReopened
```

---

# 84. QuoteRevisionPresented

Evento deve identificar:

```text
quoteId;

quoteRevisionId;

item revisions apresentadas;

occurredAt.
```

Não incluir token.

---

# 85. QuoteItemApproved

Representa:

```text
decisão efetivamente consolidada.
```

---

# 86. QuoteItemRejected

Mesmo princípio.

---

# 87. QuoteItemReopened

Representa nova oportunidade criada internamente.

---

# 88. Não criar evento de pending

Pendência é ausência de decisão.

Não é necessário evento:

```text
QuoteItemPending
```

para cada item apresentado.

---

# 89. Invariantes consolidadas

## I-01

```text
Uma QuoteRevision pertence a exatamente um Quote.
```

## I-02

```text
Um QuoteItem pertence a exatamente um Quote.
```

## I-03

```text
Uma QuoteItemRevision pertence a exatamente um QuoteItem.
```

## I-04

```text
QuoteRevision somente pode apresentar QuoteItemRevision
pertencente ao mesmo Quote.
```

## I-05

```text
Uma QuoteItemRevision apresentada é snapshot comercial.
```

## I-06

```text
Descrição, quantidade ou preço alterado exigem nova
QuoteItemRevision.
```

## I-07

```text
Alteração interna não exige nova versão comercial.
```

## I-08

```text
Decisão pertence à QuoteItemRevision exata.
```

## I-09

```text
Item omitido da Submission permanece sem decisão.
```

## I-10

```text
Ausência de decisão não é rejeição.
```

## I-11

```text
Uma QuoteItemRevision possui no máximo uma decisão efetiva.
```

## I-12

```text
Submission com múltiplas decisões é atômica.
```

## I-13

```text
Decisão histórica nunca é sobrescrita para representar
nova proposta.
```

## I-14

```text
Reabertura cria nova QuoteItemRevision.
```

## I-15

```text
Expiração bloqueia novas decisões,
não altera decisões existentes.
```

## I-16

```text
Token autoriza somente seu escopo.
```

## I-17

```text
Token válido não supera regras comerciais do domínio.
```

## I-18

```text
Replay idempotente não cria novo fato.
```

## I-19

```text
Nova QuoteItemRevision em DRAFT não invalida
versão anteriormente apresentada.
```

## I-20

```text
Nova QuoteItemRevision do mesmo QuoteItem torna a anterior
não decidível quando for efetivamente PRESENTED.
```

## I-21

```text
Nova QuoteRevision global não invalida automaticamente
todos os itens de revisão anterior.
```

## I-22

```text
Reutilizar a mesma QuoteItemRevision em complemento
não invalida aquela versão.
```

## I-23

```text
Obsolescência bloqueia novas decisões,
mas nunca remove decisões históricas.
```

## I-24

```text
PublicQuoteAccess não pode estender validade comercial.
```

## I-25

```text
Decision somente pode referenciar item efetivamente
apresentado na QuoteRevision da Submission.
```

---

# 90. Concorrência crítica — DR-0001

Cenário:

```text
A-v1
PRESENTED
PENDING
```

Existem simultaneamente:

```text
Operação X:
cliente decide A-v1

Operação Y:
gerente apresenta A-v2
```

---

# 91. Regra de serialização lógica

As duas operações devem possuir uma ordem efetiva.

Resultado permitido:

```text
X antes de Y
```

ou:

```text
Y antes de X
```

Nunca:

```text
ambas ignorando a existência da outra.
```

---

# 92. Cenário X vence

Se a decisão sobre A-v1 for consolidada antes da apresentação de A-v2:

```text
A-v1 recebe decisão válida.
```

Depois A-v2 pode ser apresentada.

Resultado:

```text
A-v1
APPROVED ou REJECTED — histórico

A-v2
PENDING
```

---

# 93. Importante

Se A-v1 foi APPROVED antes da apresentação de A-v2:

a aprovação:

```text
não é transferida para A-v2.
```

A-v2 exige nova decisão por representar nova condição comercial.

---

# 94. Cenário Y vence

Se A-v2 for efetivamente apresentada antes da consolidação da decisão de A-v1:

```text
A-v1 torna-se obsoleta.
```

A tentativa posterior de decidir A-v1 deve falhar.

---

# 95. Resultado esperado

Erro conceitual:

```text
QUOTE_ITEM_REVISION_STALE
```

ou código equivalente consolidado pela API.

---

# 96. Concorrência não pode depender do frontend

Mesmo que frontend tente atualizar rapidamente:

```text
backend continua responsável pela consistência.
```

---

# 97. Mecanismo técnico

O domínio define a invariante.

AG-11/AG-10 definem mecanismo como:

```text
optimistic locking;

constraints;

serialização adequada;

lock específico se demonstrado necessário.
```

---

# 98. Regra sobre commit

A implementação deve possuir um ponto de serialização observável.

Não pode existir resultado final onde:

```text
A-v2 já era a nova versão apresentada
```

e ainda assim uma nova decisão seja inserida em A-v1 sem conflito.

---

# 99. Não exigir pessimistic lock antecipadamente

A regra de domínio não exige tecnologia específica.

Não acoplar:

```text
domínio
```

a:

```text
SELECT FOR UPDATE.
```

---

# 100. Concorrência de duas decisões

Cenário:

```text
APPROVE A-v1
```

versus:

```text
REJECT A-v1
```

Resultado:

```text
somente uma decisão efetiva.
```

---

# 101. Concorrência de replay

Mesmo:

```text
requestId
+
payload
```

não cria nova decisão.

---

# 102. Idempotência

Identidade conceitual:

```text
PublicQuoteAccess
+
requestId.
```

---

# 103. Mesmo conteúdo

Se Submission já existir com mesmo conteúdo:

```text
retornar resultado equivalente.
```

---

# 104. Conteúdo diferente

Mesmo requestId com payload semanticamente diferente:

```text
conflito.
```

---

# 105. Canonicalização

A ordem:

```text
A approve
B reject
```

versus:

```text
B reject
A approve
```

não deve alterar a identidade semântica da mesma Submission.

---

# 106. Evidência da Submission

A evidência é snapshot.

Alterar cadastro do cliente posteriormente:

```text
não altera evidência antiga.
```

---

# 107. Evidência não é log técnico

`QuoteDecisionSubmission` e `QuoteDecision` são:

```text
dados de domínio/históricos.
```

Não apenas logs de aplicação.

---

# 108. Sem exclusão física

Não disponibilizar fluxo normal para:

```text
DELETE QuoteDecision.
```

---

# 109. Sem update da decisão

Não alterar:

```text
APPROVE
```

para:

```text
REJECT
```

através de update.

---

# 110. PublicQuoteAccess revogado

Revogação:

```text
bloqueia novas operações.
```

Não apaga decisões anteriores.

---

# 111. Expiração de PublicQuoteAccess

Bloqueia uso daquele acesso para nova decisão.

---

# 112. Expiração comercial versus acesso

Ambos são conceitos diferentes.

Nova decisão depende de ambos.

---

# 113. Fluxo principal

```text
OS existente
↓
Quote
↓
QuoteRevision DRAFT
↓
QuoteItem / QuoteItemRevision
↓
PresentQuoteRevision
↓
QuoteRevision PRESENTED
↓
PublicQuoteAccess
↓
cliente abre
↓
seleciona decisões
↓
identificação
↓
aceite
↓
RegisterQuoteDecisions
↓
valida escopo
↓
valida validade
↓
valida stale
↓
valida decisão anterior
↓
QuoteDecisionSubmission
↓
QuoteDecision
↓
eventos
```

---

# 114. Fluxo de complemento

```text
R1
A-v1
B-v1
↓
A-v1 aprovado
↓
criar R2
A-v1
B-v1
C-v1
↓
R2 PRESENTED
↓
A-v1 continua approved
C-v1 pending
```

---

# 115. Fluxo de alteração comercial

```text
R1
A-v1
R$ 500
PENDING
↓
criar A-v2
R$ 600
em DRAFT
↓
A-v1 continua decidível
↓
R2 com A-v2 torna-se PRESENTED
↓
A-v1 deixa de aceitar nova decisão
A-v2 pending
```

---

# 116. Fluxo com aprovação antes da nova apresentação

```text
A-v1 pending
↓
A-v2 DRAFT
↓
cliente aprova A-v1
↓
A-v1 approved
↓
A-v2 PRESENTED
↓
A-v1 approval permanece histórica
A-v2 pending
```

---

# 117. Fluxo com nova apresentação primeiro

```text
A-v1 pending
↓
A-v2 DRAFT
↓
A-v2 PRESENTED
↓
cliente tenta aprovar A-v1
↓
REJECT COMMAND
QUOTE_ITEM_REVISION_STALE
```

---

# 118. Fluxo de reabertura

```text
A-v1 rejected
↓
ReopenRejectedQuoteItem
↓
A-v2
↓
nova QuoteRevision
↓
PRESENTED
↓
A-v2 pending
```

A-v1:

```text
continua rejected.
```

---

# 119. Casos proibidos

```text
aprovar item não apresentado;

aprovar item de outro Quote;

aprovar versão comercial substituída;

aprovar item expirado;

decidir usando acesso revogado;

criar segunda decisão para mesma QuoteItemRevision;

transformar ausência de decisão em rejeição;

sobrescrever decisão histórica;

transferir aprovação automaticamente entre versões;

invalidar item somente porque existe nova QuoteRevision global.
```

---

# 120. Operações que não pertencem ao domínio desta Task

```text
pagamento;

conta a receber;

comissão;

NFS-e;

reserva real de estoque;

compra;

WhatsApp;

garantia.
```

---

# 121. Eventos e outros módulos

A aprovação poderá futuramente ser consumida por:

```text
Estoque;

Execução da OS;

Compras.
```

Mas esses módulos não acessam:

```text
repository interno de Quote.
```

---

# 122. Handoff AG-03 → AG-02

```text
Task:
TASK-0001

Status:
DOMAIN_APPROVED

Revision:
2

DR-0001:
INCORPORATED

Regra de stale:
- DRAFT não invalida;
- nova ItemRevision PRESENTED invalida versão anterior
  do mesmo QuoteItem para novas decisões;
- nova QuoteRevision global não invalida tudo;
- complemento com mesma ItemRevision preserva versão.

Concorrência:
DECIDE old version
x
PRESENT new version

Regra:
- se DECIDE consolida primeiro, decisão é histórica válida;
- se PRESENT consolida primeiro, decisão antiga deve falhar;
- implementação deve possuir serialização/conflito coerente.

Histórico:
preservado.

Pronto para arquitetura:
SIM
```

---

# 123. Handoff AG-03 → AG-11

```text
Backend deve possuir operação capaz de determinar:

isDecidable(QuoteItemRevision, PublicQuoteAccess, Clock)

incluindo:

- apresentada;
- acesso correto;
- validade;
- ausência de decisão;
- ausência de versão comercial posterior apresentada.

Erro esperado para versão comercial substituída:

QUOTE_ITEM_REVISION_STALE
ou nomenclatura final equivalente.

Apresentação de nova versão e decisão da antiga
precisam participar da estratégia de concorrência.
```

---

# 124. Handoff AG-03 → AG-13

Adicionar testes obrigatórios:

```text
DOM-DR01
A-v2 DRAFT não invalida A-v1.

DOM-DR02
A-v2 PRESENTED invalida A-v1 pendente para novas decisões.

DOM-DR03
A-v1 aprovada antes da apresentação de A-v2
permanece histórica.

DOM-DR04
R2 com A-v1 + complemento não invalida A-v1.

DOM-DR05
Token válido não supera stale.

DOM-DR06
DECIDE A-v1 vence concorrência
→ decisão válida histórica.

DOM-DR07
PRESENT A-v2 vence concorrência
→ decisão A-v1 rejeitada.
```

---

# 125. Resultado do AG-03

```text
TASK:
TASK-0001

STATUS:
DOMAIN_APPROVED

REVISION:
2

PARTIAL APPROVAL:
DEFINED

REJECTION:
DEFINED

PENDING:
DEFINED

COMMERCIAL VERSION:
DEFINED

GLOBAL REVISION:
DEFINED

COMPLEMENT:
DEFINED

REOPEN:
DEFINED

EXPIRATION:
DEFINED

IDEMPOTENCY:
DEFINED

ATOMICITY:
DEFINED

PUBLIC ACCESS:
DEFINED

HISTORY:
DEFINED

DR-0001:
INCORPORATED

DRAFT INVALIDATES:
NO

PRESENTED NEW ITEM REVISION INVALIDATES OLD PENDING VERSION:
YES

GLOBAL REVISION INVALIDATES EVERY OLD ITEM:
NO

CONCURRENCY DECIDE x PRESENT:
DEFINED

BLOCKING DECISION REQUESTS:
0

READY FOR ARCHITECTURE:
YES
```

---

# 126. Definition of Done — revisão de domínio

- [x] conceitos principais definidos;
- [x] Quote definido;
- [x] QuoteRevision definida;
- [x] QuoteItem definido;
- [x] QuoteItemRevision definida;
- [x] Submission definida;
- [x] Decision definida;
- [x] PublicQuoteAccess definido;
- [x] aprovação parcial definida;
- [x] pendência definida;
- [x] rejeição definida;
- [x] histórico definido;
- [x] snapshot definido;
- [x] complemento definido;
- [x] reabertura definida;
- [x] expiração definida;
- [x] idempotência definida;
- [x] atomicidade definida;
- [x] evidência definida;
- [x] DR-0001 incorporada;
- [x] obsolescência por item definida;
- [x] DRAFT tratado;
- [x] PRESENTED tratado;
- [x] concorrência DECIDE × PRESENT definida;
- [x] invariantes consolidadas;
- [x] nenhuma Decision Request impeditiva.

---

# 127. Regra final

O domínio não pergunta apenas:

```text
"ESSA VERSÃO EXISTE?"
```

Ele pergunta:

```text
"ESSA É UMA VERSÃO COMERCIAL
QUE AINDA PODE RECEBER
UMA NOVA DECISÃO?"
```

Uma versão pode existir historicamente e ainda assim não estar mais decidível.

A regra oficial é:

```text
NOVA VERSÃO EM DRAFT
→ NÃO INVALIDA A ANTERIOR

NOVA VERSÃO DO MESMO ITEM PRESENTED
→ ANTERIOR NÃO ACEITA NOVA DECISÃO

MESMA ITEM REVISION EM COMPLEMENTO
→ NÃO INVALIDA

DECISÃO ANTIGA
→ NUNCA É APAGADA
```

Na corrida:

```text
DECIDE A-v1
×
PRESENT A-v2
```

o sistema precisa produzir uma ordem consistente:

```text
SE DECIDE VENCE
→ decisão A-v1 é válida historicamente

SE PRESENT VENCE
→ nova decisão em A-v1 é rejeitada
```

**UMA VERSÃO COMERCIAL PODE CONTINUAR EXISTINDO PARA SEMPRE NO HISTÓRICO SEM CONTINUAR ABERTA PARA NOVAS DECISÕES.**