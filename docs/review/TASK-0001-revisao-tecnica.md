# Revisão Técnica — TASK-0001 — Aprovação Parcial de Orçamento

## 1. Identificação

Task:

```text
TASK-0001
```

Requisito principal:

```text
REQ-ORC-001
```

Agente responsável:

```text
AG-15 — Revisor Técnico
```

Tipo de revisão:

```text
REVISÃO TÉCNICA INDEPENDENTE
```

Data:

```text
2026-09-08
```

Status:

```text
CHANGES_REQUESTED
```

Implementação revisada:

```text
NÃO EXISTE AINDA
```

Objeto da revisão:

```text
ESPECIFICAÇÃO DOCUMENTAL DA FEATURE
```

---

# 2. Objetivo

Revisar de forma independente a especificação produzida para a aprovação parcial de orçamento antes de permitir que a TASK-0001 seja encerrada pelo AG-00.

A revisão verifica:

```text
requisitos;
domínio;
arquitetura;
segurança;
persistência;
backend;
frontend;
testes;
concorrência;
idempotência;
histórico;
rastreabilidade.
```

---

# 3. Documentos revisados

```text
tasks/backlog/TASK-0001-aprovacao-parcial-orcamento.md

docs/requirements/oficina/REQ-ORC-001-aprovacao-parcial.md

docs/domain/oficina/aprovacao-parcial-orcamento.md

docs/architecture/oficina/TASK-0001-aprovacao-parcial-orcamento.md

docs/architecture/security/TASK-0001-seguranca-aprovacao-publica.md

docs/architecture/database/TASK-0001-modelo-dados-aprovacao-orcamento.md

docs/api/TASK-0001-aprovacao-publica-orcamento.md

docs/architecture/frontend/TASK-0001-fluxo-aprovacao-orcamento.md

docs/architecture/testing/TASK-0001-plano-testes-aprovacao-orcamento.md
```

Documento de governança utilizado:

```text
agents/AG-15-revisor-tecnico.md
```

---

# 4. Limite desta revisão

Não existem nesta etapa:

```text
código Java;
código React;
migrations Flyway;
testes executáveis;
container;
deploy;
pull request de implementação.
```

Portanto esta revisão avalia:

```text
consistência e completude da especificação
```

e não qualidade de uma implementação inexistente.

---

# 5. Regra de independência

O AG-15 não presume que uma decisão tomada por agente anterior esteja correta apenas porque foi marcada como:

```text
APPROVED
```

Cada decisão foi confrontada com:

```text
requisito;
regra de domínio;
arquitetura;
segurança;
integridade de dados;
contratos;
testes.
```

---

# 6. Resumo executivo

A especificação apresenta boa cobertura dos principais requisitos da aprovação parcial.

Estão corretamente representados:

```text
aprovação individual;
rejeição individual;
pendência;
aprovação parcial;
histórico;
revisões;
alterações comerciais;
alterações internas;
complementos;
reabertura;
token público;
evidências;
idempotência;
atomicidade;
concorrência;
outbox;
segurança;
frontend;
testes.
```

Entretanto foram identificadas inconsistências que não devem ser carregadas para a implementação.

Resultado:

```text
APROVAÇÃO FINAL:
NÃO

CORREÇÕES:
NECESSÁRIAS
```

---

# 7. Achados

Resumo:

```text
CRITICAL: 0

HIGH: 2

MEDIUM: 1

LOW: 1

NOTE: 1
```

---

# 8. HIGH-01 — Integridade relacional insuficiente entre orçamento, revisão e decisão

Severidade:

```text
HIGH
```

Status:

```text
OPEN
```

Áreas:

```text
Banco de Dados
Arquitetura
Segurança
Backend
```

---

# 9. Problema HIGH-01

O modelo proposto possui relações como:

```text
QuoteRevision
→ QuoteItemRevision
```

através de:

```text
quote_revision_item
```

Porém as FKs propostas garantem apenas que ambos os registros existem.

Elas não garantem estruturalmente que ambos pertencem ao mesmo:

```text
Quote
```

---

# 10. Exemplo do problema

Seria estruturalmente possível, por erro de implementação, formar:

```text
Quote A
└── Revision A1
      └── ItemRevision pertencente ao Quote B
```

As FKs atualmente propostas poderiam aceitar esse relacionamento.

---

