# Arquitetura — TASK-0001 — Aprovação Parcial de Orçamento

## 1. Identificação

Task:

```text
TASK-0001
```

Requisito:

```text
REQ-ORC-001
```

Documento de domínio:

```text
docs/domain/oficina/aprovacao-parcial-orcamento.md
```

Módulo proprietário:

```text
Oficina / Orçamento
```

Agente responsável:

```text
AG-02 — Arquitetura
```

Status:

```text
ARCHITECTURE_APPROVED
```

Data:

```text
2026-09-05
```

---

# 2. Objetivo

Definir a arquitetura técnica da aprovação parcial de orçamento sem implementar código de produção.

Este documento define:

```text
fronteira modular;
agregados;
responsabilidades;
transações;
concorrência;
idempotência;
eventos;
outbox;
contratos públicos;
API REST conceitual;
integração com segurança;
integração com persistência;
integração futura com outros módulos.
```

---

# 3. Restrições arquiteturais do projeto

A implementação deve respeitar:

```text
Java
Spring Boot
Spring Modulith
PostgreSQL
REST
Spring Security
Flyway
Transactional Outbox
Modular Monolith
```

Não utilizar nesta Task:

```text
microservices;
Kafka;
RabbitMQ;
banco independente por módulo;
repository compartilhado entre módulos.
```

---

# 4. Módulo proprietário

A feature pertence ao módulo:

```text
workshop
```

ou equivalente definitivo do módulo Oficina.

Subdomínio:

```text
quote
```

Estrutura conceitual:

```text
workshop
└── quote
```

---

# 5. Regra de propriedade

O módulo Oficina é proprietário dos dados de:

```text
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
QuoteDecision
```

Outros módulos não podem consultar diretamente suas tabelas ou repositories.

---

# 6. Acesso público

O controle de acesso público pode ser tecnicamente implementado dentro da funcionalidade de orçamento, com regras de segurança revisadas pelo AG-09.

Conceito:

```text
PublicQuoteAccess
```

pertence ao ciclo de apresentação pública do orçamento.

---

# 7. Separação IAM

`PublicQuoteAccess` não pertence ao agregado de usuários internos do IAM.

Cliente externo:

```text
não é User interno.
```

Portanto:

```text
PublicQuoteAccess
≠
User
≠
Session de funcionário
```

---

# 8. Agregado principal

Agregado proposto:

```text
Quote
```

Root:

```text
Quote
```

Responsável por coordenar:

```text
identidade do orçamento;
itens;
revisões comerciais;
estado comercial efetivo;
criação de nova revisão;
reabertura de item;
regras de alteração comercial.
```

---

# 9. Entidades do agregado Quote

Conceitualmente:

```text
Quote
├── QuoteRevision
├── QuoteItem
└── QuoteItemRevision
```

A persistência poderá utilizar tabelas independentes, sem significar agregados independentes.

---

# 10. QuoteDecision

Arquiteturalmente, `QuoteDecision` será tratado como registro histórico associado ao agregado de orçamento.

Pode possuir repository técnico próprio por necessidade de consulta, mas não constitui domínio independente.

---

# 11. Motivo

A decisão precisa preservar:

```text
histórico;
imutabilidade;
evidência;
idempotência;
auditoria;
consultas temporais.
```

Não deve ser simplesmente um campo mutável em `QuoteItem`.

---

# 12. PublicQuoteAccess

Agregado técnico separado proposto:

```text
PublicQuoteAccess
```

Responsabilidade:

```text
token;
escopo;
revisão autorizada;
validade;
revogação;
estado do acesso.
```

---

# 13. Justificativa da separação

O ciclo de vida de:

```text
token
```

é diferente do ciclo de vida de:

```text
Quote
```

Exemplo:

```text
mesmo orçamento
→ pode ter acesso antigo revogado
→ novo acesso gerado
→ orçamento permanece o mesmo
```

---

# 14. Dependência entre agregados

`PublicQuoteAccess` referencia:

```text
QuoteId
QuoteRevisionId
```

por identificadores.

Não deve carregar internamente o agregado inteiro.

---

# 15. Regra sobre token

O token bruto recebido pelo cliente não deve ser utilizado como identificador de domínio persistido em texto recuperável.

A estratégia final de segurança será validada pelo AG-09.

---

# 16. Modelo arquitetural conceitual

```text
                    WORKSHOP MODULE

┌──────────────────────────────────────────────┐
│                                              │
│                  Quote                       │
│                    │                         │
│       ┌────────────┼─────────────┐           │
│       │            │             │           │
│ QuoteRevision   QuoteItem   QuoteDecision    │
│                      │                       │
│              QuoteItemRevision               │
│                                              │
└──────────────────────────────────────────────┘

                    ↑
                    │ identifiers
                    │
┌──────────────────────────────────────────────┐
│           PublicQuoteAccess                  │
│                                              │
│ quoteId                                      │
│ quoteRevisionId                              │
│ tokenDigest                                  │
│ validUntil                                   │
│ revokedAt                                    │
└──────────────────────────────────────────────┘
```

---

# 17. Arquitetura em camadas

