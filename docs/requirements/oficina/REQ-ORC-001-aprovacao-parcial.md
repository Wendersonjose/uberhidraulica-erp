# REQ-ORC-001 — Aprovação Parcial de Orçamento

## 1. Identificação

Código:

```text
REQ-ORC-001
```

Título:

```text
Aprovação Parcial de Orçamento
```

Módulo:

```text
Oficina / Orçamento
```

Task:

```text
TASK-0001 — Aprovação Parcial de Orçamento
```

Agente responsável:

```text
AG-01 — Produto & Requisitos
```

Agente de domínio:

```text
AG-03 — Domínio Oficina
```

Status:

```text
APPROVED
```

Prioridade:

```text
HIGH
```

Revisão:

```text
2
```

Data da revisão:

```text
2026-09-08
```

Decision Request relacionada:

```text
DR-0001 — Momento de obsolescência de versão comercial pendente
```

Decisão:

```text
OPÇÃO B
```

---

## 2. Objetivo

Permitir que o cliente analise um orçamento e tome decisões individualmente sobre cada item apresentado.

O cliente pode:

```text
aprovar;

rejeitar;

não decidir.
```

A ausência de decisão sobre um item não impede a consolidação das decisões tomadas sobre outros itens.

---

## 3. Contexto

A Ordem de Serviço existe antes do orçamento.

Relacionamento:

```text
WorkOrder
↓
Quote
↓
QuoteRevision
↓
QuoteItem / QuoteItemRevision
```

A aprovação do orçamento:

```text
não cria a OS;
não fecha a OS;
não significa pagamento;
não significa recebimento;
não emite NFS-e;
não gera comissão automaticamente.
```

---

## 4. Cliente externo

O cliente não precisa possuir:

```text
usuário;
senha;
perfil;
sessão interna.
```

O acesso ocorre através de:

```text
link público seguro.
```

---

## 5. Estados funcionais da decisão

Estados funcionais:

```text
PENDENTE_APROVACAO

APROVADO

REJEITADO
```

---

## 6. Pendência

Ausência de decisão significa:

```text
PENDENTE_APROVACAO
```

enquanto a versão continuar apta a receber decisão.

Regra:

```text
SEM DECISÃO
≠
REJEITADO
```

---

## 7. Aprovação parcial

Exemplo:

```text
Item A → APROVADO
Item B → REJEITADO
Item C → PENDENTE_APROVACAO
```

Esse estado é válido.

O sistema não exige decisão completa do orçamento.

---

## 8. Execução independente

Um item aprovado pode seguir para seu fluxo operacional permitido mesmo que outros itens permaneçam:

```text
pendentes;
rejeitados.
```

Estado comercial e estado operacional são conceitos distintos.

Exemplo:

```text
COMERCIAL:
APROVADO

OPERACIONAL:
NÃO INICIADO
```

---

## 9. Histórico

Toda decisão consolidada é histórica.

É proibido sobrescrever uma decisão anterior para representar uma nova condição comercial.

Exemplo proibido:

```text
A-v1
R$ 500
APROVADO
```

ser alterado para:

```text
A-v1
R$ 600
APROVADO
```

A condição de R$ 600 precisa ser representada por nova versão.

---

## 10. QuoteRevision

`QuoteRevision` representa:

```text
uma apresentação global do orçamento.
```

Estados técnicos mínimos:

```text
DRAFT

PRESENTED
```

---

## 11. QuoteItem

`QuoteItem` representa:

```text
a identidade lógica permanente de um item comercial.
```

---

## 12. QuoteItemRevision

`QuoteItemRevision` representa:

```text
uma condição comercial específica daquele item.
```

Inclui, no mínimo:

```text
descrição;
quantidade;
preço.
```

---

## 13. Alterações comerciais

As seguintes alterações exigem nova versão comercial:

```text
preço;

descrição apresentada ao cliente;

quantidade apresentada ao cliente.
```

A nova versão exige nova decisão.

---

## 14. Alterações internas

Alterações exclusivamente internas não invalidam automaticamente uma decisão comercial.

Exemplos:

```text
técnico;

fornecedor;

custo;

referência interna;

informação operacional.
```

---

## 15. Snapshot comercial

A decisão deve permanecer vinculada exatamente à condição comercial visualizada pelo cliente.

Alterações posteriores no:

```text
catálogo;

preço padrão;

descrição padrão;

custos internos
```

não alteram retroativamente o conteúdo aprovado.

---

## 16. Complemento

Uma nova revisão global pode adicionar itens sem alterar versões comerciais existentes.

Exemplo:

```text
R1
├── A-v1
└── B-v1
```

Depois:

```text
R2
├── A-v1
├── B-v1
└── C-v1
```

A existência de `R2`:

```text
não invalida A-v1;
não invalida B-v1.
```