# 11. Impacto

Isso viola uma invariante central:

```text
uma revisão somente pode apresentar versões
de itens do mesmo orçamento.
```

Também cria risco de:

```text
IDOR;
vazamento de informação;
histórico comercial incorreto;
decisão associada ao orçamento errado.
```

---

# 12. Mesmo problema em PublicQuoteAccess

Existe:

```text
public_quote_access.quote_id
public_quote_access.quote_revision_id
```

mas o banco não garante estruturalmente que:

```text
quote_revision_id
```

pertence ao:

```text
quote_id
```

informado.

---

# 13. Mesmo problema em QuoteDecisionSubmission

Existe:

```text
public_quote_access_id
quote_revision_id
```

mas o banco não garante que a revisão da submissão é exatamente a revisão autorizada pelo acesso.

---

# 14. Mesmo problema em QuoteDecision

Existe:

```text
submission_id
quote_item_revision_id
```

mas o banco não garante estruturalmente que aquela versão de item fazia parte da revisão da submissão.

---

# 15. Defesa atual

Os documentos determinam validação na aplicação.

Isso é necessário, porém não é suficiente para uma invariante estrutural crítica quando o PostgreSQL pode reforçá-la.

---

# 16. Correção esperada HIGH-01

AG-10 e AG-02 devem revisar o modelo físico para aumentar integridade referencial.

A solução pode utilizar:

```text
chaves compostas;
quote_id redundante controlado;
FKs compostas;
constraints adequadas;
ou outro desenho equivalente.
```

---

# 17. Exemplo conceitual

Uma possibilidade:

```text
quote_revision
UNIQUE(id, quote_id)

quote_item_revision
possui quote_id derivado/controlado
UNIQUE(id, quote_id)
```

Permitindo:

```text
quote_revision_item(
    quote_revision_id,
    quote_item_revision_id,
    quote_id
)
```

com FKs que garantam:

```text
revision pertence ao quote_id
E
itemRevision pertence ao mesmo quote_id.
```

---

# 18. Observação

AG-15 não determina que esse seja necessariamente o desenho final.

A exigência é:

```text
a invariante cross-quote precisa possuir
proteção estrutural proporcional ao risco.
```

---

# 19. Critério de fechamento HIGH-01

O finding será fechado quando existir desenho que impeça ou torne estruturalmente inviável:

```text
Revision do Quote A
→ ItemRevision do Quote B

PublicAccess do Quote A
→ Revision do Quote B

Submission para Revision A
→ Decision sobre item não apresentado em A
```

---

# 20. HIGH-02 — Regra de revisão obsoleta não está completamente determinada

Severidade:

```text
HIGH
```

Status:

```text
OPEN
```

Áreas:

```text
Produto
Domínio
Arquitetura
Backend
Frontend
QA
```

---

# 21. Problema HIGH-02

Os documentos corretamente determinam que:

```text
uma revisão antiga nunca deve aprovar
silenciosamente uma revisão nova.
```

Entretanto falta determinar exatamente quando uma revisão/item anterior deixa de aceitar uma nova decisão.

---

# 22. Cenário A

```text
R1 apresentada

A-v1 = R$ 500
PENDENTE
```

Depois:

```text
R2 DRAFT

A-v2 = R$ 600
```

Pergunta:

```text
enquanto R2 ainda está DRAFT,
o cliente ainda pode aprovar A-v1 através de R1?
```

A especificação atual não responde de maneira inequívoca.

---

# 23. Cenário B

```text
R1 apresentada

A-v1 = R$ 500
PENDENTE
```

Depois:

```text
R2 apresentada

A-v2 = R$ 600
PENDENTE
```

Pergunta:

```text
o link de R1 deve ser automaticamente impedido
de aprovar A-v1?
```

A resposta precisa ser explícita.

---

# 24. Cenário C — complemento sem alteração

```text
R1:
A-v1 pending

R2:
A-v1 sem alteração
B-v1 novo
```

Pergunta:

```text
o cliente ainda pode decidir A-v1 utilizando R1?
```

Esse caso é diferente de uma alteração comercial de A.

---

# 25. Por que isso importa

Uma regra excessivamente global como:

```text
existe R2
→ R1 inteira é stale
```

pode quebrar a semântica de complemento.

Por outro lado:

```text
R1 sempre continua decidível
```