Fluxo obrigatório:

```text
HTTP Controller
↓
Application Use Case
↓
Domain
↓
Repository Port
↓
Persistence Adapter
```

---

# 18. Controllers

Controllers não devem:

```text
decidir se revisão está expirada;
calcular aprovação;
alterar estado diretamente;
consultar EntityManager diretamente;
emitir eventos manualmente fora do caso de uso.
```

---

# 19. Application Layer

Responsável por:

```text
carregar agregados;
coordenar transação;
validar referências externas;
invocar regras de domínio;
persistir alterações;
registrar outbox;
retornar resultado.
```

---

# 20. Domain Layer

Responsável por:

```text
estados;
transições;
invariantes;
nova revisão comercial;
reabertura;
decisão válida;
expiração funcional;
regras de aprovação.
```

---

# 21. Persistence Adapter

Responsável por:

```text
JPA;
queries;
locking;
mapeamento;
persistência;
constraints.
```

---

# 22. Entrada pública

Fluxo arquitetural de consulta:

```text
GET público
↓
PublicQuoteController
↓
GetPublicQuoteRevisionUseCase
↓
validar acesso
↓
carregar revisão autorizada
↓
projetar DTO público
```

---

# 23. Entrada de decisão

Fluxo:

```text
POST público
↓
PublicQuoteDecisionController
↓
RegisterPublicQuoteDecisionsUseCase
↓
validar acesso
↓
carregar revisão
↓
validar vigência
↓
carregar estado necessário
↓
aplicar decisões
↓
persistir
↓
registrar eventos/outbox
↓
commit
```

---

# 24. Ordem das validações

A ordem conceitual deve minimizar exposição indevida.

Primeiro:

```text
token/acesso público
```

Depois:

```text
escopo do acesso
```

Depois:

```text
revisão
```

Depois:

```text
regras de negócio
```

---

# 25. API pública não expõe IDs arbitrários

A API pública não deve permitir consultar orçamento apenas fornecendo:

```text
quoteId
```

ou:

```text
workOrderId
```

sem autorização derivada do acesso público.

---

# 26. Identificador público

Acesso será derivado do token.

Exemplo conceitual:

```text
/public/quotes/{token}
```

A URI final poderá ser refinada pelo AG-11.

---

# 27. GET público conceitual

```text
GET /api/public/quotes/{token}
```

Responsabilidade:

```text
resolver acesso;
validar token;
validar escopo;
retornar revisão autorizada.
```

---

# 28. Resposta pública conceitual

Exemplo:

```json
{
  "quoteReference": "public-reference",
  "revision": 2,
  "presentedAt": "2026-09-05T14:00:00-03:00",
  "validUntil": "2026-09-12T23:59:59-03:00",
  "status": "AVAILABLE",
  "customer": {
    "displayName": "Cliente"
  },
  "items": [
    {
      "itemReference": "public-item-reference-1",
      "description": "Serviço X",
      "quantity": 1,
      "price": "500.00",
      "decisionStatus": "PENDING_APPROVAL"
    }
  ]
}
```

Estrutura final pertence ao AG-11.

---

# 29. Não expor dados desnecessários

A API pública não deve retornar automaticamente:

```text
custos;
comissões;
fornecedores;
margens;
informações internas;
dados financeiros administrativos;
dados de outros clientes.
```

---

# 30. POST de decisão conceitual

```text
POST /api/public/quotes/{token}/decisions
```

---

# 31. Request conceitual

```json
{
  "revisionReference": "revision-reference",
  "customer": {
    "name": "Nome informado",
    "documentType": "CPF",
    "documentNumber": "00000000000"
  },
  "explicitAcceptance": true,
  "requestId": "client-generated-idempotency-key",
  "decisions": [
    {
      "itemReference": "item-reference-1",
      "decision": "APPROVE"
    },
    {
      "itemReference": "item-reference-2",
      "decision": "REJECT"
    }
  ]
}
```

Não é contrato final.

É contrato arquitetural para orientar AG-11.

---

# 32. Item omitido

Se um item não estiver presente em:

```text
decisions[]
```

nenhuma decisão deve ser inferida para ele.

---

# 33. Dados comerciais não vêm no POST

O cliente não deve enviar como fonte de verdade:

```text
price
description
quantity
```

para determinar o conteúdo aprovado.

---

# 34. Fonte de verdade

O backend carrega:

```text
QuoteItemRevision
```

persistida.

A decisão é aplicada a essa versão.

---

# 35. Atomicidade da submissão

Para a TASK-0001, a submissão de múltiplas decisões será tratada como uma única unidade transacional.

Regra:

```text
ou todas as decisões válidas da submissão são registradas
ou nenhuma é registrada.
```

---

# 36. Justificativa

Isso evita:

```text
Item A aprovado
Item B falhou por inconsistência
```

quando o cliente confirmou os dois em uma mesma operação.

---

# 37. Validação prévia completa

Antes de persistir qualquer decisão:

```text
validar todos os itens enviados.
```

---

# 38. Falha em um item

Se um item da submissão for inválido:

```text
rollback da submissão inteira.
```

