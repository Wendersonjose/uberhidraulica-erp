# Domínio — Aprovação Parcial de Orçamento

## 1. Identificação

Documento:

```text
Aprovação Parcial de Orçamento
```

Task:

```text
TASK-0001
```

Requisito principal:

```text
REQ-ORC-001
```

Módulo proprietário:

```text
Oficina / Orçamento
```

Agente responsável:

```text
AG-03 — Domínio Oficina
```

Status:

```text
DOMAIN_APPROVED
```

Data:

```text
2026-09-05
```

---

# 2. Objetivo do domínio

Definir o modelo de domínio necessário para representar corretamente:

```text
orçamento;
revisões;
itens;
decisões individuais;
aprovação parcial;
rejeição;
pendência;
alteração comercial;
alteração interna;
complementos;
reabertura;
validade;
evidências;
histórico.
```

O modelo deve impedir que uma aprovação seja aplicada a condições comerciais diferentes daquelas efetivamente apresentadas ao cliente.

---

# 3. Linguagem ubíqua

Os seguintes termos devem possuir significado consistente no módulo Oficina:

```text
Ordem de Serviço
Orçamento
Revisão do Orçamento
Item do Orçamento
Revisão do Item
Decisão do Cliente
Aprovação
Rejeição
Pendência
Complemento
Reabertura
Validade
Acesso Público
Evidência de Decisão
```

---

# 4. Ordem de Serviço

A Ordem de Serviço:

```text
WorkOrder
```

existe antes do orçamento.

O orçamento pertence ao contexto de uma OS existente.

---

# 5. Orçamento

Nome conceitual:

```text
Quote
```

Responsabilidade:

representar o conjunto comercial de itens apresentados ao cliente dentro de uma OS.

---

# 6. Identidade do orçamento

Um orçamento possui identidade própria.

Alterações futuras não devem criar a impressão de que um novo orçamento independente nasceu quando, na realidade, ocorreu uma revisão do mesmo processo comercial.

---

# 7. Relação com a OS

Conceitualmente:

```text
WorkOrder
    1
    |
    | possui
    |
    0..N
    Quote
```

A cardinalidade definitiva será validada pelo AG-02.

---

# 8. Revisão do orçamento

Nome conceitual:

```text
QuoteRevision
```

Representa uma versão comercial apresentada ao cliente.

---

# 9. Responsabilidade da revisão

A revisão deve permitir reconstruir:

```text
o que estava sendo apresentado;
quando foi apresentada;
quais itens estavam presentes;
quais condições comerciais estavam vigentes;
qual era sua validade.
```

---

# 10. Revisão é histórica

Uma revisão não deve ser modificada retroativamente depois que se tornou evidência comercial relevante.

---

# 11. Nova revisão

Quando houver alteração comercial relevante, deve ser criada nova revisão.

Não sobrescrever a anterior.

---

# 12. Número da revisão

Conceitualmente, uma revisão pode possuir:

```text
1
2
3
4
...
```

ou outro identificador ordenável.

A estratégia técnica definitiva pertence ao AG-02/AG-10.

---

# 13. Item do orçamento

Nome conceitual:

```text
QuoteItem
```

Representa a identidade lógica de um item negociado dentro do orçamento.

---

# 14. Revisão do item

Nome conceitual:

```text
QuoteItemRevision
```

Representa o conteúdo comercial de um item em determinada revisão.

---

# 15. Por que separar item e revisão do item

É necessário distinguir:

```text
"este é o mesmo item comercial"
```

de:

```text
"esta é a versão específica desse item que o cliente viu"
```

Exemplo:

```text
QuoteItem:
Troca da caixa de direção

QuoteItemRevision 1:
R$ 1.500
APROVADA

QuoteItemRevision 2:
R$ 1.700
PENDENTE
```

---

# 16. Conteúdo comercial da revisão do item

Deve preservar pelo menos:

```text
descrição apresentada;
quantidade apresentada;
preço apresentado.
```

Outros campos comerciais podem ser adicionados futuramente mediante requisito.

---

# 17. Snapshot comercial

Os dados apresentados ao cliente devem funcionar como snapshot.

Alterações no catálogo não podem modificar retroativamente o conteúdo aprovado.

---

# 18. Catálogo não é histórico do orçamento

O orçamento pode ter origem em dados de catálogo.

Depois que a revisão é criada:

```text
dados da revisão
≠
referência dinâmica ao valor atual do catálogo
```

---

# 19. Estado funcional da decisão

Os estados funcionais mínimos são:

```text
PENDENTE_APROVACAO
APROVADO
REJEITADO
```

---

# 20. Estado pertence à versão apresentada

A decisão é referente a:

```text
QuoteItemRevision
```

e não apenas ao `QuoteItem` abstrato.

---

# 21. Pendência

Uma revisão de item sem decisão válida permanece:

```text
PENDENTE_APROVACAO
```

---

# 22. Aprovação

Uma decisão explícita do cliente pode produzir:

```text
APROVADO
```

---

# 23. Rejeição