pode permitir aprovação de uma condição comercial que já foi substituída.

---

# 26. Regra atual insuficiente

Os documentos utilizam conceitos como:

```text
QUOTE_REVISION_STALE
```

e:

```text
item comercialmente substituído
```

mas o instante exato da substituição decisória não está fechado.

---

# 27. Risco

O comportamento pode divergir entre:

```text
backend;
frontend;
testes;
operações reais.
```

E pode permitir:

```text
aprovação de preço antigo
```

após uma nova condição comercial ter sido apresentada.

---

# 28. Correção esperada HIGH-02

O domínio precisa definir explicitamente a regra.

Uma regra possível para decisão do proprietário seria:

```text
A criação de DRAFT não invalida uma versão apresentada.

Quando uma nova versão comercial do MESMO ITEM
é efetivamente PRESENTED ao cliente,
a versão comercial anterior desse item
não aceita mais nova decisão.

Uma nova QuoteRevision criada apenas por complemento,
reutilizando a mesma QuoteItemRevision,
não invalida aquela versão de item.
```

Essa proposta é tecnicamente coerente com o modelo atual.

Entretanto:

```text
AG-15 NÃO PODE INVENTAR ESSA REGRA DE NEGÓCIO.
```

Ela precisa ser formalmente confirmada.

---

# 29. Decision Request

O finding HIGH-02 requer:

```text
DECISION REQUEST
```

antes da implementação.

---

# 30. Decisão necessária

Pergunta:

```text
Quando um item pendente possui uma nova versão comercial,
em qual momento a versão anterior deixa de aceitar decisão?
```

Opções principais:

```text
A — assim que a nova versão DRAFT for criada;

B — somente quando a nova versão for PRESENTED;

C — a versão anterior continua aceitando decisão até revogação manual;

D — outra regra definida pelo proprietário.
```

---

# 31. Recomendação técnica do AG-15

Sem transformar recomendação em requisito:

```text
OPÇÃO B
```

é a mais coerente com a arquitetura atual.

Motivo:

```text
DRAFT ainda é trabalho interno;

PRESENTED representa nova proposta comercial efetiva;

complementos que reutilizam a mesma ItemRevision
não invalidam o item anterior.
```

---

# 32. Critério de fechamento HIGH-02

O finding será fechado quando:

```text
a decisão estiver registrada;
REQ-ORC-001 estiver atualizado se necessário;
domínio refletir a regra;
arquitetura refletir a regra;
API tratar a regra;
QA possuir cenários explícitos.
```

---

# 33. MEDIUM-01 — Existem duas validades sem fonte de verdade explicitamente separada

Severidade:

```text
MEDIUM
```

Status:

```text
OPEN
```

Áreas:

```text
Arquitetura
Segurança
Banco
Backend
```

---

# 34. Problema MEDIUM-01

Existem:

```text
quote_revision.valid_until
```

e:

```text
public_quote_access.valid_until
```

Os documentos indicam que ambos devem ser coerentes.

Porém não definem claramente se representam:

```text
a mesma regra
```

ou:

```text
duas regras diferentes.
```

---

# 35. Duas semânticas possíveis

Validade comercial:

```text
QuoteRevision.validUntil
```

Responde:

```text
Até quando esta proposta comercial aceita nova decisão?
```

Validade da credencial:

```text
PublicQuoteAccess.validUntil
```

Responde:

```text
Até quando este token pode ser utilizado?
```

Esses conceitos podem ser diferentes.

---

# 36. Risco

Se ambos forem utilizados indistintamente:

```text
QuoteRevision válida
PublicQuoteAccess expirado
```

ou:

```text
QuoteRevision expirada
PublicQuoteAccess válido
```

pode gerar comportamento inconsistente.

---

# 37. Correção esperada MEDIUM-01

Definir explicitamente:

```text
validade comercial
```

e:

```text
validade de segurança do acesso.
```

Recomendação arquitetural:

```text
PublicQuoteAccess.validUntil
<=
QuoteRevision.validUntil
```

Para registrar decisão:

```text
ambos precisam estar válidos.
```

---

# 38. Alternativa

Se não existir necessidade real de validades diferentes:

usar uma única fonte de verdade e eliminar duplicação semântica.

---

# 39. Critério de fechamento MEDIUM-01

Documentar:

```text
qual campo governa qual regra;
qual relação deve existir entre eles;
como GET e POST se comportam.
```

---

# 40. LOW-01 — Frontend público menciona veículo sem contrato equivalente no backend

Severidade:

```text
LOW
```

Status:

```text
OPEN
```

Áreas:

```text
Frontend
Backend/API
```

---

# 41. Problema LOW-01

A especificação de frontend prevê potencialmente apresentar:

```text
veículo;
placa parcialmente mascarada;
referência da OS.
```

Porém o contrato público do GET atualmente apresenta essencialmente:

```text
revisão;
validade;
customer display name;
items.
```

Não há contrato definido para veículo.

---

# 42. Impacto

Pode causar implementação onde frontend:

```text
espera campo inexistente
```

ou backend adiciona dado público sem revisão de minimização.

---

# 43. Correção esperada LOW-01

Antes da implementação escolher uma opção:

```text
A — remover veículo/placa do escopo da página pública desta Task;

ou

B — definir DTO público mínimo e seguro para identificação do veículo.
```

---

# 44. Recomendação

Se não existe requisito explícito nesta Task:

```text
OPÇÃO A
```

mantém o escopo menor.

A identificação do veículo pode ser especificada junto da vertical slice Cliente → Veículo → OS.

---

# 45. NOTE-01 — Testes estão especificados, não executados

Severidade:

```text
NOTE
```

Status:

```text
EXPECTED
```

---

# 46. Observação NOTE-01

O AG-13 marca cenários como especificados.

Nenhum teste real foi executado.

Isso é esperado porque:

```text
TASK-0001 é uma simulação documental
anterior à implementação Spring Boot/React.
```

---

# 47. Não é defeito

A Task definiu `DONE` nesta simulação como:

```text
feature especificada e validada,
mas ainda não implementada.
```

Portanto ausência de testes executáveis:

```text
não bloqueia a simulação.
```

---

# 48. Revisão do requisito funcional

Aprovação parcial:

```text
CORRETA
```

---

# 49. Decisão individual

```text
CORRETA
```

---

# 50. Pendência

A regra:

```text
item omitido permanece pendente
```

está consistente em:

```text
requisito;
domínio;
API;
frontend;
QA.
```

Resultado:

```text
APPROVED
```

---

# 51. Rejeição

Preservada individualmente.

Resultado:

```text
APPROVED
```

---

# 52. Alteração comercial

Preço:

```text
nova aprovação
```

Descrição:

```text
nova aprovação
```

Quantidade:

```text
nova aprovação
```

Resultado:

```text
APPROVED
```

com ressalva do HIGH-02 sobre o momento de obsolescência da versão antiga.

---

# 53. Alteração interna

Não invalida aprovação.

Resultado:

```text
APPROVED
```

---

# 54. Complemento

Modelo:

```text
R1:
A-v1
B-v1

R2:
A-v1
B-v1
C-v1
```

permite preservar itens não alterados.

Resultado:

```text
APPROVED
```

---

# 55. Reabertura

Rejeição antiga é preservada.

Nova oportunidade possui nova:

```text
QuoteItemRevision
```

Resultado:

```text
APPROVED
```

---

# 56. Histórico

Não existe estratégia normal de:

```text
DELETE
```

ou overwrite de decisão.

Resultado:

```text
APPROVED
```

---

# 57. Revisão do domínio

Entidades:

```text
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
QuoteDecisionSubmission
QuoteDecision
PublicQuoteAccess
```

são suficientes para representar o problema atual.

Resultado:

```text
APPROVED WITH FINDINGS
```

---

# 58. Complexidade

A separação entre:

```text
QuoteRevision
```

e:

```text
QuoteItemRevision
```

adiciona complexidade.

Entretanto essa complexidade possui justificativa concreta:

```text
complementos;
histórico;
reuso de itens não alterados;
nova aprovação somente onde houve alteração.
```

Não é considerada abstração excessiva.

---

# 59. Revisão arquitetural

Monólito modular:

```text
RESPEITADO
```

---

# 60. Microservices

Não foram introduzidos.

Resultado:

```text
CORRETO
```

---

# 61. Comunicação entre módulos

Modelo prevê:

```text
contracts;
events;
outbox.
```

Não prevê acesso direto de Estoque ao repository de Quote.

Resultado:

```text
CORRETO
```

---