---

# 39. Transação

O caso de uso:

```text
RegisterPublicQuoteDecisionsUseCase
```

deve executar dentro de uma transação local PostgreSQL.

---

# 40. Chamada externa

Não existe chamada externa necessária durante essa transação.

---

# 41. Eventos

Eventos de domínio produzidos são registrados no mesmo contexto transacional.

Exemplos:

```text
QuoteItemApproved
QuoteItemRejected
```

---

# 42. Transactional Outbox

Para eventos que terão consumidores em outros módulos:

utilizar:

```text
Transactional Outbox
```

---

# 43. Motivo do outbox

Cenário futuro:

```text
QuoteItemApproved
↓
Estoque precisa reservar item
```

Se aplicação cair após commit da aprovação:

a informação não pode desaparecer.

---

# 44. Persistência transacional

Na mesma transação:

```text
persistir decisão
+
persistir outbox event
```

---

# 45. Publicação posterior

Worker interno:

```text
PENDING
↓
PROCESSING
↓
SUCCESS
```

com estados de retry conforme arquitetura global.

---

# 46. Sem broker externo

No MVP:

```text
não usar Kafka;
não usar RabbitMQ.
```

---

# 47. Eventos públicos do módulo

Contratos candidatos:

```text
QuoteCreated
QuoteRevisionCreated
QuoteSent
QuoteItemApproved
QuoteItemRejected
QuoteItemRevised
QuoteItemReopened
```

---

# 48. Eventos internos versus públicos

Nem todo evento de domínio precisa ser publicado para outros módulos.

AG-11 deve distinguir:

```text
domain event interno
```

de:

```text
integration event público
```

---

# 49. QuoteItemApproved

É candidato forte a evento público porque futuros módulos podem reagir.

---

# 50. Payload

Payload não deve carregar entidade inteira.

Exemplo:

```json
{
  "eventId": "...",
  "occurredAt": "...",
  "quoteId": "...",
  "quoteRevisionId": "...",
  "quoteItemId": "...",
  "quoteItemRevisionId": "...",
  "workOrderId": "...",
  "workOrderServiceId": "..."
}
```

---

# 51. Dados pessoais em evento

Não incluir CPF/CNPJ ou dados pessoais em eventos sem necessidade.

---

# 52. Concorrência — modelo geral

Existem três riscos principais:

```text
1. duas decisões simultâneas;
2. decisão versus nova revisão;
3. repetição da mesma requisição.
```

---

# 53. Estratégia de concorrência

Recomenda-se combinação de:

```text
optimistic locking
+
constraints únicas
+
idempotency key
```

AG-10 definirá estrutura física.

---

# 54. Optimistic locking

Agregados mutáveis relevantes devem possuir versão técnica.

Conceito:

```text
version
```

para detectar atualização concorrente.

---

# 55. Dois APPROVE simultâneos

Cenário:

```text
A → APPROVE
A → APPROVE
```

Não devem gerar duas decisões efetivas.

---

# 56. APPROVE versus REJECT

Cenário concorrente:

```text
A → APPROVE
A → REJECT
```

A aplicação deve impedir que ambas sejam consolidadas.

---

# 57. Regra

Uma `QuoteItemRevision` pode possuir no máximo:

```text
uma decisão efetiva
```

no escopo atual.

---

# 58. Constraint

AG-10 deve avaliar constraint equivalente a:

```text
UNIQUE(quote_item_revision_id)
```

na tabela de decisão efetiva.

---

# 59. Histórico futuro

Se futuramente houver retratação:

não remover a constraint sem redesenhar o modelo.

Poderá ser necessário conceito de:

```text
decision sequence
```

ou:

```text
superseded decision
```

Não pertence à TASK-0001.

---

# 60. Idempotência

O endpoint de decisão deve aceitar identificador de idempotência.

Conceito:

```text
requestId
```

---

# 61. Escopo da idempotência

Chave deve estar vinculada ao acesso/contexto correto.

Não pode ser globalmente reaproveitada por outro cliente de forma perigosa.

---

# 62. Resultado repetido

Se uma requisição já processada for reenviada com mesmo identificador e mesmo conteúdo:

retornar resultado equivalente sem novo efeito.

---

# 63. Mesma chave com conteúdo diferente

Deve ser rejeitada como conflito.

---

# 64. Armazenamento de idempotência

Pode ser:

```text
tabela própria;
registro da submissão;
constraint no decision batch.
```

AG-10 definirá.

---

# 65. Conceito recomendado

Criar conceito técnico:

```text
QuoteDecisionSubmission
```

para representar uma confirmação pública.

---

# 66. QuoteDecisionSubmission

Responsabilidades:

```text
requestId;
publicAccessId;
quoteRevisionId;
customer identity snapshot;
explicitAcceptance;
timestamp;
IP;
user-agent;
resultado.
```

---

# 67. Decisões dentro da submissão

Relacionamento:

```text
QuoteDecisionSubmission
    1
    |
    | possui
    |
    1..N
    QuoteDecision
```

---

# 68. Benefício

Permite responder:

```text
quais decisões foram confirmadas juntas?
```