C-v1 inicia sua própria decisão.

---

## 17. Reabertura de item rejeitado

Usuário interno autorizado pode reabrir um item rejeitado.

Antes:

```text
A-v1
REJEITADO
```

Depois:

```text
A-v1
REJEITADO — histórico

A-v2
PENDENTE_APROVACAO
```

A rejeição anterior nunca é apagada.

---

## 18. Reabertura com mesmos termos

Mesmo quando:

```text
descrição;
quantidade;
preço
```

continuarem iguais, uma reabertura cria nova oportunidade de decisão.

Portanto deve existir:

```text
nova QuoteItemRevision.
```

---

# 19. DR-0001 — Obsolescência Comercial

A Decision Request:

```text
DR-0001
```

foi decidida pelo proprietário do produto como:

```text
OPÇÃO B
```

---

## 20. Nova versão em DRAFT

Dado:

```text
A-v1
PRESENTED
PENDENTE_APROVACAO
```

quando uma nova versão:

```text
A-v2
```

for criada somente dentro de:

```text
QuoteRevision DRAFT
```

então:

```text
A-v1 CONTINUA APTA A RECEBER DECISÃO
```

desde que as demais condições permaneçam válidas.

---

## 21. Justificativa da regra DRAFT

`DRAFT` representa:

```text
trabalho interno.
```

Uma condição que ainda não foi apresentada ao cliente não substitui a proposta que ele efetivamente recebeu.

Regra:

```text
DRAFT NÃO INVALIDA
A PROPOSTA APRESENTADA.
```

---

## 22. Nova versão PRESENTED

Quando uma nova versão comercial do:

```text
MESMO QuoteItem
```

for efetivamente:

```text
PRESENTED
```

ao cliente, a versão comercial anterior:

```text
DEIXA DE ACEITAR NOVAS DECISÕES.
```

---

## 23. Exemplo

Inicialmente:

```text
R1 PRESENTED

A-v1
R$ 500
PENDENTE
```

Gerente prepara:

```text
A-v2
R$ 600
DRAFT
```

Resultado:

```text
A-v1 ainda é decidível.
```

Depois:

```text
R2 PRESENTED
A-v2
R$ 600
```

Resultado:

```text
A-v1 deixa de aceitar nova decisão.

A-v2 passa a ser a nova condição comercial decidível.
```

---

## 24. Decisão anterior preservada

Se A-v1 já tiver recebido decisão antes da apresentação de A-v2:

```text
A-v1 APPROVED
```

ou:

```text
A-v1 REJECTED
```

essa decisão continua histórica.

A apresentação de A-v2:

```text
não apaga;

não altera;

não migra
```

a decisão de A-v1.

---

## 25. Aprovação não migra entre versões

É proibido concluir:

```text
A-v1 APPROVED
→
A-v2 APPROVED
```

automaticamente.

A-v2 representa nova condição comercial.

---

## 26. Obsolescência é por item

Uma nova:

```text
QuoteRevision
```

global não torna todos os itens anteriores automaticamente obsoletos.

A pergunta correta é:

```text
EXISTE UMA QuoteItemRevision POSTERIOR
DO MESMO QuoteItem
QUE FOI EFETIVAMENTE PRESENTED?
```

---

## 27. Complemento após DR-0001

Exemplo:

```text
R1
A-v1
```

Depois:

```text
R2
A-v1
B-v1
```

Mesmo que R2 seja `PRESENTED`:

```text
A-v1 não fica obsoleta
```

porque a mesma `QuoteItemRevision` foi reutilizada.

---

## 28. Token não supera regra comercial

Mesmo quando:

```text
token válido;
token não expirado;
token não revogado;
```

uma versão comercial já substituída:

```text
não pode receber nova decisão.
```

Regra:

```text
TOKEN VÁLIDO
≠
VERSÃO DECIDÍVEL
```

---

## 29. Validade comercial

Validade padrão:

```text
7 dias
```

a partir da apresentação, salvo configuração aprovada aplicável.

---

## 30. Efeito da expiração

A expiração bloqueia:

```text
novas decisões sobre itens ainda pendentes.
```

Não altera:

```text
aprovações anteriores;

rejeições anteriores;

histórico.
```

---

## 31. Aprovação existente após expiração

Exemplo:

```text
Dia 3:
A-v1 APPROVED

Dia 8:
revisão expirada
```

Resultado:

```text
A-v1 continua APPROVED.
```

---

## 32. Identificação do cliente

Para consolidar decisão pública devem ser informados:

```text
nome;

CPF ou CNPJ.
```

---

## 33. Aceite explícito

É obrigatório:

```text
explicitAcceptance = true
```

A simples abertura do link:

```text
não aprova;
não rejeita;
não cria decisão.
```

---