Uma decisão explícita do cliente pode produzir:

```text
REJEITADO
```

---

# 24. Não decisão

Ausência de decisão não é rejeição.

Portanto:

```text
item omitido
→ continua PENDENTE_APROVACAO
```

---

# 25. Aprovação parcial

O domínio deve aceitar simultaneamente:

```text
Item A → APROVADO
Item B → REJEITADO
Item C → PENDENTE_APROVACAO
```

---

# 26. Proibição de estado global simplista

É inadequado representar toda decisão do orçamento apenas como:

```text
approved = true
```

ou:

```text
approved = false
```

---

# 27. Estado agregado do orçamento

Pode existir um estado derivado para visualização.

Exemplos conceituais possíveis:

```text
PENDING
PARTIALLY_DECIDED
FULLY_DECIDED
```

Mas esse estado:

```text
não substitui
```

os estados individuais dos itens.

A definição definitiva pertence ao AG-02 se necessária.

---

# 28. Decisão do cliente

Nome conceitual:

```text
QuoteDecision
```

Representa a manifestação registrada do cliente.

---

# 29. Decisão deve ser histórica

Não sobrescrever decisão anterior.

Uma decisão registrada deve continuar reconstruível.

---

# 30. Identificação da decisão

Uma decisão deve apontar, no mínimo, para:

```text
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
```

conforme a estrutura final do modelo.

---

# 31. Tipo da decisão

Valores mínimos:

```text
APPROVE
REJECT
```

Ausência de decisão não precisa gerar evento de rejeição.

---

# 32. Evidência da decisão

Nome conceitual:

```text
DecisionEvidence
```

Pode ser parte da própria decisão ou estrutura associada.

AG-02 definirá fronteira técnica.

---

# 33. Dados funcionais da evidência

Registrar:

```text
nome informado;
CPF ou CNPJ informado;
aceite explícito;
timestamp.
```

---

# 34. Dados técnicos da evidência

Registrar:

```text
IP;
user-agent;
identificador seguro do acesso público.
```

---

# 35. Conteúdo comercial associado

A evidência deve permitir identificar:

```text
descrição;
quantidade;
preço;
revisão.
```

Não é necessário duplicar fisicamente todos os dados caso a revisão seja imutável e referenciável.

Essa decisão é técnica.

---

# 36. Acesso público

Nome conceitual:

```text
PublicQuoteAccess
```

Responsabilidade:

controlar o acesso público do cliente à revisão do orçamento.

---

# 37. Acesso público não é usuário interno

`PublicQuoteAccess` não representa:

```text
User
```

do módulo IAM.

O cliente não possui conta interna no MVP.

---

# 38. Token

O segredo do token não pertence ao núcleo das regras de negócio.

O domínio precisa apenas da capacidade de saber se determinado acesso:

```text
é válido;
é referente à revisão correta;
está dentro da validade;
não foi revogado.
```

AG-09 e AG-02 definirão implementação.

---

# 39. Validade

A revisão apresentada possui validade.

Padrão aprovado:

```text
7 dias
```

---

# 40. Validade é snapshot

A data efetiva de validade deve ser preservada na revisão.

Alterar futuramente o padrão global não altera revisões antigas.

---

# 41. Expiração

A expiração atua sobre a capacidade de tomar nova decisão.

Não deve alterar retroativamente decisão já registrada.

---

# 42. Exemplo de expiração

```text
Revision:
validUntil = 10/09

Item A:
approvedAt = 07/09

Item B:
PENDENTE

Data atual:
11/09
```

Resultado:

```text
Item A continua APROVADO
Item B não pode ser aprovado usando essa revisão expirada
```

---

# 43. Invariante de expiração

```text
APPROVED_BEFORE_EXPIRATION
+
REVISION_EXPIRED
=
APPROVAL_REMAINS_VALID
```

---

# 44. Alteração comercial

São alterações comerciais aprovadas como relevantes:

```text
preço;
descrição;
quantidade.
```

---

# 45. Efeito da alteração comercial

Alterar qualquer um desses campos exige:

```text
nova QuoteItemRevision
```

e nova decisão.

---

# 46. Alteração comercial não apaga aprovação anterior

Exemplo:

```text
R1:
R$ 1.500
APROVADO

R2:
R$ 1.700
PENDENTE_APROVACAO
```

---

# 47. Proibição de mutação retroativa

É inválido fazer:

```text
R1:
R$ 1.500
APROVADO
```

virar:

```text
R1:
R$ 1.700
APROVADO
```

---

# 48. Alteração de preço

Produz nova revisão comercial.

---

# 49. Alteração de descrição

Produz nova revisão comercial.

---

# 50. Alteração de quantidade

Produz nova revisão comercial.

---

# 51. Alteração interna

São exemplos:

```text
técnico;
fornecedor;
custo;
referência interna;
informação operacional.
```

---

# 52. Efeito da alteração interna

Não cria automaticamente nova aprovação.

---

# 53. Separação comercial/interna