e implementar idempotência de forma clara.

---

# 69. Atualização do modelo conceitual

Modelo arquitetural recomendado:

```text
Quote
├── QuoteRevision
├── QuoteItem
└── QuoteItemRevision

QuoteDecisionSubmission
└── QuoteDecision

PublicQuoteAccess
```

---

# 70. Fronteira de agregado de Submission

`QuoteDecisionSubmission` pode ser agregado histórico próprio.

Após criado:

```text
imutável
```

salvo metadados técnicos estritamente necessários.

---

# 71. Motivo

A submissão representa um fato concluído.

Não deve sofrer edição administrativa comum.

---

# 72. Ligação da decisão

Cada `QuoteDecision` referencia:

```text
QuoteItemRevisionId
```

e sua submissão.

---

# 73. Segurança

AG-09 deve revisar obrigatoriamente:

```text
token;
hash/digest;
expiração;
revogação;
rate limiting;
enumeração;
CPF/CNPJ;
logs;
IP;
user-agent;
CSRF;
cookies;
headers;
cache HTTP.
```

---

# 74. Autenticação pública

Não utilizar sessão de funcionário para o cliente.

---

# 75. Autorização pública

Autorização é dada pelo:

```text
PublicQuoteAccess
```

válido e limitado à revisão específica.

---

# 76. Token bruto

Recomendação arquitetural:

```text
gerar token criptograficamente aleatório;
entregar token bruto ao cliente;
persistir somente digest/hash adequado;
comparar de forma segura.
```

AG-09 define detalhes.

---

# 77. Expiração do acesso

`PublicQuoteAccess` pode possuir:

```text
validUntil
```

coerente com a revisão apresentada.

---

# 78. Revogação

Campo conceitual:

```text
revokedAt
```

Se presente:

```text
acesso inválido.
```

---

# 79. Acesso e nova revisão

Ao criar nova revisão para reapresentação:

recomenda-se novo acesso público vinculado à nova revisão.

---

# 80. Acesso antigo

Pode ser:

```text
revogado
```

ou permanecer apenas para consulta histórica, dependendo da regra futura.

Para decisão:

não deve autorizar revisão nova.

---

# 81. Regra mínima obrigatória

Mesmo que acesso antigo ainda exista:

```text
não pode decidir revisão diferente daquela à qual foi vinculado.
```

---

# 82. Stale revision

Se houver revisão comercial nova aplicável ao item:

a arquitetura deve ser capaz de rejeitar tentativa obsoleta quando necessário.

---

# 83. Definição de obsolescência

Não basta existir qualquer revisão global posterior.

É preciso considerar se o item específico teve sua condição comercial substituída.

---

# 84. Complemento

Exemplo:

```text
R1:
A aprovado

R2:
A sem alteração
B novo
```

A aprovação de A continua válida.

Logo:

```text
R2 existir
```

não torna automaticamente A inválido.

---

# 85. Consequência arquitetural

O estado efetivo deve ser avaliado por:

```text
QuoteItemRevision
```

e não apenas por:

```text
último número global de QuoteRevision.
```

---

# 86. Versionamento por item

Cada item deve possuir sua sequência de revisões comerciais.

---

# 87. QuoteRevision

Funciona como envelope/apresentação comercial.

---

# 88. QuoteItemRevision

Funciona como versão comercial específica do item.

---

# 89. Inclusão em revisão

Pode existir relacionamento explícito indicando quais versões de item pertencem a uma `QuoteRevision`.

---

# 90. Modelo relacional conceitual

Exemplo:

```text
quote
quote_revision
quote_item
quote_item_revision
quote_revision_item
quote_decision_submission
quote_decision
public_quote_access
```

AG-10 decide nomes e estrutura final.

---

# 91. quote_revision_item

Relacionamento recomendado:

```text
QuoteRevision
N
↕
N
QuoteItemRevision
```

através de associação explícita.

---

# 92. Motivo

Uma nova revisão global pode reutilizar uma versão comercial de item que não mudou.

Exemplo:

```text
R1:
A-v1
B-v1

R2:
A-v1
B-v2
C-v1
```

---

# 93. Benefício

Isso preserva exatamente:

```text
A não mudou
B mudou
C é novo
```

sem duplicar artificialmente versões idênticas.

---

# 94. Aprovação nesse modelo

Exemplo:

```text
A-v1 → APPROVED
B-v1 → REJECTED

R2:
A-v1
B-v2
C-v1
```

Resultado:

```text
A-v1 → continua APPROVED
B-v2 → PENDING
C-v1 → PENDING
```

---

# 95. Alteração interna

Não cria nova `QuoteItemRevision`.

---

# 96. Alteração comercial

Cria nova `QuoteItemRevision`.

---

# 97. Nova revisão global

Uma alteração comercial ou complemento pode produzir nova `QuoteRevision`.

---

# 98. Reabertura

Reabertura de item rejeitado cria nova `QuoteItemRevision` mesmo se conteúdo comercial for igual.

Motivo:

```text
nova oportunidade de decisão.
```

---

# 99. Identidade da reapresentação