# 62. Outbox

Uso é proporcional ao problema futuro de efeitos intermodulares.

Não há necessidade de Kafka/RabbitMQ.

Resultado:

```text
CORRETO
```

---

# 63. Revisão de segurança

Token aleatório:

```text
CORRETO
```

---

# 64. Entropia

```text
256 bits
```

é adequada ao contexto.

---

# 65. Persistência somente do digest

```text
CORRETO
```

---

# 66. SHA-256 para token aleatório

Como token possui alta entropia:

```text
ACEITÁVEL
```

Não confundir com password hashing.

---

# 67. IDOR

Foi corretamente identificado como risco crítico.

Backend deve validar:

```text
token
→ revision
→ item.
```

Resultado:

```text
CORRETO
```

com ressalva do HIGH-01 de integridade estrutural.

---

# 68. Logs

Proibição de registrar:

```text
token bruto;
CPF/CNPJ completo;
payload integral.
```

Resultado:

```text
CORRETO
```

---

# 69. Evidências

São preservados conceitualmente:

```text
nome;
CPF/CNPJ;
timestamp do servidor;
IP;
User-Agent;
revisão;
decisões.
```

Resultado:

```text
CORRETO
```

---

# 70. Revisão do banco

Uso de:

```text
NUMERIC
TIMESTAMPTZ
INET
BYTEA
```

está tecnicamente coerente com PostgreSQL.

---

# 71. Money

Uso futuro de:

```text
BigDecimal
```

é obrigatório.

Resultado:

```text
CORRETO
```

---

# 72. Enum

Persistência textual em vez de ordinal:

```text
CORRETO
```

---

# 73. Decisão única

Constraint:

```text
UNIQUE(quote_item_revision_id)
```

é coerente com a regra atual de:

```text
uma decisão efetiva por versão.
```

---

# 74. Retratação

Não foi inventada.

Resultado:

```text
CORRETO
```

---

# 75. Idempotência

Modelo:

```text
publicQuoteAccessId
+
requestId
+
payloadDigest
```

é adequado.

---

# 76. Replay

Mesmo conteúdo:

```text
retorna resultado anterior.
```

Novo conteúdo com mesma chave:

```text
409.
```

Resultado:

```text
CORRETO
```

---

# 77. Canonicalização

Ordenar decisões antes do digest evita diferença artificial por ordem.

Resultado:

```text
CORRETO
```

---

# 78. Atomicidade

Uma submissão com múltiplos itens é:

```text
all-or-nothing.
```

Resultado:

```text
CORRETO
```

---

# 79. Concorrência

Estratégia:

```text
optimistic locking
+
constraints
+
transação
+
tratamento explícito de conflito
```

é adequada ao volume e arquitetura esperados.

---

# 80. Lock pessimista

Não introduzir prematuramente:

```text
CORRETO
```

---

# 81. Redis

Não introduzir apenas para idempotência:

```text
CORRETO
```

---

# 82. Revisão do backend

Separação:

```text
PublicQuoteController
InternalQuoteController
```

é adequada.

---

# 83. Controller

Regra de negócio permanece fora do controller.

Resultado:

```text
CORRETO
```

---

# 84. DTO

Não retornar JPA Entity diretamente:

```text
CORRETO
```

---

# 85. Mass assignment

DTO público dedicado:

```text
CORRETO
```

---

# 86. POST vazio

Decisão técnica:

```text
decisions[] vazio
→ 400
```

é coerente e não viola aprovação parcial.

Resultado:

```text
CORRETO
```

---

# 87. HTTP

Mapeamento geral está coerente:

```text
200
201
400
404
409
410
429
500
```

Nenhum finding impeditivo.

---

# 88. Revisão frontend

O frontend diferencia:

```text
seleção local
```

de:

```text
decisão persistida.
```

Resultado:

```text
CORRETO
```

---

# 89. Aprovação parcial no frontend

Item omitido não entra no request.

Resultado:

```text
CORRETO
```

---

# 90. Retry

Preservação de requestId no timeout:

```text
CORRETO E IMPORTANTE
```

---

# 91. Duas abas

Conflito seguido de refetch:

```text
CORRETO
```

---

# 92. XSS

Evitar:

```text
dangerouslySetInnerHTML
```

para conteúdo de negócio:

```text
CORRETO
```

---