O modelo deve evitar que mudanças operacionais internas alterem o snapshot comercial.

---

# 54. Complemento

Novo serviço identificado posteriormente pode ser adicionado.

---

# 55. Regra de complemento

Adicionar novo item:

```text
não invalida
```

itens anteriormente aprovados sem alteração comercial.

---

# 56. Exemplo

Antes:

```text
A → APROVADO
B → APROVADO
```

Complemento:

```text
C → novo
```

Depois:

```text
A → APROVADO
B → APROVADO
C → PENDENTE_APROVACAO
```

---

# 57. Nova revisão com complemento

Uma nova revisão do orçamento pode conter:

```text
itens aprovados anteriormente sem alteração;
novos itens pendentes.
```

A arquitetura deve preservar o vínculo correto entre essas representações.

---

# 58. Aprovação não deve ser duplicada artificialmente

Se um item aprovado não sofreu alteração comercial:

não é necessário fingir que o cliente o aprovou novamente na revisão complementar.

Sua aprovação anterior continua sendo a fonte histórica da autorização.

---

# 59. Estado efetivo do item

O sistema poderá precisar calcular o estado efetivo do `QuoteItem` considerando suas revisões.

Conceitualmente:

```text
última revisão comercial aplicável
+
decisão válida correspondente
```

---

# 60. Regra do estado efetivo

Se a última revisão comercial exigir nova aprovação:

```text
estado efetivo = PENDENTE_APROVACAO
```

mesmo que uma revisão antiga tenha sido aprovada.

---

# 61. Item não alterado

Se não houve nova revisão comercial daquele item:

a aprovação anterior permanece efetiva.

---

# 62. Rejeição

Rejeição pertence à revisão específica apresentada.

---

# 63. Reabertura

Gerente autorizado pode reabrir item rejeitado.

---

# 64. Reabertura não altera a decisão antiga

A rejeição anterior permanece:

```text
REJEITADO
```

na revisão anterior.

---

# 65. Resultado da reabertura

Cria nova oportunidade comercial.

Conceitualmente:

```text
nova QuoteItemRevision
→ PENDENTE_APROVACAO
```

---

# 66. Reabertura com mesmo conteúdo

Mesmo que o gerente deseje reapresentar o mesmo preço/descrição/quantidade:

a nova tentativa deve permanecer distinguível da rejeição histórica.

---

# 67. Reabertura e autorização

A autorização interna para reabrir pertence ao mecanismo de permissão.

AG-09 participa.

---

# 68. Execução

A aprovação torna o item elegível para fluxo operacional.

---

# 69. Aprovação não significa execução

Os estados são diferentes:

```text
APROVADO
≠
EM_EXECUCAO
≠
CONCLUIDO
```

---

# 70. Estado comercial versus operacional

O domínio deve separar:

```text
estado de aprovação comercial
```

de:

```text
estado de execução do serviço
```

---

# 71. Exemplo

```text
QuoteItemRevision:
APROVADO

WorkOrderService:
NAO_INICIADO
```

é válido.

---

# 72. Serviço pendente não bloqueia aprovado

Um serviço aprovado pode iniciar mesmo que outro item permaneça:

```text
PENDENTE_APROVACAO
```

---

# 73. Item rejeitado não pode ser executado como aprovado

Para execução comercial normal:

deve existir autorização válida correspondente.

---

# 74. Relação com serviço da OS

Quando um item representa um serviço:

o vínculo com o serviço da OS deve ser explícito.

A cardinalidade definitiva será definida na arquitetura.

---

# 75. Aprovação e estoque

Nesta Task:

```text
QuoteItemApproved
```

pode futuramente ser consumido pelo módulo Estoque.

Mas reserva não faz parte da implementação simulada.

---

# 76. Aprovação e compras

Falta de estoque poderá futuramente produzir necessidade de compra.

Não pertence à responsabilidade do agregado de orçamento.

---

# 77. Aprovação e financeiro

Aprovação não cria automaticamente:

```text
pagamento;
recebimento;
conta a receber;
conciliação.
```

---

# 78. Aprovação e comissão

Aprovação não gera comissão.

---

# 79. Aprovação e fiscal

Aprovação não emite NFS-e.

---

# 80. Agregado principal proposto

Modelo conceitual inicial:

```text
Quote
├── QuoteRevision
├── QuoteItem
│   └── QuoteItemRevision
└── referência/histórico de decisões
```

A fronteira transacional exata será validada pelo AG-02.

---

# 81. Possível agregado de acesso público

Pode existir agregado separado:

```text
PublicQuoteAccess
```

porque:

```text
token;
expiração;
revogação;
tentativas;
segurança
```

possuem ciclo de vida diferente do orçamento.

Decisão final pertence ao AG-02.

---

# 82. Possível agregado de evidência

`QuoteDecision` pode ser:

```text
entidade interna do agregado Quote
```

ou:

```text
registro histórico separado
```

desde que as invariantes sejam preservadas.

AG-02/AG-10 decidem estrutura técnica.

---

# 83. Invariante I-01