Nesse caso, `QuoteItemRevision` não representa apenas mudança de valor.

Também representa:

```text
nova proposição comercial decidível.
```

---

# 100. Estado efetivo

Não persistir necessariamente um estado redundante no `QuoteItem` se ele puder ser derivado com segurança.

AG-10/AG-11 devem avaliar custo de consulta versus risco de inconsistência.

---

# 101. Leitura otimizada

Se necessário futuramente:

```text
read model
```

pode ser criado.

Não misturar isso com fonte de verdade do domínio.

---

# 102. CQRS

Não adotar CQRS completo para esta Task.

Consultas específicas podem usar projections/read queries sem criar infraestrutura excessiva.

---

# 103. Consulta interna

Endpoint conceitual:

```text
GET /api/work-orders/{workOrderId}/quotes/{quoteId}
```

---

# 104. Histórico interno

Endpoint conceitual:

```text
GET /api/work-orders/{workOrderId}/quotes/{quoteId}/history
```

---

# 105. Criar revisão

Endpoint conceitual:

```text
POST /api/work-orders/{workOrderId}/quotes/{quoteId}/revisions
```

---

# 106. Reabrir item

Endpoint conceitual:

```text
POST /api/work-orders/{workOrderId}/quotes/{quoteId}/items/{itemId}/reopen
```

---

# 107. Enviar/reapresentar

Endpoint conceitual:

```text
POST /api/work-orders/{workOrderId}/quotes/{quoteId}/present
```

Pode gerar novo acesso público.

---

# 108. Endpoints não são finais

AG-11 poderá ajustar:

```text
paths;
DTOs;
status HTTP;
nomenclatura.
```

sem alterar arquitetura aprovada.

---

# 109. Application Use Cases propostos

```text
CreateQuoteUseCase
CreateQuoteRevisionUseCase
PresentQuoteRevisionUseCase
GetPublicQuoteRevisionUseCase
RegisterPublicQuoteDecisionsUseCase
ReopenRejectedQuoteItemUseCase
GetQuoteHistoryUseCase
```

---

# 110. Ports de persistência

Conceitualmente:

```text
QuoteRepository
QuoteDecisionSubmissionRepository
PublicQuoteAccessRepository
OutboxRepository
```

---

# 111. Ports públicos do módulo

Outros módulos não recebem os repositories.

Podem consumir:

```text
application contract
```

ou:

```text
domain/integration events.
```

---

# 112. Estoque

No futuro:

```text
QuoteItemApproved
↓
Inventory application handler
```

---

# 113. Compras

No futuro:

```text
falta de estoque
↓
PurchaseNeedCreated
```

Não pertence à TASK-0001.

---

# 114. Financeiro

Não recebe evento financeiro apenas porque ocorreu aprovação.

---

# 115. Comissão

Não recebe evento de comissão na aprovação.

---

# 116. Fiscal

Não recebe pedido de NFS-e apenas pela aprovação.

---

# 117. Transição para execução

O módulo Oficina poderá utilizar a decisão aprovada para permitir iniciar serviço correspondente.

---

# 118. Regra

```text
WorkOrderService.start()
```

ou equivalente deve verificar existência de autorização comercial válida quando aplicável.

---

# 119. Não duplicar regra no frontend

O React pode desabilitar botão.

Backend continua validando.

---

# 120. Money

Em Java:

```text
BigDecimal
```

Em PostgreSQL:

```text
NUMERIC
```

---

# 121. Quantidade

A estrutura precisa preservar quantidade apresentada.

O tipo final depende da semântica da quantidade do orçamento.

Não decidir nesta Task se toda quantidade será inteira.

---

# 122. Data/hora

Persistir timestamps com semântica clara.

Para eventos/evidência:

recomendação:

```text
Instant
```

ou abordagem equivalente consistente.

---

# 123. Timezone de apresentação

Frontend poderá apresentar no fuso local da oficina.

Persistência não deve depender de strings locais ambíguas.

---

# 124. Clock

Use cases/regras dependentes de tempo devem receber abstração de clock.

Exemplo conceitual:

```text
Clock
```

para teste determinístico.

---

# 125. Validade padrão

Regra de produto:

```text
7 dias
```

Mas a revisão deve armazenar:

```text
validUntil
```

como snapshot.

---

# 126. Configuração futura

Se o padrão mudar:

revisões já emitidas não são alteradas.

---

# 127. Auditoria

Existem dois tipos de rastreabilidade:

```text
histórico de domínio
+
auditoria transversal
```

---

# 128. Histórico de domínio

Inclui:

```text
QuoteDecisionSubmission
QuoteDecision
QuoteRevision
QuoteItemRevision
```

---

# 129. Auditoria transversal

AG-09 poderá registrar:

```text
ator
ação
recurso
timestamp
correlationId
metadados
```

---

# 130. Não duplicar tudo

Auditoria não precisa copiar todo snapshot se domínio já o preserva.

Mas deve permitir rastrear a operação.

---

# 131. Correlation ID

Recomendado para requisições públicas e processamento de outbox.

---

# 132. Observabilidade