# 93. LocalStorage

Evitar persistir token:

```text
CORRETO
```

---

# 94. Revisão QA

A matriz cobre:

```text
requisito;
domínio;
application;
PostgreSQL;
API;
segurança;
concorrência;
outbox;
auditoria;
frontend;
E2E;
arquitetura.
```

Resultado:

```text
COBERTURA DOCUMENTAL ADEQUADA
```

---

# 95. Testcontainers

PostgreSQL real:

```text
CORRETO
```

---

# 96. H2

Proibição de utilizar H2 como substituto dos testes PostgreSQL desta feature:

```text
CORRETA
```

---

# 97. Rastreabilidade

Existe caminho claro:

```text
TASK
↓
REQ
↓
DOMAIN
↓
ARCHITECTURE
↓
SECURITY
↓
DATABASE
↓
BACKEND
↓
FRONTEND
↓
QA
```

Resultado:

```text
CORRETO
```

---

# 98. Regras que não foram inventadas

Não foram adicionados indevidamente:

```text
pagamento;
comissão;
NFS-e;
WhatsApp;
reserva automática;
retratação;
assinatura digital ICP-Brasil.
```

Resultado:

```text
CORRETO
```

---

# 99. Regra fiscal

Nenhuma emissão fiscal foi associada à aprovação.

Resultado:

```text
CORRETO
```

---

# 100. Regra financeira

Aprovação não foi transformada em:

```text
pagamento;
recebimento;
liquidação;
conta a receber automática.
```

Resultado:

```text
CORRETO
```

---

# 101. Regra de comissão

Aprovação não gera comissão.

Resultado:

```text
CORRETO
```

---

# 102. Regra de estoque

Reserva automática não foi implementada nesta Task.

Resultado:

```text
CORRETO
```

---

# 103. Checklist funcional

- [x] requisito correspondente existe;
- [x] critérios de aceite existem;
- [x] fluxo principal está especificado;
- [x] fluxos alternativos estão especificados;
- [x] exceções relevantes foram consideradas;
- [ ] stale revision possui regra completa;
- [x] histórico é preservado;
- [x] aprovação parcial está preservada;
- [x] regra de produto não foi reinterpretada deliberadamente.

Resultado:

```text
NÃO APROVADO AINDA
```

devido ao HIGH-02.

---

# 104. Checklist arquitetural

- [x] módulo proprietário correto;
- [x] monólito modular respeitado;
- [x] repositories não são públicos entre módulos;
- [x] eventos possuem justificativa;
- [x] outbox possui justificativa;
- [x] sem microservices;
- [x] complexidade proporcional;
- [ ] integridade relacional cross-quote precisa ser fortalecida.

Resultado:

```text
NÃO APROVADO AINDA
```

devido ao HIGH-01.

---

# 105. Checklist backend

- [x] controller sem regra;
- [x] use cases definidos;
- [x] repositories encapsulados;
- [x] erros definidos;
- [x] transações claras;
- [x] DTO público dedicado;
- [x] segurança incorporada;
- [x] auditabilidade prevista;
- [x] idempotência prevista;
- [x] concorrência prevista.

Resultado documental:

```text
APROVADO COM DEPENDÊNCIA DOS FINDINGS ABERTOS.
```

---

# 106. Checklist banco

- [x] dinheiro decimal;
- [x] timestamps adequados;
- [x] IP adequado;
- [x] token digest;
- [x] constraints básicas;
- [x] decisão única;
- [x] idempotência;
- [x] histórico;
- [ ] integridade entre entidades do mesmo Quote.

Resultado:

```text
CHANGES REQUIRED
```

---

# 107. Checklist segurança

- [x] token forte;
- [x] digest;
- [x] expiração;
- [x] revogação;
- [x] IDOR;
- [x] minimização de dados;
- [x] logs;
- [x] XSS;
- [x] rate limiting previsto;
- [x] HTTPS previsto;
- [x] evidência;
- [x] auditoria;
- [ ] coerência exata das duas validades precisa ser definida.

Resultado:

```text
APROVADO COM AJUSTE
```

---

# 108. Checklist frontend

- [x] item individual;
- [x] pendência;
- [x] rejeição;
- [x] resumo;
- [x] aceite;
- [x] mobile;
- [x] acessibilidade;
- [x] timeout;
- [x] idempotência;
- [x] conflitos;
- [x] complemento;
- [x] reabertura;
- [ ] alinhar dados do cabeçalho público com API.