Toda decisão deve pertencer a:

```text
uma revisão de item existente
```

---

# 84. Invariante I-02

A revisão do item deve pertencer ao orçamento/revisão apresentada.

---

# 85. Invariante I-03

Uma decisão não pode ser aplicada a revisão diferente daquela apresentada.

---

# 86. Invariante I-04

Aprovação de uma revisão não implica aprovação automática de revisão posterior alterada comercialmente.

---

# 87. Invariante I-05

Alteração de:

```text
preço
descrição
quantidade
```

exige nova aprovação.

---

# 88. Invariante I-06

Alteração exclusivamente interna não invalida decisão comercial existente.

---

# 89. Invariante I-07

Revisão expirada não aceita nova decisão válida para item pendente.

---

# 90. Invariante I-08

Decisão aprovada antes da expiração continua válida após a expiração.

---

# 91. Invariante I-09

Item omitido de uma submissão continua pendente.

---

# 92. Invariante I-10

Aprovação parcial é válida.

---

# 93. Invariante I-11

Item aprovado pode seguir para execução independentemente dos demais.

---

# 94. Invariante I-12

Item rejeitado não pode ser tratado como aprovado.

---

# 95. Invariante I-13

Rejeição histórica não desaparece após reabertura.

---

# 96. Invariante I-14

Aprovação histórica não desaparece após criação de complemento.

---

# 97. Invariante I-15

Solicitação duplicada não gera decisão operacional duplicada.

---

# 98. Invariante I-16

Decisão deve possuir evidência mínima obrigatória.

---

# 99. Invariante I-17

A mera visualização do orçamento não gera aprovação.

---

# 100. Invariante I-18

Uma ação pública não pode acessar orçamento diferente do autorizado pelo token.

---

# 101. Comando conceitual — criar orçamento

```text
CreateQuote
```

Responsável:

usuário interno autorizado.

---

# 102. Comando conceitual — criar revisão

```text
CreateQuoteRevision
```

---

# 103. Comando conceitual — enviar/apresentar revisão

```text
PresentQuoteRevision
```

Pode originar acesso público.

---

# 104. Comando conceitual — registrar decisões

```text
RegisterQuoteDecisions
```

Pode conter múltiplas decisões em uma única submissão.

---

# 105. Conteúdo conceitual do comando

```text
publicAccessReference
quoteRevisionReference
customerIdentification
explicitAcceptance
decisions[]
```

---

# 106. Decisões da submissão

Exemplo:

```text
decisions:
- item A → APPROVE
- item B → REJECT
```

Item C ausente:

```text
continua PENDENTE
```

---

# 107. Validações antes da decisão

Antes de registrar:

```text
acesso público válido;
revisão existente;
revisão correta;
validade;
item existente;
item pertence à revisão;
estado permite decisão;
identificação presente;
aceite explícito presente.
```

---

# 108. Comando conceitual — reabrir rejeitado

```text
ReopenRejectedQuoteItem
```

Usuário:

```text
interno autorizado
```

Resultado:

```text
nova oportunidade/revisão pendente
```

---

# 109. Comando conceitual — alterar item comercial

```text
ReviseQuoteItemCommercialTerms
```

Se alterar:

```text
preço
descrição
quantidade
```

produz nova revisão pendente.

---

# 110. Comando conceitual — alterar informação interna

Não deve utilizar operação que altere a revisão comercial.

Deve pertencer ao objeto operacional correspondente.

---

# 111. Evento — orçamento criado

```text
QuoteCreated
```

---

# 112. Evento — revisão criada

```text
QuoteRevisionCreated
```

---

# 113. Evento — orçamento enviado/apresentado

```text
QuoteSent
```

ou evento equivalente definido por AG-02.

---

# 114. Evento — item aprovado

```text
QuoteItemApproved
```

Payload conceitual mínimo:

```text
quoteId
quoteRevisionId
quoteItemId
quoteItemRevisionId
decisionId
occurredAt
```

---

# 115. Evento — item rejeitado

```text
QuoteItemRejected
```

---

# 116. Evento — item revisado

```text
QuoteItemRevised
```

---

# 117. Evento — item reaberto

```text
QuoteItemReopened
```

---

# 118. Evento — revisão expirada

Pode existir:

```text
QuoteRevisionExpired
```

caso exista necessidade de reação explícita.

Não é obrigatória uma mutação persistida apenas porque o tempo passou.

AG-02 decide.

---

# 119. Tempo como regra

A validade pode ser calculada através de:

```text
currentTime > validUntil
```

sem obrigatoriamente executar job para mudar status.

---

# 120. Clock testável

Implementação futura deve evitar dependência direta e incontrolável de:

```text
now()
```

espalhada pelo domínio.

AG-11 deve permitir clock testável.

---

# 121. Concorrência — revisão criada durante aprovação

Cenário:

```text
cliente abriu revisão 2
gerente cria revisão 3
cliente envia decisão da revisão 2
```

O sistema não pode aplicar automaticamente a decisão à revisão 3.

---