Logs devem registrar identificadores técnicos, nunca segredo bruto.

---

# 133. Log proibido

Não registrar:

```text
token bruto;
CPF/CNPJ completo sem necessidade;
dados sensíveis completos;
cookie de sessão.
```

---

# 134. Erros arquiteturais públicos

A API deve distinguir conceitualmente:

```text
INVALID_ACCESS
EXPIRED_ACCESS
STALE_REVISION
ALREADY_DECIDED
INVALID_DECISION
VALIDATION_ERROR
CONFLICT
```

---

# 135. Não revelar existência

Para token inválido:

resposta não deve permitir enumeração de OS/orçamento.

---

# 136. Status HTTP

AG-11 definirá os códigos específicos.

Sugestões deverão respeitar semântica HTTP.

---

# 137. Requisição vazia

Uma submissão sem nenhuma decisão:

não possui efeito.

AG-11/AG-01 podem definir se será:

```text
rejeitada
```

ou:

```text
no-op
```

Não é impeditivo para o modelo estrutural.

Caso necessário antes da implementação, abrir Decision Request.

---

# 138. Ponto de atenção

A TASK diz que o cliente pode deixar itens sem decisão.

Isso não significa necessariamente que ele deve poder enviar:

```text
zero decisões.
```

A regra exata pode ser refinada na API.

---

# 139. Decision Request atual

Não há Decision Request impeditiva para arquitetura.

---

# 140. Segurança de CPF/CNPJ

Não decidir criptografia de campo neste documento.

AG-09 fará análise específica.

---

# 141. Integridade referencial

AG-10 deverá garantir que:

```text
decision
```

não possa referenciar:

```text
itemRevision
```

de orçamento incompatível.

---

# 142. Consistência na aplicação

Mesmo com FKs:

o use case deve validar semanticamente os relacionamentos.

---

# 143. Persistência sugerida — quote

Responsabilidade:

```text
identidade do orçamento;
workOrderId;
status estrutural;
version.
```

---

# 144. quote_revision

Responsabilidade:

```text
quoteId;
revisionNumber;
presentedAt;
validUntil;
createdAt.
```

---

# 145. quote_item

Responsabilidade:

```text
quoteId;
identidade lógica do item;
workOrderServiceId quando aplicável.
```

---

# 146. quote_item_revision

Responsabilidade:

```text
quoteItemId;
revisionSequence;
description;
quantity;
price;
createdAt;
reason/type quando necessário.
```

---

# 147. quote_revision_item

Responsabilidade:

```text
quoteRevisionId;
quoteItemRevisionId;
ordenação de apresentação.
```

---

# 148. quote_decision_submission

Responsabilidade:

```text
idempotencyRequestId;
publicAccessId;
quoteRevisionId;
customer identity snapshot;
explicitAcceptance;
occurredAt;
IP;
userAgent.
```

---

# 149. quote_decision

Responsabilidade:

```text
submissionId;
quoteItemRevisionId;
decisionType;
occurredAt.
```

---

# 150. public_quote_access

Responsabilidade:

```text
quoteId;
quoteRevisionId;
tokenDigest;
validUntil;
revokedAt;
createdAt.
```

---

# 151. Índices candidatos

AG-10 deve analisar pelo menos:

```text
quote.work_order_id
quote_revision.quote_id
quote_item.quote_id
quote_item_revision.quote_item_id
quote_revision_item.quote_revision_id
quote_decision.quote_item_revision_id
quote_decision_submission.request_id
public_quote_access.token_digest
```

---

# 152. Constraints candidatas

```text
UNIQUE(quote_id, revision_number)

UNIQUE(quote_item_id, revision_sequence)

UNIQUE(quote_revision_id, quote_item_revision_id)

UNIQUE(quote_item_revision_id)
na decisão efetiva da modelagem atual

UNIQUE(public_access_id, request_id)
ou equivalente para idempotência
```

AG-10 valida.

---

# 153. Token digest

Deve possuir índice adequado para lookup eficiente.

---

# 154. Optimistic version

`Quote` deve ser candidato a:

```text
@Version
```

ou mecanismo equivalente.

---

# 155. Lock de decisão

A estratégia final para decisão concorrente poderá ser:

```text
constraint única
+
tratamento de conflito
```

sem necessidade obrigatória de lock pessimista.

---

# 156. Revisão concorrente

Criação de nova revisão deve trabalhar com versionamento do agregado ou constraint de número de revisão.

---

# 157. Repetição de número de revisão

Não permitir:

```text
Quote 100
Revision 3
Revision 3
```

---

# 158. Publicação de revisão

Uma revisão apresentada deve ser identificável como tal.

Pode haver distinção entre:

```text
DRAFT
PRESENTED
```

se necessária.

---

# 159. Cuidado com estado adicional

Não adicionar estados de negócio sem necessidade.

Caso `DRAFT/PRESENTED` seja necessário apenas tecnicamente para o fluxo, documentar claramente.

---

# 160. Revisão mutável antes de apresentação

Arquitetura possível:

```text
DRAFT
→ conteúdo ainda editável
```

Depois:

```text
PRESENTED
→ snapshot imutável
```

---

# 161. Justificativa

Isso facilita composição do orçamento antes de envio sem gerar dezenas de revisões históricas inúteis.

---

# 162. Limite

Depois que a revisão for apresentada ou utilizada como evidência:

não pode sofrer mutação comercial retroativa.

---

# 163. Estados arquiteturais de QuoteRevision propostos

```text
DRAFT
PRESENTED
```

Expiração pode ser derivada por `validUntil`.

Não é necessário persistir:

```text
EXPIRED
```

como estado obrigatório.

---

# 164. Cancelamento

Não definido na TASK.

Não incluir estado `CANCELLED` nesta feature sem requisito específico.

---

# 165. Apresentação

Ao executar:

```text
PresentQuoteRevisionUseCase
```

devem ser definidos:

```text
presentedAt
validUntil
PublicQuoteAccess
```

---

# 166. Snapshot após apresentação

Após `PRESENTED`:

campos comerciais da revisão e das versões de item associadas não devem ser alterados.

---

# 167. Nova alteração

Exige:

```text
nova revisão
```

ou nova `QuoteItemRevision`, conforme regra.

---

# 168. Nova revisão reutilizando item não alterado

Permitido:

```text
R2
→ reutiliza A-v1
→ utiliza B-v2
```

---

# 169. PublicQuoteAccess e apresentação

Cada apresentação deve gerar ou associar acesso correspondente à revisão exata.

---

# 170. Revogação de acessos antigos

Quando nova revisão substituir outra para decisão:

recomenda-se revogar acessos decisórios antigos.

---

# 171. Complemento sem invalidar aprovado

Mesmo que o acesso antigo seja revogado:

a decisão histórica de item aprovado permanece válida.

---

# 172. Leitura pública da nova revisão

Nova apresentação pode exibir:

```text
itens já aprovados
+
itens novos pendentes
```

---

# 173. UX e domínio

Frontend deve deixar evidente quais itens:

```text
já foram aprovados;
foram rejeitados;
aguardam decisão.
```

---

# 174. Não pedir nova aprovação artificial

Item já aprovado e não alterado pode ser exibido como:

```text
APROVADO ANTERIORMENTE
```

sem exigir novo clique.

---

# 175. Handoff para AG-09

```text
Task:
TASK-0001

Superfície pública:
GET /api/public/quotes/{token}
POST /api/public/quotes/{token}/decisions

Dados sensíveis:
- token
- nome
- CPF/CNPJ
- IP
- user-agent

Modelo:
PublicQuoteAccess separado.
Token bruto não deve ser persistido como segredo recuperável.
Acesso limitado a QuoteRevision específica.

Analisar:
- geração do token;
- digest;
- comparação;
- expiração;
- revogação;
- rate limiting;
- CSRF;
- cache;
- enumeration;
- logs;
- proteção de CPF/CNPJ;
- auditoria;
- headers.

Status:
SECURITY_REVIEW_REQUIRED
```

---

# 176. Handoff para AG-10

```text
Task:
TASK-0001

Modelo conceitual:
- quote
- quote_revision
- quote_item
- quote_item_revision
- quote_revision_item
- quote_decision_submission
- quote_decision
- public_quote_access

Pontos críticos:
- histórico imutável;
- many-to-many entre revisão e versão de item;
- idempotência;
- uma decisão efetiva por itemRevision;
- tokenDigest único/indexado;
- optimistic locking;
- revisão numerada;
- concorrência approve/reject;
- BigDecimal/NUMERIC;
- timestamps.

Validar:
- FKs;
- UNIQUE;
- CHECK;
- índices;
- estratégia de locking;
- estrutura do idempotency request.

Status:
READY_FOR_DATA_REVIEW
```

---

# 177. Handoff para AG-11

```text
Task:
TASK-0001

Nesta simulação:
NÃO implementar código.

Produzir:
- contratos REST refinados;
- casos de uso;
- DTOs conceituais;
- erros;
- fronteiras transacionais;
- plano de implementação.

Use cases:
- CreateQuoteUseCase
- CreateQuoteRevisionUseCase
- PresentQuoteRevisionUseCase
- GetPublicQuoteRevisionUseCase
- RegisterPublicQuoteDecisionsUseCase
- ReopenRejectedQuoteItemUseCase
- GetQuoteHistoryUseCase

Regra crítica:
backend é fonte de verdade.

Status:
AGUARDAR AG-09 E AG-10
```

---

# 178. Handoff para AG-12

```text
Task:
TASK-0001

Nesta simulação:
NÃO implementar React.

Produzir posteriormente:
- fluxo interno;
- fluxo público;
- estados visuais;
- tratamento de erro;
- comportamento de aprovação parcial;
- apresentação de revisões;
- UX de item aprovado anteriormente.

Status:
AGUARDAR CONTRATO AG-11
```

---

# 179. Handoff para AG-13