Resultado:

```text
APROVADO COM AJUSTE LOW
```

---

# 109. Checklist QA

- [x] domínio;
- [x] application;
- [x] PostgreSQL;
- [x] API;
- [x] segurança;
- [x] concorrência;
- [x] atomicidade;
- [x] outbox;
- [x] frontend;
- [x] E2E;
- [x] arquitetura.

Após resolução do HIGH-02:

```text
adicionar casos explícitos para stale por item.
```

---

# 110. Bloqueios

Bloqueiam encerramento:

```text
HIGH-01
HIGH-02
```

---

# 111. Não bloqueia sozinho

```text
MEDIUM-01
LOW-01
NOTE-01
```

Porém recomenda-se corrigir todos dentro da TASK-0001 para evitar dívida documental antes da implementação.

---

# 112. Decision Request necessária

Abrir:

```text
DR-0001 — Momento de obsolescência de versão comercial pendente
```

Questão:

```text
Quando uma nova versão comercial de um item é criada,
em que momento a versão anterior deixa de aceitar
nova decisão do cliente?
```

---

# 113. Handoff AG-15 → AG-00

```text
Task:
TASK-0001

Review status:
CHANGES_REQUESTED

Critical:
0

High:
2

Medium:
1

Low:
1

Notes:
1

Bloqueios:
- HIGH-01 integridade relacional;
- HIGH-02 regra de stale revision.

Decision Request:
DR-0001 necessária para HIGH-02.

AG-00 pode marcar DONE:
NÃO

Fluxo recomendado:
AG-00
↓
Decision Request
↓
AG-01 / AG-03
↓
AG-02
↓
AG-10
↓
AG-11
↓
AG-12 se necessário
↓
AG-13
↓
AG-15 re-review
↓
AG-00
```

---

# 114. Resultado do AG-15

```text
TASK:
TASK-0001

RESULT:
CHANGES_REQUESTED

REQUISITO:
MAJORITARIAMENTE CONSISTENTE

DOMÍNIO:
CONSISTENTE COM FINDING DE STALE

ARQUITETURA:
CONSISTENTE COM AJUSTES

SEGURANÇA:
CONSISTENTE COM AJUSTE DE VALIDADE

BANCO:
REQUER FORTALECIMENTO DE INTEGRIDADE

BACKEND:
COERENTE COM DEPENDÊNCIAS

FRONTEND:
COERENTE COM AJUSTE LOW

QA:
COBERTURA ADEQUADA

CRITICAL:
0

HIGH:
2

MEDIUM:
1

LOW:
1

DECISION REQUEST:
1

APTO PARA DONE:
NÃO
```

---

# 115. Definition of Done do AG-15 — primeira revisão

- [x] requisitos revisados;
- [x] domínio revisado;
- [x] arquitetura revisada;
- [x] segurança revisada;
- [x] banco revisado;
- [x] backend revisado;
- [x] frontend revisado;
- [x] QA revisado;
- [x] rastreabilidade revisada;
- [x] complexidade analisada;
- [x] riscos classificados;
- [x] findings documentados;
- [x] bloqueios identificados;
- [x] Decision Request identificada;
- [ ] findings HIGH resolvidos;
- [ ] re-review concluída;
- [ ] apto para DONE.

---

# 116. Conclusão

A arquitetura da aprovação parcial está próxima de ficar pronta para implementação.

Os principais fundamentos estão corretos:

```text
decisão por item;
versionamento;
histórico;
segurança;
idempotência;
atomicidade;
concorrência;
outbox;
frontend;
QA.
```

Entretanto o objetivo da revisão independente é impedir que lacunas conhecidas atravessem para o código.

Neste momento:

```text
TASK-0001
NÃO ESTÁ APTA PARA DONE.
```

Antes da conclusão devem ser resolvidos:

```text
1. integridade estrutural entre Quote,
   QuoteRevision, QuoteItemRevision,
   PublicQuoteAccess, Submission e Decision;

2. regra exata de obsolescência
   de uma versão comercial pendente.
```

**UMA REVISÃO TÉCNICA QUE APROVA TUDO SEM QUESTIONAR NÃO FUNCIONA COMO BARREIRA DE QUALIDADE.**