# 122. Tratamento conceitual

A decisão deve ser validada contra:

```text
revisionId esperado
```

e demais condições de vigência.

---

# 123. Concorrência — dois cliques

Cenário:

```text
requisição A → APPROVE
requisição B → APPROVE
```

simultâneas.

Resultado:

```text
uma decisão efetiva
```

---

# 124. Concorrência — decisões conflitantes

Cenário:

```text
requisição A → APPROVE
requisição B → REJECT
```

para a mesma revisão/item quase simultaneamente.

Esse conflito deve ser impedido por regra de consistência.

A primeira decisão válida efetivamente consolidada deve impedir alteração silenciosa pela segunda.

A estratégia técnica pertence ao AG-02/AG-10.

---

# 125. Decisão posterior diferente

Mudar uma decisão já consolidada não deve acontecer pela mesma operação pública como simples overwrite.

Caso o negócio futuramente permita retratação:

deverá existir requisito específico.

---

# 126. Idempotência

A idempotência deve distinguir:

```text
repetição da mesma operação
```

de:

```text
nova decisão legítima
```

---

# 127. Chave de idempotência

A estratégia exata pode utilizar:

```text
request identifier;
decision identifier;
constraint funcional;
ou combinação.
```

AG-02/AG-10 definem.

---

# 128. Histórico

O domínio deve permitir responder:

```text
Qual revisão foi apresentada?
Qual item?
Qual preço?
Qual descrição?
Qual quantidade?
Qual decisão?
Quem declarou?
Quando?
De qual acesso?
```

---

# 129. Histórico não é somente auditoria técnica

As decisões fazem parte do próprio domínio.

Não podem existir apenas em logs.

---

# 130. Auditoria complementar

Além da entidade de decisão, AG-09 pode exigir registro de auditoria transversal.

---

# 131. CPF/CNPJ

O domínio exige que uma identificação seja fornecida conforme o requisito.

A definição técnica de:

```text
normalização;
máscara;
validação de dígito;
armazenamento;
proteção;
```

será detalhada por AG-09/AG-11 conforme requisitos aplicáveis.

---

# 132. Nome informado

O nome registrado na decisão representa a declaração feita naquele momento.

Não deve ser substituído retroativamente caso o cadastro do cliente mude.

---

# 133. IP

IP pertence à evidência técnica da decisão.

---

# 134. User-Agent

User-Agent pertence à evidência técnica da decisão.

---

# 135. Aceite explícito

Deve existir valor inequívoco indicando confirmação.

Exemplo conceitual:

```text
explicitAcceptance = true
```

Sem aceite:

```text
nenhuma decisão é consolidada
```

---

# 136. Transação da submissão

Quando o cliente envia múltiplas decisões em uma mesma confirmação, a política de atomicidade deve evitar estado incoerente.

Recomendação de domínio para esta Task:

```text
validar toda a submissão antes de consolidar seus efeitos.
```

A estratégia transacional final pertence ao AG-02.

---

# 137. Submissão com item inválido

Exemplo:

```text
A válido
B não pertence à revisão
```

Não deve ocorrer aceitação silenciosa de B.

O tratamento integral da requisição será definido na arquitetura/API.

---

# 138. Não confiar no payload do frontend

Preço, descrição e quantidade aprovados não devem ser aceitos do browser como fonte de verdade.

O backend utiliza a revisão persistida.

---

# 139. Frontend público

O frontend apenas expressa a intenção:

```text
APPROVE
REJECT
```

para identificadores autorizados.

---

# 140. Domínio como fonte de verdade

A validade da transição é determinada no backend/domínio.

---

# 141. Value Object proposto — Money

Valores monetários devem utilizar representação exata.

No Java:

```text
BigDecimal
```

Detalhamento pelo AG-11.

---

# 142. Value Object proposto — Quantity

A quantidade comercial deve possuir semântica explícita.

Para esta Task, apenas é necessário preservar o valor apresentado.

---

# 143. Value Object proposto — QuoteValidity

Pode representar:

```text
presentedAt
validUntil
```

ou apenas validade final conforme desenho arquitetural.

---

# 144. Value Object proposto — CustomerDecisionIdentity

Pode agrupar:

```text
name
documentType
documentNumber
```

AG-09 deve revisar impacto de dados pessoais.

---

# 145. Value Object proposto — CommercialSnapshot

Pode agrupar:

```text
description
quantity
unitPrice
totalPrice
```

desde que não introduza regra comercial não aprovada.

---

# 146. Total do item

Se houver:

```text
quantidade × preço
```

o cálculo deve ser consistente.

A definição de preço unitário versus preço final dependerá do modelo comercial aprovado.

Nesta Task, o essencial é preservar o preço que foi apresentado.

---

# 147. Desconto

Desconto não é regra central desta simulação.

Caso o desconto altere o valor apresentado ao cliente:

o snapshot comercial final deve refletir esse valor.

---

# 148. Dependência de Workflow

A aprovação pode futuramente causar mudança automática de status da OS.

Não implementar como responsabilidade direta desta Task.