## 34. Evidências

A submissão deve preservar, no mínimo:

```text
Quote;

QuoteRevision;

QuoteItemRevision;

decisão;

nome declarado;

CPF/CNPJ declarado;

aceite explícito;

timestamp do servidor;

IP;

User-Agent.
```

---

## 35. Tempo

A autoridade temporal é:

```text
servidor.
```

Não confiar no horário informado pelo navegador.

---

## 36. Idempotência

A retransmissão da mesma operação não pode duplicar decisões.

Mesmo:

```text
requestId
+
mesmo conteúdo semântico
```

deve produzir:

```text
mesmo efeito funcional.
```

---

## 37. Reuso conflitante

Mesmo:

```text
requestId
```

com conteúdo diferente deve gerar:

```text
CONFLITO.
```

---

## 38. Atomicidade

Uma submissão com múltiplas decisões é atômica.

Exemplo:

```text
A APPROVE
B REJECT
```

Se B não puder ser consolidada:

```text
A também não deve ser consolidada
naquela submissão.
```

---

## 39. Uma decisão efetiva por versão

No escopo da TASK-0001:

```text
uma QuoteItemRevision
possui no máximo
uma decisão efetiva.
```

---

## 40. Retratação

Retratação pública não faz parte desta Task.

Portanto:

```text
APPROVED
→ REJECTED
```

ou:

```text
REJECTED
→ APPROVED
```

não são atualizações permitidas da mesma `QuoteItemRevision`.

Nova oportunidade exige nova versão quando prevista pelo processo.

---

## 41. Segurança

O cliente somente pode acessar:

```text
Quote;

QuoteRevision;

QuoteItemRevision
```

autorizados pelo acesso público correspondente.

---

## 42. IDOR

É obrigatório impedir:

```text
token do Quote A
→ Quote B
```

e:

```text
token da Revision R1
→ item que não pertence a R1.
```

---

## 43. Dados internos proibidos no acesso público

Não expor:

```text
custos;

margem;

lucro;

comissão;

salário;

fornecedor;

dados bancários;

anotações administrativas;

dados internos da oficina não necessários à decisão.
```

---

## 44. Concorrência crítica

Cenário:

```text
cliente tenta decidir A-v1

ao mesmo tempo em que

gerente apresenta A-v2.
```

O sistema deve produzir uma ordem coerente.

---

## 45. DECIDE vence

Se a decisão de A-v1 for consolidada primeiro:

```text
A-v1 recebe decisão válida.
```

Depois A-v2 pode ser apresentada.

A decisão de A-v1 permanece histórica.

---

## 46. PRESENT vence

Se A-v2 for apresentada primeiro:

```text
A-v1 torna-se não decidível.
```

A tentativa de registrar nova decisão em A-v1 deve ser rejeitada.

---

## 47. Regra de concorrência

Não pode existir resultado final no qual:

```text
A-v2 já havia se tornado a nova condição apresentada
```

e posteriormente:

```text
A-v1 recebeu uma nova decisão
```

sem detecção de conflito.

---

# 48. Critérios de Aceite

## CA-01 — Aprovação parcial

Dado:

```text
A
B
C
```

pendentes.

Quando:

```text
A e B forem aprovados.
```

Então:

```text
A = APROVADO
B = APROVADO
C = PENDENTE_APROVACAO
```

---

## CA-02 — Execução independente

Item aprovado pode seguir para execução permitida sem exigir decisão dos demais itens.

---

## CA-03 — Rejeição individual

Cliente pode rejeitar somente determinado item.

---

## CA-04 — Histórico

Nova revisão não apaga decisões anteriores.

---

## CA-05 — Alteração de preço

Alterar preço apresentado cria nova versão comercial pendente.

---

## CA-06 — Alteração interna

Alterar somente técnico não invalida decisão comercial.

---

## CA-07 — Expiração

Item pendente não aceita nova decisão após expiração da condição comercial aplicável.

---

## CA-08 — Aprovação sobrevive à expiração

Item aprovado anteriormente permanece aprovado.

---

## CA-09 — Revisão exata

Decisão somente afeta a versão comercial exata correspondente.

---

## CA-10 — Idempotência

Retry equivalente não duplica efeito.

---

## CA-11 — Reabertura

Reabertura preserva rejeição anterior e cria nova oportunidade.

---

## CA-12 — Complemento

Novo item pode ser incluído sem invalidar versões comerciais não alteradas.

---

## CA-13 — Identificação

Decisão exige:

```text
nome;
CPF/CNPJ;
aceite explícito.
```

---

## CA-14 — Token inválido

Token inválido não revela dados do orçamento.

---

## CA-15 — Evidências

Decisão consolidada registra evidências exigidas.

---

## CA-16 — DRAFT não invalida

Dado:

```text
A-v1 apresentada e pendente.
```

Quando:

```text
A-v2 for criada apenas em DRAFT.
```

Então:

```text
A-v1 continua decidível,
respeitadas as demais regras.
```

---

## CA-17 — PRESENTED substitui

Dado:

```text
A-v1 apresentada e pendente.
```

Quando:

```text
A-v2 for efetivamente PRESENTED.
```

Então:

```text
A-v1 deixa de aceitar nova decisão.
```

---

## CA-18 — Histórico após substituição

Se A-v1 já possuía decisão antes da apresentação de A-v2:

```text
essa decisão permanece registrada.
```

---

## CA-19 — Complemento com mesma versão

Dado:

```text
R1 contém A-v1.
```

Quando:

```text
R2 contém A-v1 + B-v1
```

e R2 for apresentada:

```text
A-v1 não fica stale apenas por existir R2.
```

---

## CA-20 — Token não supera stale

Dado:

```text
token válido
+
A-v1 substituída por A-v2 apresentada
```

quando houver tentativa de decidir A-v1:

```text
a decisão deve ser rejeitada.
```

---

## CA-21 — Concorrência

Na corrida:

```text
DECIDE A-v1
×
PRESENT A-v2
```

o sistema deve consolidar uma ordem coerente sem permitir commits contraditórios.

---

# 49. Regras Consolidadas

```text
RN-01
Decisão é individual por item.

RN-02
Ausência de decisão permanece pendente.

RN-03
Aprovação parcial é permitida.

RN-04
Mudança de preço exige nova versão.

RN-05
Mudança de descrição exige nova versão.

RN-06
Mudança de quantidade exige nova versão.

RN-07
Mudança exclusivamente interna não invalida decisão.

RN-08
Histórico não é sobrescrito.

RN-09
Complemento preserva versões não alteradas.

RN-10
Reabertura cria nova oportunidade.

RN-11
Decisão pertence à versão comercial exata.

RN-12
Expiração bloqueia novas decisões pendentes.

RN-13
Decisões anteriores sobrevivem à expiração.

RN-14
Cliente não precisa de conta interna.

RN-15
Decisão exige identificação e aceite.

RN-16
Submissão é idempotente.

RN-17
Submissão com múltiplas decisões é atômica.

RN-18
Uma versão possui no máximo uma decisão efetiva.

RN-19
Nova versão em DRAFT não invalida a anterior.

RN-20
Nova versão do mesmo item PRESENTED substitui
a anterior para novas decisões.

RN-21
Nova revisão global não invalida todos os itens anteriores.

RN-22
Complemento reutilizando mesma QuoteItemRevision
não invalida essa versão.

RN-23
Token válido não supera obsolescência comercial.

RN-24
Obsolescência nunca apaga decisão histórica.
```

---

# 50. Fora do Escopo

Não faz parte da especificação executável desta Task:

```text
implementação Java;

implementação React;

migration Flyway;

WhatsApp;

pagamento;

NFS-e;

comissão;

estoque completo;

compras;

assinatura digital certificada;

retratação pública.
```

---

# 51. Decision Requests

Decision Request relacionada:

```text
DR-0001
```

Status:

```text
DECIDED
```

Decisão:

```text
OPÇÃO B
```

Decision Requests bloqueadoras abertas:

```text
0
```

---

# 52. Resultado do AG-01

```text
TASK:
TASK-0001

REQUIREMENT:
REQ-ORC-001

REVISION:
2

STATUS:
APPROVED

PARTIAL APPROVAL:
APPROVED

COMMERCIAL VERSIONING:
APPROVED

COMPLEMENT:
APPROVED

REOPEN:
APPROVED

EXPIRATION:
APPROVED

PUBLIC ACCESS:
APPROVED

IDEMPOTENCY:
APPROVED

ATOMICITY:
APPROVED

DR-0001:
INCORPORATED

DRAFT INVALIDATES PREVIOUS:
NO

NEW PRESENTED ITEM VERSION INVALIDATES
PREVIOUS FOR NEW DECISIONS:
YES

BLOCKING DECISION REQUESTS:
0

READY:
YES
```

---

# 53. Regra Final

```text
DRAFT
NÃO SUBSTITUI
A PROPOSTA APRESENTADA.

NOVA VERSÃO COMERCIAL
DO MESMO ITEM
PRESENTED
SUBSTITUI A ANTERIOR
PARA NOVAS DECISÕES.

COMPLEMENTO COM A MESMA
QuoteItemRevision
NÃO INVALIDA ESSA VERSÃO.

DECISÕES HISTÓRICAS
NUNCA SÃO APAGADAS.
```

**A DECISÃO DO CLIENTE É VINCULADA À CONDIÇÃO COMERCIAL QUE ELE EFETIVAMENTE RECEBEU.**