```text
Task:
TASK-0001

Testar posteriormente:
- aprovação parcial;
- rejeição;
- item omitido;
- alteração comercial;
- alteração interna;
- complemento;
- reabertura;
- expiração;
- acesso inválido;
- revisão obsoleta;
- idempotência;
- concorrência approve/approve;
- concorrência approve/reject;
- atomicidade da submissão.

Status:
AGUARDAR CONTRATOS
```

---

# 180. Decisões arquiteturais consolidadas

```text
1. Oficina é proprietária do orçamento.

2. Quote é o agregado comercial principal.

3. QuoteItem possui identidade lógica.

4. QuoteItemRevision representa versão comercial decidível.

5. QuoteRevision representa apresentação global.

6. QuoteRevision pode reutilizar QuoteItemRevision não alterada.

7. QuoteDecision não é campo mutável no item.

8. QuoteDecisionSubmission agrupa decisões confirmadas juntas.

9. PublicQuoteAccess possui ciclo próprio.

10. Cliente público não é User IAM.

11. Token autoriza somente revisão específica.

12. Submissão de múltiplas decisões é atômica.

13. Backend usa snapshot persistido, nunca preço enviado pelo browser.

14. Idempotência é obrigatória.

15. Concorrência usa versão + constraints + idempotência.

16. Uma versão de item possui uma decisão efetiva no escopo atual.

17. Histórico não é sobrescrito.

18. Eventos intermodulares usam outbox quando necessário.

19. Não usar broker externo no MVP.

20. Outros módulos não acessam repositories/tabelas de Oficina diretamente.
```

---

# 181. Riscos arquiteturais

## R-01 — Complexidade excessiva de revisão

Risco:

```text
QuoteRevision + QuoteItemRevision
```

pode parecer mais complexo que um simples status.

Mitigação:

```text
complexidade é necessária para preservar
aprovações parciais e histórico comercial correto.
```

---

## R-02 — Duplicação de versão

Mitigação:

```text
reutilizar QuoteItemRevision quando conteúdo
comercial não mudou.
```

---

## R-03 — Estado derivado incorreto

Mitigação:

```text
fonte de verdade = versão comercial + decisão.
```

---

## R-04 — Corrida de decisão

Mitigação:

```text
constraint + locking/versionamento + transação.
```

---

## R-05 — Token vazado

Mitigação:

```text
AG-09;
token forte;
digest persistido;
expiração;
revogação;
rate limiting.
```

---

## R-06 — Evento duplicado

Mitigação:

```text
eventId;
outbox;
consumidores idempotentes.
```

---

# 182. Decision Requests

Impeditivas:

```text
NENHUMA
```

Ponto não impeditivo para refinamento:

```text
Definir posteriormente se POST com decisions[] vazio
será rejeitado ou tratado como no-op.
```

Isso deve ser definido antes da implementação do endpoint, mas não bloqueia AG-09/AG-10.

---

# 183. Resultado do AG-02

```text
TASK:
TASK-0001

STATUS:
ARCHITECTURE_APPROVED

MÓDULO:
WORKSHOP / QUOTE

AGREGADOS:
- Quote
- QuoteDecisionSubmission
- PublicQuoteAccess

ENTIDADES:
- QuoteRevision
- QuoteItem
- QuoteItemRevision
- QuoteDecision

ASSOCIAÇÃO:
- QuoteRevision ↔ QuoteItemRevision

TRANSAÇÃO DE DECISÃO:
ATÔMICA

CONCORRÊNCIA:
OPTIMISTIC LOCKING
+
CONSTRAINTS
+
IDEMPOTÊNCIA

OUTBOX:
SIM PARA EVENTOS INTERMODULARES

BROKER EXTERNO:
NÃO

SEGURANÇA:
REVIEW AG-09 OBRIGATÓRIA

BANCO:
REVIEW AG-10 OBRIGATÓRIA

DECISION REQUESTS IMPEDITIVAS:
NENHUMA

PRONTO PARA AG-09:
SIM

PRONTO PARA AG-10:
SIM

PRONTO PARA AG-11:
NÃO — AGUARDAR AG-09 E AG-10
```

---

# 184. Definition of Done do AG-02

- [x] módulo proprietário definido;
- [x] agregados definidos;
- [x] histórico preservado;
- [x] revisões definidas;
- [x] acesso público separado;
- [x] transação definida;
- [x] atomicidade definida;
- [x] idempotência definida;
- [x] concorrência definida;
- [x] eventos definidos;
- [x] outbox avaliado;
- [x] contratos REST conceituais definidos;
- [x] segurança encaminhada;
- [x] banco encaminhado;
- [x] fronteiras modulares preservadas;
- [x] nenhuma microservice desnecessária;
- [x] nenhuma Decision Request impeditiva.

---

# 185. Regra final

A arquitetura deve preservar simultaneamente:

```text
IDENTIDADE DO ITEM
+
VERSÃO COMERCIAL
+
APRESENTAÇÃO AO CLIENTE
+
DECISÃO DO CLIENTE
+
EVIDÊNCIA
```

sem permitir que uma revisão posterior altere o significado de uma decisão passada.

**UMA APROVAÇÃO SÓ É VÁLIDA PARA A VERSÃO COMERCIAL EXATA QUE O CLIENTE DECIDIU.**