---

# 149. Contrato público conceitual

O módulo Oficina deve disponibilizar capacidade equivalente a:

```text
getPublicQuoteRevision(accessToken)
```

e:

```text
registerPublicQuoteDecisions(...)
```

Os contratos reais serão definidos pelo AG-02/AG-11.

---

# 150. Contrato interno conceitual

Outros módulos podem receber fatos, por exemplo:

```text
QuoteItemApproved
```

em vez de consultar tabelas do módulo Oficina.

---

# 151. Proibição de acesso direto externo

Estoque não deve fazer:

```text
SELECT quote_item ...
```

diretamente nas tabelas de Oficina.

---

# 152. Reserva futura

Fluxo futuro:

```text
QuoteItemApproved
↓
Estoque avalia peças associadas
↓
reserva ou necessidade de compra
```

Fora do escopo da implementação atual.

---

# 153. Casos de domínio obrigatórios

Devem ser testáveis:

```text
aprovação de um item;
rejeição de um item;
aprovação parcial;
item omitido;
expiração;
aprovação anterior à expiração;
alteração de preço;
alteração de descrição;
alteração de quantidade;
alteração interna;
complemento;
reabertura;
revisão obsoleta;
duplicidade;
concorrência.
```

---

# 154. Caso D-01 — Aprovação individual

Dado:

```text
Item A = PENDENTE_APROVACAO
```

Quando:

```text
cliente aprova A
```

Então:

```text
A = APROVADO
```

---

# 155. Caso D-02 — Aprovação parcial

Dado:

```text
A = PENDENTE
B = PENDENTE
C = PENDENTE
```

Quando:

```text
A = APPROVE
B = APPROVE
C = omitido
```

Então:

```text
A = APROVADO
B = APROVADO
C = PENDENTE
```

---

# 156. Caso D-03 — Rejeição

Quando:

```text
A = REJECT
```

Então:

```text
A = REJEITADO
```

---

# 157. Caso D-04 — Alteração de preço

Antes:

```text
R1
R$ 350
APROVADO
```

Alteração:

```text
R$ 400
```

Depois:

```text
R1:
R$ 350
APROVADO

R2:
R$ 400
PENDENTE_APROVACAO
```

---

# 158. Caso D-05 — Alteração interna

Antes:

```text
A = APROVADO
Técnico = João
```

Alteração:

```text
Técnico = Pedro
```

Depois:

```text
A = APROVADO
```

---

# 159. Caso D-06 — Expiração

Antes:

```text
A aprovado dentro da validade
B pendente
```

Depois da validade:

```text
A = APROVADO
B não aceita nova decisão nessa revisão
```

---

# 160. Caso D-07 — Complemento

Antes:

```text
A = APROVADO
```

Novo item:

```text
B
```

Depois:

```text
A = APROVADO
B = PENDENTE
```

---

# 161. Caso D-08 — Reabertura

Antes:

```text
R1
A = REJEITADO
```

Após gerente reabrir:

```text
R1:
A = REJEITADO

R2:
A = PENDENTE
```

---

# 162. Caso D-09 — Link inválido

Resultado:

```text
nenhum dado do orçamento retornado
nenhuma decisão registrada
```

---

# 163. Caso D-10 — Revisão obsoleta

Dado:

```text
cliente possui acesso à revisão 1
revisão 2 comercialmente substitui item
```

Quando:

```text
cliente tenta aprovar revisão 1
```

Então:

```text
não aplicar decisão à revisão 2
```

A aceitação ou rejeição absoluta da decisão sobre R1 depende do estado de vigência definido pela arquitetura.

A regra obrigatória é:

```text
nunca migrar a decisão para R2.
```

---

# 164. Caso D-11 — Requisição duplicada

Duas requisições equivalentes:

```text
APPROVE A
APPROVE A
```

Resultado:

```text
uma aprovação efetiva
```

---

# 165. Caso D-12 — Conflito concorrente

Duas requisições:

```text
APPROVE A
REJECT A
```

Resultado:

```text
não podem ambas se tornar decisões efetivas concorrentes
```

---

# 166. Erros de domínio conceituais

Exemplos:

```text
QUOTE_NOT_FOUND
QUOTE_REVISION_NOT_FOUND
QUOTE_REVISION_EXPIRED
QUOTE_REVISION_STALE
QUOTE_ITEM_NOT_FOUND
QUOTE_ITEM_NOT_IN_REVISION
QUOTE_ITEM_ALREADY_DECIDED
INVALID_QUOTE_DECISION
PUBLIC_ACCESS_INVALID
PUBLIC_ACCESS_EXPIRED
CUSTOMER_IDENTIFICATION_REQUIRED
EXPLICIT_ACCEPTANCE_REQUIRED
```

Nomes finais pertencem ao AG-11.

---

# 167. Decisão antiga não é erro histórico

Uma decisão antiga continua sendo dado válido de histórico.

Pode não ser mais aplicável operacionalmente à última revisão.

---

# 168. Estado atual versus histórico

O domínio deve conseguir responder duas perguntas diferentes:

```text
1. Qual foi a decisão da revisão X?

2. Qual é a situação comercial efetiva atual do item?
```

Não confundir.

---

# 169. Consulta de histórico

Deve permitir visualizar a linha do tempo:

```text
R1 criada
R1 enviada
A aprovado
B rejeitado
R2 criada
B reaberto
B aprovado
```

---

# 170. Consulta do estado atual

Pode retornar:

```text
A → APROVADO
B → APROVADO
C → PENDENTE
```

com referência às decisões que justificam cada estado.

---

# 171. Exclusão

Não excluir fisicamente decisões como fluxo normal.

---

# 172. Cancelamento de orçamento

O comportamento completo de cancelamento de orçamento não faz parte desta Task.

Não inventar.

---

# 173. Retratação do cliente

Não está definida neste requisito.

Não permitir implicitamente alteração de:

```text
APROVADO → REJEITADO
```

ou:

```text
REJEITADO → APROVADO
```

na mesma revisão por simples overwrite.

Se necessário futuramente:

```text
Decision Request / novo requisito
```

---

# 174. Renovação após expiração

Sabemos que item pendente não pode ser aprovado em revisão expirada sem renovação válida.

A mecânica exata de renovação pode ser definida em Task futura caso necessária.

Não impede esta modelagem.

---

# 175. Invariante sobre renovação

Qualquer renovação futura deve:

```text
preservar histórico da validade anterior
```

e não alterar retroativamente a data original.

---

# 176. Segurança

AG-09 deve analisar:

```text
token;
hash do token;
expiração;
revogação;
escopo;
tentativas;
rate limiting;
proteção de CPF/CNPJ;
IP;
user-agent;
logs;
CSRF;
enumeration.
```

---

# 177. Persistência

AG-10 deve propor modelo capaz de preservar:

```text
quote
quote_revision
quote_item
quote_item_revision
quote_decision
public_quote_access
```

Os nomes são conceituais.

---

# 178. Constraints esperadas

AG-10 deve avaliar constraints para impedir:

```text
revisão duplicada;
item fora do orçamento;
revisão do item fora da revisão;
decisão duplicada;
uso indevido de token;
concorrência inconsistente.
```

---

# 179. Precisão monetária

Qualquer preço deve utilizar precisão decimal.

---

# 180. Timestamps

Decisões devem utilizar timestamp consistente e adequado para auditoria.

---

# 181. Eventos de integração

Eventos publicados devem ser tratados como contratos públicos do módulo.

Mudanças incompatíveis exigem versionamento ou coordenação.

---

# 182. Evento de aprovação e idempotência de consumidores

Consumidores futuros também devem tolerar reentrega de evento.

Exemplo:

```text
QuoteItemApproved
```

não pode causar duas reservas da mesma peça.

A regra específica será tratada nas respectivas Tasks.

---

# 183. Outbox

Se evento precisar disparar efeitos que devem sobreviver a falhas de processo:

AG-02 poderá exigir outbox.

Arquitetura geral do projeto já prevê transactional outbox.

---

# 184. Não há mensageria externa no MVP

Não exigir:

```text
Kafka
RabbitMQ
```

para esta Task.

---

# 185. Responsabilidade do AG-03

AG-03 define:

```text
semântica;
invariantes;
estados;
transições;
eventos de domínio;
fronteiras conceituais.
```

---

# 186. Responsabilidade do AG-02

AG-02 definirá:

```text
aggregate boundaries definitivas;
módulos;
ports;
contratos;
transações;
concorrência;
idempotência técnica;
outbox;
API;
```

---

# 187. Responsabilidade do AG-09

AG-09 definirá:

```text
proteção do link;
token;
dados sensíveis;
autorização;
auditoria;
ameaças.
```

---

# 188. Responsabilidade do AG-10

AG-10 definirá:

```text
tabelas;
FKs;
constraints;
índices;
locking;
migrations.
```

---

# 189. Responsabilidade do AG-11

AG-11 definirá posteriormente:

```text
casos de uso;
DTOs;
controllers;
domain objects;
repositories;
erros;
transações.
```

Nesta simulação:

```text
sem código de produção.
```

---

# 190. Responsabilidade do AG-12

AG-12 definirá:

```text
fluxo público;
fluxo interno;
estados de interface;
tratamento de erro.
```

Nesta simulação:

```text
sem código de produção.
```

---

# 191. Responsabilidade do AG-13

AG-13 validará os cenários derivados dos critérios de aceite e invariantes.

---

# 192. Invariantes consolidadas

```text
1. decisão é por item/revisão.

2. aprovação parcial é válida.

3. item omitido permanece pendente.

4. item aprovado pode seguir independentemente.

5. rejeitado não é aprovado.

6. decisão histórica não é sobrescrita.

7. preço alterado exige nova aprovação.

8. descrição alterada exige nova aprovação.

9. quantidade alterada exige nova aprovação.

10. alteração interna não invalida aprovação.

11. complemento não invalida item aprovado sem alteração.

12. rejeição reaberta permanece no histórico.

13. revisão expirada não aceita nova decisão pendente.

14. aprovação feita dentro da validade continua válida.

15. revisão antiga nunca aprova silenciosamente revisão nova.

16. requisição repetida não produz efeito duplicado.

17. decisões concorrentes incompatíveis não podem ambas prevalecer.

18. visualização não equivale a aceite.

19. aceite explícito é obrigatório.

20. dados comerciais usados na aprovação vêm da revisão persistida.
```

---

# 193. Eventos consolidados

```text
QuoteCreated
QuoteRevisionCreated
QuoteSent
QuoteItemApproved
QuoteItemRejected
QuoteItemRevised
QuoteItemReopened
```

Evento opcional dependente da arquitetura:

```text
QuoteRevisionExpired
```

---

# 194. Entidades conceituais consolidadas

```text
WorkOrder
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
QuoteDecision
PublicQuoteAccess
```

---

# 195. Value Objects conceituais

```text
Money
CustomerDecisionIdentity
QuoteValidity
CommercialSnapshot
```

Outros poderão ser adicionados se necessários tecnicamente.

---

# 196. Handoff AG-03 → AG-02

```text
Task:
TASK-0001

Requirement:
REQ-ORC-001

Status:
DOMAIN_APPROVED

Módulo proprietário:
Oficina / Orçamento

Entidades:
- Quote
- QuoteRevision
- QuoteItem
- QuoteItemRevision
- QuoteDecision
- PublicQuoteAccess

Value Objects sugeridos:
- Money
- CustomerDecisionIdentity
- QuoteValidity
- CommercialSnapshot

Estados comerciais mínimos:
- PENDENTE_APROVACAO
- APROVADO
- REJEITADO

Invariantes principais:
- decisão vinculada à revisão específica;
- aprovação parcial permitida;
- não decisão permanece pendente;
- alteração comercial cria nova revisão;
- alteração interna não invalida;
- histórico imutável;
- expiração não desfaz aprovação anterior;
- revisão antiga não autoriza versão nova;
- operação duplicada não cria efeito duplicado;
- decisões concorrentes incompatíveis não podem ambas prevalecer.

Eventos:
- QuoteCreated
- QuoteRevisionCreated
- QuoteSent
- QuoteItemApproved
- QuoteItemRejected
- QuoteItemRevised
- QuoteItemReopened

Concorrência crítica:
- criação de revisão simultânea à aprovação;
- duplo clique;
- approve/reject concorrentes.

Segurança:
AG-09 obrigatório devido a:
- link público;
- token;
- CPF/CNPJ;
- IP;
- user-agent;
- evidência.

Persistência:
AG-10 obrigatório.

Decision Requests impeditivas:
NENHUMA

Resultado esperado do AG-02:
- definir agregados;
- definir contratos públicos do módulo;
- definir transações;
- definir estratégia de concorrência;
- definir idempotência;
- definir arquitetura do acesso público;
- definir uso de outbox;
- definir contratos REST conceituais.
```

---

# 197. Resultado do AG-03

```text
TASK:
TASK-0001

STATUS:
DOMAIN_APPROVED

MÓDULO:
OFICINA / ORÇAMENTO

ENTIDADES:
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
QuoteDecision
PublicQuoteAccess

ESTADOS:
PENDENTE_APROVACAO
APROVADO
REJEITADO

INVARIANTES:
DEFINIDAS

EVENTOS:
DEFINIDOS

CONTRATOS:
CONCEITUAIS

HISTÓRICO:
OBRIGATÓRIO

CONCORRÊNCIA:
IDENTIFICADA

SEGURANÇA:
AG-09 OBRIGATÓRIO

DECISION REQUESTS:
NENHUMA

PRONTO PARA AG-02:
SIM
```

---

# 198. Definition of Done do AG-03

- [x] requisito analisado;
- [x] linguagem ubíqua definida;
- [x] entidades conceituais definidas;
- [x] revisões modeladas;
- [x] decisão individual modelada;
- [x] estados definidos;
- [x] transições definidas;
- [x] alteração comercial definida;
- [x] alteração interna definida;
- [x] complemento definido;
- [x] reabertura definida;
- [x] validade definida;
- [x] evidências identificadas;
- [x] invariantes definidas;
- [x] eventos propostos;
- [x] concorrência identificada;
- [x] segurança identificada;
- [x] nenhuma regra de produto inventada;
- [x] nenhuma Decision Request impeditiva.

---

# 199. Regra final do domínio

O domínio nunca deve responder apenas:

```text
"este orçamento está aprovado"
```

quando a realidade é:

```text
alguns itens foram aprovados,
outros rejeitados,
outros continuam pendentes.
```

E nunca deve atribuir ao cliente uma aprovação sobre condições comerciais que ele não visualizou.

**A DECISÃO É HISTÓRICA, INDIVIDUAL, EXPLÍCITA E VINCULADA À REVISÃO EXATA APRESENTADA.**