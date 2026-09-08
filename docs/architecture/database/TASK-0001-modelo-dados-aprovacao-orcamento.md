# Banco de Dados — TASK-0001 — Aprovação Parcial de Orçamento

## 1. Identificação

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

Documentos relacionados:

```text
docs/requirements/oficina/REQ-ORC-001-aprovacao-parcial.md

docs/domain/oficina/aprovacao-parcial-orcamento.md

docs/architecture/oficina/TASK-0001-aprovacao-parcial-orcamento.md

docs/architecture/security/TASK-0001-seguranca-aprovacao-publica.md

docs/api/TASK-0001-aprovacao-publica-orcamento.md

docs/architecture/review/TASK-0001-revisao-tecnica.md

decision-requests/DR-0001-obsolescencia-versao-comercial.md
```

Agente responsável:

```text
AG-10 — Banco de Dados
```

Status:

```text
DATA_REVISED
```

Revisão:

```text
REVISION 2
```

Banco:

```text
PostgreSQL
```

Migration:

```text
Flyway
```

Data:

```text
2026-09-08
```

---

# 2. Motivo desta revisão

A primeira versão do modelo dependia excessivamente da aplicação para garantir que:

```text
Quote
QuoteRevision
QuoteItem
QuoteItemRevision
PublicQuoteAccess
QuoteDecisionSubmission
QuoteDecision
```

pertencessem ao mesmo contexto de orçamento.

O AG-15 identificou o finding:

```text
HIGH-01
```

porque seria estruturalmente possível, por erro de implementação, relacionar:

```text
Revision do Quote A
→
ItemRevision do Quote B
```

ou:

```text
PublicQuoteAccess do Quote A
→
Revision do Quote B
```

ou:

```text
Submission da Revision A
→
Decision sobre item não apresentado na Revision A
```

Esta revisão fortalece a integridade referencial utilizando:

```text
quote_id redundante controlado
+
UNIQUE compostos
+
FOREIGN KEY compostas
```

---

# 3. Objetivo

O PostgreSQL deve ajudar a garantir estruturalmente:

```text
1. Revision pertence ao Quote correto;

2. Item pertence ao Quote correto;

3. ItemRevision pertence ao Item e Quote corretos;

4. RevisionItem somente relaciona entidades do mesmo Quote;

5. PublicQuoteAccess pertence exatamente à Revision correta;

6. Submission pertence exatamente ao PublicQuoteAccess
   e Revision corretos;

7. Decision somente pode apontar para ItemRevision
   efetivamente apresentada na Revision da Submission.
```

---

# 4. Princípio de defesa em profundidade

A aplicação continua validando todas as invariantes.

Porém:

```text
VALIDAÇÃO JAVA
+
INTEGRIDADE POSTGRESQL
```

é preferível a:

```text
VALIDAÇÃO JAVA APENAS
```

para relações estruturais críticas.

---

# 5. Banco único

A aplicação utilizará:

```text
PostgreSQL
```

com banco físico único no MVP.

---

# 6. Schema lógico

Schema recomendado:

```text
workshop
```

Exemplos:

```text
workshop.quote
workshop.quote_revision
workshop.quote_item
```

---

# 7. Fronteira modular

As tabelas pertencem ao módulo:

```text
Oficina / Quote
```

Outros módulos não devem acessar diretamente:

```text
repository;
JPA entity;
tabela;
schema interno
```

do módulo Oficina.

Comunicação intermodular ocorrerá através de:

```text
application contracts;
interfaces públicas;
eventos;
outbox.
```

---

# 8. Modelo físico consolidado

Tabelas da feature:

```text
workshop.quote

workshop.quote_revision

workshop.quote_item

workshop.quote_item_revision

workshop.quote_revision_item

workshop.public_quote_access

workshop.quote_decision_submission

workshop.quote_decision
```

Estruturas transversais:

```text
outbox
audit
```

---

# 9. Visão estrutural

```text
quote
│
├── quote_revision
│      │
│      └── quote_revision_item
│                │
│                └── quote_item_revision
│                         │
│                         └── quote_item
│
├── public_quote_access
│
└── quote_decision_submission
          │
          └── quote_decision
                   │
                   └── quote_revision_item
```

---

# 10. Identificadores

Recomendação:

```text
UUID
```

para identificadores principais.

UUID:

```text
não é segredo;
não substitui autorização;
não substitui token.
```

---

# 11. Valores monetários

PostgreSQL:

```text
NUMERIC
```

Java:

```text
BigDecimal
```

Nunca utilizar para dinheiro:

```text
REAL
FLOAT
DOUBLE PRECISION
double
float
```

Sugestão inicial:

```text
NUMERIC(19,4)
```

A política monetária global será consolidada antes da implementação definitiva.

---

# 12. Timestamps

Utilizar:

```text
TIMESTAMPTZ
```

para:

```text
created_at;
presented_at;
valid_until;
revoked_at;
occurred_at.
```

---

# 13. `quote`

Responsabilidade:

```text
identidade lógica do orçamento.
```

Campos:

```text
id
work_order_id
version
created_at
created_by
```

DDL conceitual:

```sql
CREATE TABLE workshop.quote (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL
);
```

Índice:

```sql
CREATE INDEX idx_quote_work_order
    ON workshop.quote(work_order_id);
```

Não criar:

```text
UNIQUE(work_order_id)
```

nesta Task.

---

# 14. `quote_revision`

Responsabilidade:

```text
representar uma apresentação global do orçamento.
```

Campos:

```text
id
quote_id
revision_number
status
presented_at
valid_until
created_at
created_by
version
```

Estados:

```text
DRAFT
PRESENTED
```

Não persistir:

```text
EXPIRED
```

como estado fixo.

Expiração comercial é derivada por:

```text
Clock
+
valid_until
```

---

# 15. Integridade composta da revisão

Além da PK:

```text
id
```

criar:

```text
UNIQUE(id, quote_id)
```

Isso permite que tabelas dependentes garantam simultaneamente:

```text
revision_id
+
quote_id
```

---

# 16. DDL — `quote_revision`

```sql
CREATE TABLE workshop.quote_revision (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    presented_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_quote_revision_quote
        FOREIGN KEY (quote_id)
        REFERENCES workshop.quote(id),

    CONSTRAINT uq_quote_revision_number
        UNIQUE (quote_id, revision_number),

    CONSTRAINT uq_quote_revision_id_quote
        UNIQUE (id, quote_id),

    CONSTRAINT ck_quote_revision_number
        CHECK (revision_number > 0),

    CONSTRAINT ck_quote_revision_status
        CHECK (status IN ('DRAFT', 'PRESENTED')),

    CONSTRAINT ck_quote_revision_presentation
        CHECK (
            (
                status = 'DRAFT'
            )
            OR
            (
                status = 'PRESENTED'
                AND presented_at IS NOT NULL
                AND valid_until IS NOT NULL
                AND valid_until >= presented_at
            )
        )
);
```

---

# 17. Validade comercial

Fonte de verdade da validade comercial:

```text
quote_revision.valid_until
```

Semântica:

```text
até quando aquela proposta apresentada
pode aceitar nova decisão comercial,
respeitadas as demais invariantes.
```

---

# 18. `quote_item`

Responsabilidade:

```text
identidade lógica permanente do item comercial.
```

Campos:

```text
id
quote_id
work_order_service_id
created_at
created_by
```

---

# 19. Integridade composta do item

Criar:

```text
UNIQUE(id, quote_id)
```

para permitir que uma versão de item demonstre estruturalmente:

```text
Item pertence ao Quote informado.
```

---

# 20. DDL — `quote_item`

```sql
CREATE TABLE workshop.quote_item (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    work_order_service_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,

    CONSTRAINT fk_quote_item_quote
        FOREIGN KEY (quote_id)
        REFERENCES workshop.quote(id),

    CONSTRAINT uq_quote_item_id_quote
        UNIQUE (id, quote_id)
);
```

Índices:

```sql
CREATE INDEX idx_quote_item_quote
    ON workshop.quote_item(quote_id);

CREATE INDEX idx_quote_item_work_order_service
    ON workshop.quote_item(work_order_service_id);
```

---

# 21. `quote_item_revision`

Responsabilidade:

```text
representar uma condição comercial específica
e historicamente imutável de um QuoteItem.
```

Campos:

```text
id
quote_id
quote_item_id
revision_sequence
description
quantity
unit_price
total_price
revision_reason
created_at
created_by
```

---

# 22. Por que `quote_id` aparece novamente

`quote_id` é redundante do ponto de vista lógico.

Porém é mantido deliberadamente para permitir:

```text
FOREIGN KEY composta
```

e impedir associações cross-quote.

A redundância é controlada pela FK:

```text
(quote_item_id, quote_id)
→
quote_item(id, quote_id)
```

Assim não é possível declarar:

```text
quote_item_id do Quote B
+
quote_id do Quote A.
```

---

# 23. Integridade da versão do item

Obrigatório:

```text
UNIQUE(id, quote_id)
```

e:

```text
UNIQUE(quote_item_id, revision_sequence)
```

---

# 24. DDL — `quote_item_revision`

```sql
CREATE TABLE workshop.quote_item_revision (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    quote_item_id UUID NOT NULL,
    revision_sequence INTEGER NOT NULL,
    description VARCHAR(1000) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    total_price NUMERIC(19,4) NOT NULL,
    revision_reason VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,

    CONSTRAINT fk_quote_item_revision_item_quote
        FOREIGN KEY (quote_item_id, quote_id)
        REFERENCES workshop.quote_item(id, quote_id),

    CONSTRAINT uq_quote_item_revision_sequence
        UNIQUE (quote_item_id, revision_sequence),

    CONSTRAINT uq_quote_item_revision_id_quote
        UNIQUE (id, quote_id),

    CONSTRAINT ck_quote_item_revision_sequence
        CHECK (revision_sequence > 0),

    CONSTRAINT ck_quote_item_revision_quantity
        CHECK (quantity > 0),

    CONSTRAINT ck_quote_item_revision_unit_price
        CHECK (unit_price >= 0),

    CONSTRAINT ck_quote_item_revision_total_price
        CHECK (total_price >= 0)
);
```

---

# 25. Snapshot comercial

Depois que uma `QuoteItemRevision` for apresentada:

não atualizar diretamente:

```text
description;
quantity;
unit_price;
total_price.
```

Alteração comercial cria:

```text
nova QuoteItemRevision.
```

---

# 26. Alterações que criam nova versão

Conforme requisito aprovado:

```text
preço;
descrição;
quantidade.
```

---

# 27. Alterações internas

Mudanças como:

```text
técnico;
fornecedor;
custo;
dados operacionais internos
```

não criam nova versão comercial apenas por si mesmas.

---

# 28. DR-0001 — regra oficial

Decisão:

```text
OPÇÃO B
```

Uma nova `QuoteItemRevision` em:

```text
DRAFT
```

não invalida automaticamente a versão anteriormente apresentada.

---

# 29. Momento de substituição comercial

A versão anterior deixa de aceitar nova decisão quando:

```text
uma nova QuoteItemRevision
do MESMO QuoteItem
for efetivamente PRESENTED.
```

---

# 30. Complemento

Exemplo válido:

```text
R1
A-v1

R2
A-v1
B-v1
```

Nesse caso:

```text
A-v1
```

não se torna obsoleta simplesmente porque existe:

```text
R2.
```

---

# 31. Não criar `is_stale`

Não persistir inicialmente coluna:

```text
is_stale
```

ou:

```text
current = true/false
```

porque seria estado derivável e sujeito a inconsistência.

---

# 32. Determinação de obsolescência

Para uma `QuoteItemRevision`, o backend deve verificar se existe:

```text
outra QuoteItemRevision do mesmo QuoteItem
com revision_sequence superior
que tenha sido efetivamente incluída
em uma QuoteRevision PRESENTED.
```

Se existir:

```text
versão anterior não aceita nova decisão.
```

---

# 33. Histórico não muda

Se A-v1 foi aprovada antes da apresentação de A-v2:

```text
A-v1 continua APPROVED historicamente.
```

A apresentação de A-v2 não altera decisão anterior.

---

# 34. `quote_revision_item`

Responsabilidade:

```text
registrar quais versões comerciais foram efetivamente
incluídas em determinada apresentação global.
```

Campos:

```text
quote_revision_id
quote_item_revision_id
quote_id
display_order
```

---

# 35. Integridade cross-quote

Esta tabela é o principal fechamento do `HIGH-01`.

Ela deve possuir simultaneamente:

```text
(quote_revision_id, quote_id)
→ quote_revision

e

(quote_item_revision_id, quote_id)
→ quote_item_revision
```

Portanto é impossível inserir:

```text
Revision do Quote A
+
ItemRevision do Quote B
```

com um único `quote_id` válido.

---

# 36. DDL — `quote_revision_item`

```sql
CREATE TABLE workshop.quote_revision_item (
    quote_revision_id UUID NOT NULL,
    quote_item_revision_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    display_order INTEGER NOT NULL,

    PRIMARY KEY (
        quote_revision_id,
        quote_item_revision_id
    ),

    CONSTRAINT fk_quote_revision_item_revision_quote
        FOREIGN KEY (quote_revision_id, quote_id)
        REFERENCES workshop.quote_revision(id, quote_id),

    CONSTRAINT fk_quote_revision_item_item_revision_quote
        FOREIGN KEY (quote_item_revision_id, quote_id)
        REFERENCES workshop.quote_item_revision(id, quote_id),

    CONSTRAINT uq_quote_revision_item_identity
        UNIQUE (
            quote_revision_id,
            quote_item_revision_id,
            quote_id
        ),

    CONSTRAINT uq_quote_revision_display_order
        UNIQUE (
            quote_revision_id,
            display_order
        ),

    CONSTRAINT ck_quote_revision_display_order
        CHECK (display_order > 0)
);
```

---

# 37. Resultado da proteção

Agora o PostgreSQL rejeita estruturalmente:

```text
Revision Q-A
+
ItemRevision Q-B
```

mesmo que o Java possua bug.

---

# 38. `public_quote_access`

Responsabilidade:

```text
credencial pública limitada a uma revisão específica.
```

Campos:

```text
id
quote_id
quote_revision_id
token_digest
created_at
valid_until
revoked_at
created_by
```

---

# 39. Token

Token bruto:

```text
NÃO PERSISTIR
```

Persistir somente:

```text
token_digest
```

---

# 40. Tipo do digest

SHA-256:

```text
32 bytes
```

Persistência:

```text
BYTEA
```

---

# 41. Integridade Access → QuoteRevision

Utilizar:

```text
(quote_revision_id, quote_id)
```

como FK composta.

Dessa forma:

```text
PublicQuoteAccess do Quote A
```

não consegue apontar para:

```text
Revision do Quote B.
```

---

# 42. Chave composta auxiliar

Criar:

```text
UNIQUE(id, quote_revision_id, quote_id)
```

para permitir que Submission referencie exatamente:

```text
Access
+
Revision
+
Quote
```

---

# 43. DDL — `public_quote_access`

```sql
CREATE TABLE workshop.public_quote_access (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL,
    quote_revision_id UUID NOT NULL,
    token_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_by UUID NOT NULL,

    CONSTRAINT fk_public_quote_access_revision_quote
        FOREIGN KEY (quote_revision_id, quote_id)
        REFERENCES workshop.quote_revision(id, quote_id),

    CONSTRAINT uq_public_quote_access_token_digest
        UNIQUE (token_digest),

    CONSTRAINT uq_public_quote_access_scope
        UNIQUE (
            id,
            quote_revision_id,
            quote_id
        ),

    CONSTRAINT ck_public_quote_access_validity
        CHECK (valid_until >= created_at),

    CONSTRAINT ck_public_quote_access_revocation
        CHECK (
            revoked_at IS NULL
            OR revoked_at >= created_at
        )
);
```

---

# 44. Duas validades

Esta revisão também esclarece o finding `MEDIUM-01`.

Existem dois conceitos distintos.

### Validade comercial

```text
quote_revision.valid_until
```

Significa:

```text
prazo da proposta comercial.
```

### Validade da credencial pública

```text
public_quote_access.valid_until
```

Significa:

```text
prazo máximo de utilização daquele token.
```

---

# 45. Relação entre as validades

Regra técnica:

```text
PublicQuoteAccess.validUntil
não pode ampliar
QuoteRevision.validUntil.
```

Portanto:

```text
publicAccess.validUntil
<=
quoteRevision.validUntil
```

---

# 46. Fonte de verdade para decisão

Para aceitar um POST:

```text
QuoteRevision precisa estar comercialmente válida
E
PublicQuoteAccess precisa estar válido e não revogado
E
QuoteItemRevision precisa continuar decidível.
```

---

# 47. Constraint cross-table de validade

PostgreSQL `CHECK` não deve consultar outra tabela.

Portanto a relação:

```text
PublicQuoteAccess.validUntil
<=
QuoteRevision.validUntil
```

será validada pela aplicação durante criação do acesso.

Não criar trigger apenas para isso.

---

# 48. `quote_decision_submission`

Responsabilidade:

```text
representar uma submissão pública atômica.
```

Campos:

```text
id
public_quote_access_id
quote_revision_id
quote_id
request_id
request_payload_digest
customer_name
document_type
document_number
explicit_acceptance
occurred_at
ip_address
user_agent
created_at
```

---

# 49. Escopo estrutural da Submission

A Submission deve apontar exatamente para o triplo:

```text
PublicQuoteAccess
+
QuoteRevision
+
Quote
```

que foi autorizado.

---

# 50. FK composta

Utilizar:

```text
(
    public_quote_access_id,
    quote_revision_id,
    quote_id
)
```

referenciando:

```text
public_quote_access(
    id,
    quote_revision_id,
    quote_id
)
```

Isso impede:

```text
Access de R1
+
Submission declarando R2.
```

---

# 51. Idempotência

Chave:

```text
(public_quote_access_id, request_id)
```

---

# 52. Payload digest

Persistir:

```text
request_payload_digest BYTEA
```

para detectar:

```text
mesmo requestId
+
conteúdo diferente.
```

---

# 53. Evidências

Persistir snapshot de:

```text
nome;
CPF/CNPJ;
aceite;
timestamp servidor;
IP;
User-Agent.
```

---

# 54. IP

PostgreSQL:

```text
INET
```

Suporta:

```text
IPv4
IPv6
```

---

# 55. DDL — `quote_decision_submission`

```sql
CREATE TABLE workshop.quote_decision_submission (
    id UUID PRIMARY KEY,
    public_quote_access_id UUID NOT NULL,
    quote_revision_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    request_payload_digest BYTEA NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    document_type VARCHAR(10) NOT NULL,
    document_number VARCHAR(14) NOT NULL,
    explicit_acceptance BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    ip_address INET NOT NULL,
    user_agent VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_quote_submission_access_scope
        FOREIGN KEY (
            public_quote_access_id,
            quote_revision_id,
            quote_id
        )
        REFERENCES workshop.public_quote_access(
            id,
            quote_revision_id,
            quote_id
        ),

    CONSTRAINT uq_quote_submission_request
        UNIQUE (
            public_quote_access_id,
            request_id
        ),

    CONSTRAINT uq_quote_submission_scope
        UNIQUE (
            id,
            quote_revision_id,
            quote_id
        ),

    CONSTRAINT ck_quote_submission_document_type
        CHECK (
            document_type IN ('CPF', 'CNPJ')
        ),

    CONSTRAINT ck_quote_submission_document_length
        CHECK (
            (
                document_type = 'CPF'
                AND char_length(document_number) = 11
            )
            OR
            (
                document_type = 'CNPJ'
                AND char_length(document_number) = 14
            )
        ),

    CONSTRAINT ck_quote_submission_acceptance
        CHECK (explicit_acceptance = TRUE)
);
```

---

# 56. Documento

O banco valida apenas estrutura básica:

```text
CPF → 11 caracteres
CNPJ → 14 caracteres
```

Não implementar algoritmo de dígito verificador via SQL.

---

# 57. `quote_decision`

Responsabilidade:

```text
representar uma decisão individual consolidada.
```

Campos:

```text
id
submission_id
quote_revision_id
quote_item_revision_id
quote_id
decision_type
occurred_at
```

---

# 58. Por que a Decision contém Revision e Quote

Esses campos permitem que o PostgreSQL garanta:

```text
Submission
e
ItemRevision apresentado
```

dentro do mesmo contexto.

---

# 59. Integridade Decision → Submission

FK:

```text
(
    submission_id,
    quote_revision_id,
    quote_id
)
```

para:

```text
quote_decision_submission
```

---

# 60. Integridade Decision → RevisionItem

A Decision deve também referenciar:

```text
(
    quote_revision_id,
    quote_item_revision_id,
    quote_id
)
```

para:

```text
quote_revision_item
```

Com isso o banco garante:

```text
o item realmente fazia parte
da revisão daquela submissão.
```

---

# 61. DDL — `quote_decision`

```sql
CREATE TABLE workshop.quote_decision (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL,
    quote_revision_id UUID NOT NULL,
    quote_item_revision_id UUID NOT NULL,
    quote_id UUID NOT NULL,
    decision_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_quote_decision_submission_scope
        FOREIGN KEY (
            submission_id,
            quote_revision_id,
            quote_id
        )
        REFERENCES workshop.quote_decision_submission(
            id,
            quote_revision_id,
            quote_id
        ),

    CONSTRAINT fk_quote_decision_presented_item
        FOREIGN KEY (
            quote_revision_id,
            quote_item_revision_id,
            quote_id
        )
        REFERENCES workshop.quote_revision_item(
            quote_revision_id,
            quote_item_revision_id,
            quote_id
        ),

    CONSTRAINT uq_quote_decision_item_revision
        UNIQUE (quote_item_revision_id),

    CONSTRAINT ck_quote_decision_type
        CHECK (
            decision_type IN ('APPROVE', 'REJECT')
        )
);
```

---

# 62. Resultado da nova FK

Agora o banco rejeita:

```text
Submission de R1
+
ItemRevision não presente em R1.
```

Mesmo se:

```text
o item pertencer ao mesmo Quote.
```

---

# 63. Uma decisão por versão comercial

Constraint:

```text
UNIQUE(quote_item_revision_id)
```

continua válida no escopo atual.

Portanto:

```text
A-v1 APPROVE
```

e:

```text
A-v1 REJECT
```

não podem coexistir como fatos efetivos.

---

# 64. Pending

Não inserir:

```text
PENDING
```

em `quote_decision`.

Ausência de registro significa:

```text
PENDING_APPROVAL.
```

---

# 65. Retratação

Não existe nesta Task.

Não remover:

```text
UNIQUE(quote_item_revision_id)
```

sem nova decisão de produto.

---

# 66. Modelo corrigido completo

```text
quote
│
├── quote_revision
│      │
│      └── quote_revision_item
│              │
│              └── quote_item_revision
│                      │
│                      └── quote_item
│
├── public_quote_access
│       │
│       └── quote_revision
│
└── quote_decision_submission
        │
        └── quote_decision
               │
               └── quote_revision_item
```

Todos compartilham:

```text
quote_id
```

onde necessário para proteção composta.

---

# 67. Exemplo válido

```text
Quote A

Revision A1

Item A

ItemRevision A-v1
```

Relacionamento:

```text
quote_revision_item
quote_id = A
revision = A1
itemRevision = A-v1
```

Resultado:

```text
ACEITO
```

---

# 68. Exemplo inválido cross-quote

```text
Quote A
Revision A1

Quote B
ItemRevision B-v1
```

Tentativa:

```text
quote_revision_item
quote_id = A
revision = A1
itemRevision = B-v1
```

Resultado:

```text
FOREIGN KEY VIOLATION
```

---

# 69. Public access inválido

Tentativa:

```text
quote_id = Quote A
quote_revision_id = Revision do Quote B
```

Resultado:

```text
FOREIGN KEY VIOLATION
```

---

# 70. Submission inválida

Acesso:

```text
Quote A / R1
```

Submission tenta:

```text
Quote A / R2
```

Resultado:

```text
FOREIGN KEY VIOLATION
```

---

# 71. Decision inválida

Submission:

```text
R1
```

Item:

```text
A-v2
```

mas R1 apresentou somente:

```text
A-v1
```

Resultado:

```text
FOREIGN KEY VIOLATION
```

---

# 72. Aprovação parcial

Exemplo:

```text
R1
├── A-v1
├── B-v1
└── C-v1
```

Submission:

```text
A-v1 APPROVE
B-v1 REJECT
```

Banco:

```text
2 QuoteDecision
```

C-v1:

```text
sem QuoteDecision
=
PENDING_APPROVAL
```

---

# 73. Complemento

Revisão 1:

```text
R1
├── A-v1
└── B-v1
```

A-v1 aprovado.

Nova revisão:

```text
R2
├── A-v1
├── B-v1
└── C-v1
```

Resultado:

```text
A-v1 continua aprovado;
C-v1 continua pendente.
```

---

# 74. Alteração comercial

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

Enquanto A-v2 estiver somente em DRAFT:

```text
A-v1 continua decidível,
se ainda cumprir validade e demais regras.
```

---

# 75. Apresentação da nova versão

Quando uma Revision contendo:

```text
A-v2
```

for marcada:

```text
PRESENTED
```

então:

```text
A-v1 deixa de aceitar nova decisão.
```

---

# 76. A-v1 já aprovada

Se A-v1 já tinha:

```text
APPROVE
```

antes da nova apresentação:

essa decisão permanece histórica e válida.

Não é apagada.

---

# 77. Estado efetivo

Não persistir inicialmente:

```text
quote_item.current_status
```

ou:

```text
quote_item_revision.decision_status
```

O estado comercial é derivado de:

```text
QuoteItemRevision
+
QuoteDecision
+
regra de obsolescência quando ainda pendente.
```

---

# 78. Idempotência — primeira chamada

Entrada:

```text
access P1
requestId ABC
payloadDigest X
```

Resultado:

```text
nova Submission.
```

---

# 79. Replay

Mesmos:

```text
P1
ABC
X
```

Resultado:

```text
carregar Submission anterior;
não criar novo efeito.
```

---

# 80. Idempotency conflict

Mesmo:

```text
P1
ABC
```

com:

```text
payloadDigest Y
```

Resultado:

```text
CONFLICT.
```

---

# 81. Concorrência de idempotência

Constraint:

```text
UNIQUE(public_quote_access_id, request_id)
```

é a proteção final.

---

# 82. Concorrência de decisão

Constraint:

```text
UNIQUE(quote_item_revision_id)
```

protege:

```text
APPROVE x APPROVE

APPROVE x REJECT
```

---

# 83. Concorrência DR-0001

Cenário crítico:

```text
Thread A:
cliente decide A-v1

Thread B:
gerente apresenta A-v2
```

Esse cenário exige coordenação transacional no backend.

---

# 84. Estratégia para concorrência de apresentação

AG-11 deverá garantir que:

```text
apresentar nova versão comercial
```

e:

```text
registrar decisão da versão anterior
```

não produzam estado contraditório.

---

# 85. Lock recomendado

O caso de uso poderá utilizar:

```text
optimistic locking
```

sobre agregado apropriado e/ou serialização das alterações do mesmo:

```text
QuoteItem
```

A técnica definitiva será validada durante implementação com testes concorrentes.

---

# 86. Invariante de concorrência

Depois do commit final não pode existir situação em que:

```text
A-v2 foi efetivamente apresentada primeiro
```

e depois:

```text
A-v1 recebeu nova decisão.
```

---

# 87. DRAFT concorrente

A criação de:

```text
A-v2 DRAFT
```

por si só não impede decisão de A-v1.

Isso decorre diretamente da:

```text
DR-0001.
```

---

# 88. Atomicidade

Uma Submission com:

```text
A APPROVE
B REJECT
```

ocorre em:

```text
uma única transação.
```

---

# 89. Falha de B

Se B falhar:

```text
ROLLBACK
```

de:

```text
Submission;
Decision A;
Decision B;
Outbox events;
auditoria transacional relacionada.
```

---

# 90. Outbox

Eventos produzidos somente após fatos válidos:

```text
QuoteItemApproved
QuoteItemRejected
```

Persistidos na mesma transação.

---

# 91. Dados proibidos no evento intermodular

Não incluir sem necessidade:

```text
token;
tokenDigest;
CPF/CNPJ;
IP;
User-Agent;
nome completo.
```

---

# 92. Histórico

Operações históricas não devem utilizar:

```text
DELETE
```

como fluxo normal.

---

# 93. ON DELETE

Preferir:

```text
NO ACTION
```

ou:

```text
RESTRICT
```

nas relações históricas.

---

# 94. Cascade destrutivo

Não utilizar automaticamente:

```text
ON DELETE CASCADE
```

em:

```text
quote_revision;
quote_item_revision;
quote_decision_submission;
quote_decision.
```

---

# 95. Imutabilidade

Não criar CRUD administrativo para alterar diretamente:

```text
QuoteDecision.
```

---

# 96. Reabertura

Reabertura de item rejeitado:

```text
não altera QuoteDecision anterior.
```

Cria:

```text
nova QuoteItemRevision.
```

---

# 97. Índices

Obrigatórios ou candidatos:

```sql
CREATE INDEX idx_quote_work_order
    ON workshop.quote(work_order_id);

CREATE INDEX idx_quote_revision_quote
    ON workshop.quote_revision(quote_id);

CREATE INDEX idx_quote_item_quote
    ON workshop.quote_item(quote_id);

CREATE INDEX idx_quote_item_work_order_service
    ON workshop.quote_item(work_order_service_id);

CREATE INDEX idx_quote_item_revision_item
    ON workshop.quote_item_revision(quote_item_id);

CREATE INDEX idx_public_quote_access_revision
    ON workshop.public_quote_access(quote_revision_id);

CREATE INDEX idx_quote_submission_revision
    ON workshop.quote_decision_submission(quote_revision_id);

CREATE INDEX idx_quote_submission_occurred_at
    ON workshop.quote_decision_submission(occurred_at);

CREATE INDEX idx_quote_decision_submission
    ON workshop.quote_decision(submission_id);
```

As constraints `UNIQUE` já criam índices correspondentes.

---

# 98. Não indexar inicialmente

Sem caso de consulta real:

```text
customer_name;
document_number;
user_agent;
ip_address;
description.
```

---

# 99. JSONB

Não usar JSONB para substituir:

```text
QuoteRevision;
QuoteItemRevision;
Decision;
Submission.
```

---

# 100. Payload bruto

Não é necessário armazenar request completo.

Persistir:

```text
request_payload_digest
```

e os fatos normalizados relevantes.

---

# 101. Enum PostgreSQL

Preferência inicial:

```text
VARCHAR + CHECK
```

em vez de:

```text
CREATE TYPE ENUM
```

para facilitar evolução do domínio.

---

# 102. Enum Java

Persistir string.

Nunca:

```text
ordinal.
```

---

# 103. Testes PostgreSQL obrigatórios

Utilizar:

```text
Testcontainers
+
PostgreSQL
```

Não utilizar H2 como substituto.

---

# 104. DB-01 — cross-quote RevisionItem

Criar:

```text
Revision do Quote A
ItemRevision do Quote B
```

Tentar relacionar.

Esperado:

```text
FOREIGN KEY VIOLATION.
```

---

# 105. DB-02 — PublicAccess cross-quote

```text
quote_id A
revision_id B
```

Esperado:

```text
FOREIGN KEY VIOLATION.
```

---

# 106. DB-03 — Submission em revisão diferente do Access

Access:

```text
R1
```

Submission:

```text
R2
```

Esperado:

```text
FOREIGN KEY VIOLATION.
```

---

# 107. DB-04 — Decision em item não apresentado

Submission:

```text
R1
```

ItemRevision:

```text
não presente em R1
```

Esperado:

```text
FOREIGN KEY VIOLATION.
```

---

# 108. DB-05 — revision_number duplicado

Esperado:

```text
UNIQUE violation.
```

---

# 109. DB-06 — revision_sequence duplicada

Esperado:

```text
UNIQUE violation.
```

---

# 110. DB-07 — token digest duplicado

Esperado:

```text
UNIQUE violation.
```

---

# 111. DB-08 — requestId duplicado no mesmo Access

Esperado:

```text
UNIQUE violation.
```

---

# 112. DB-09 — requestId em Access diferente

Esperado:

```text
permitido.
```

---

# 113. DB-10 — decisão duplicada

Duas decisões para mesma:

```text
QuoteItemRevision
```

Esperado:

```text
UNIQUE violation.
```

---

# 114. DB-11 — approve/reject simultâneo

Esperado:

```text
uma única decisão efetiva.
```

---

# 115. DB-12 — quantidade inválida

```text
quantity = 0
```

Esperado:

```text
CHECK violation.
```

---

# 116. DB-13 — preço negativo

Esperado:

```text
CHECK violation.
```

---

# 117. DB-14 — CPF estruturalmente incorreto

Esperado:

```text
CHECK violation.
```

---

# 118. DB-15 — CNPJ estruturalmente incorreto

Esperado:

```text
CHECK violation.
```

---

# 119. DB-16 — aceite falso

Esperado:

```text
CHECK violation.
```

---

# 120. DB-17 — PRESENTED sem datas

Esperado:

```text
CHECK violation.
```

---

# 121. DB-18 — validade comercial inválida

```text
valid_until < presented_at
```

Esperado:

```text
CHECK violation.
```

---

# 122. DB-19 — IPv4

Persistência:

```text
sucesso.
```

---

# 123. DB-20 — IPv6

Persistência:

```text
sucesso.
```

---

# 124. DB-21 — atomicidade

Falha em uma Decision.

Esperado:

```text
zero Submission;
zero Decisions;
zero Outbox events.
```

---

# 125. DB-22 — complemento

R1:

```text
A-v1
```

R2:

```text
A-v1
B-v1
```

Esperado:

```text
estrutura válida.
```

---

# 126. DB-23 — DRAFT não invalida

A-v1 apresentada e pendente.

Criar:

```text
A-v2
```

somente em DRAFT.

Esperado:

```text
nenhuma alteração destrutiva em A-v1;
nenhuma Decision criada;
histórico preservado.
```

---

# 127. DB-24 — PRESENTED preserva versões

Apresentar A-v2.

Esperado:

```text
A-v1 continua no banco;
A-v2 continua no banco;
nenhuma linha histórica removida.
```

A capacidade de decisão de A-v1 será bloqueada pelo caso de uso conforme DR-0001.

---

# 128. Invariantes protegidas diretamente pelo PostgreSQL

```text
QuoteRevision pertence a Quote existente;

QuoteItem pertence a Quote existente;

QuoteItemRevision pertence simultaneamente
ao QuoteItem e Quote corretos;

QuoteRevisionItem somente associa Revision e
ItemRevision do mesmo Quote;

PublicQuoteAccess somente aponta para Revision
do Quote correto;

Submission somente aponta para a Revision
exatamente autorizada pelo PublicQuoteAccess;

Decision somente aponta para ItemRevision
efetivamente presente na Revision da Submission;

revision_number único por Quote;

revision_sequence única por QuoteItem;

tokenDigest único;

requestId único por PublicQuoteAccess;

uma Decision efetiva por QuoteItemRevision;

quantity positiva;

preços não negativos;

documentType válido;

estrutura CPF/CNPJ coerente;

explicitAcceptance true;

status de Revision válido;

datas de apresentação coerentes.
```

---

# 129. Invariantes da aplicação/domínio

Continuam no Java:

```text
token está válido;

token não está revogado;

QuoteRevision não expirou;

PublicQuoteAccess não expirou;

PublicQuoteAccess.validUntil não ultrapassa
QuoteRevision.validUntil;

QuoteItemRevision ainda está decidível;

DR-0001 é respeitada;

alteração comercial cria nova versão;

alteração interna não cria nova versão comercial;

reabertura cria nova oportunidade;

payload idempotente é canonicalizado;

mesmo requestId com conteúdo diferente gera conflito;

clock do servidor governa validade.
```

---

# 130. Por que nem tudo vira constraint

Algumas regras dependem de:

```text
Clock;
ordem temporal;
estado agregado;
eventos concorrentes;
semântica de negócio.
```

Transformá-las em triggers complexas esconderia regra de domínio dentro do banco.

---

# 131. Triggers

Não criar triggers complexas nesta fase.

---

# 132. Locking

Estratégia geral:

```text
transação PostgreSQL
+
optimistic locking
+
constraints
+
tratamento explícito de conflitos.
```

---

# 133. Lock pessimista

Não é padrão inicial.

Avaliar apenas se testes reais mostrarem necessidade.

---

# 134. Redis

Não utilizar Redis para:

```text
idempotência;
lock;
decisão.
```

nesta feature.

PostgreSQL já é a autoridade transacional.

---

# 135. Particionamento

Não necessário.

---

# 136. Sharding

Não necessário.

---

# 137. Read replica

Não necessária no MVP.

---

# 138. Migration

Este documento ainda é especificação.

Não criar migration Flyway nesta fase.

A implementação futura utilizará nome semelhante a:

```text
Vxxx__create_quote_approval_tables.sql
```

A numeração dependerá do projeto real.

---

# 139. Ordem futura de migration

As tabelas deverão ser criadas em ordem compatível com FKs.

Exemplo:

```text
1. quote

2. quote_revision

3. quote_item

4. quote_item_revision

5. quote_revision_item

6. public_quote_access

7. quote_decision_submission

8. quote_decision
```

---

# 140. Rollback de migration

Não criar migration destrutiva improvisada.

Flyway migrations em produção devem ser tratadas como:

```text
forward-only
```

com correções através de novas migrations quando aplicável.

---

# 141. Dados pessoais

`quote_decision_submission` contém:

```text
nome;
CPF/CNPJ;
IP;
User-Agent.
```

Acesso deve ser controlado pelo backend.

---

# 142. Token bruto

Nenhuma tabela possui:

```text
raw_token;
plain_token;
public_link.
```

---

# 143. Logs SQL

Configuração de produção não deve expor:

```text
token;
CPF/CNPJ;
payload;
dados sensíveis
```

através de logging indiscriminado de parâmetros SQL.

---

# 144. Exclusão

Não existirão operações normais como:

```sql
DELETE FROM workshop.quote_decision;
```

para alterar histórico comercial.

---

# 145. Auditoria

Auditoria transversal complementa, mas não substitui:

```text
QuoteDecisionSubmission
+
QuoteDecision
+
versionamento comercial.
```

---

# 146. Fechamento do HIGH-01

Finding:

```text
HIGH-01 — Integridade relacional insuficiente
```

Correção:

```text
IMPLEMENTADA NO DESENHO
```

Proteções adicionadas:

```text
quote_revision
UNIQUE(id, quote_id)

quote_item
UNIQUE(id, quote_id)

quote_item_revision
quote_id +
FK composta para quote_item

quote_revision_item
quote_id +
duas FKs compostas

public_quote_access
FK composta para quote_revision

quote_decision_submission
FK composta para PublicQuoteAccess + Revision + Quote

quote_decision
FK composta para Submission
+
FK composta para RevisionItem apresentado
```

Resultado esperado:

```text
HIGH-01 READY_FOR_REREVIEW
```

---

# 147. Tratamento do MEDIUM-01

Finding:

```text
MEDIUM-01 — Duas validades sem semântica clara
```

Definição:

```text
QuoteRevision.validUntil
=
validade comercial

PublicQuoteAccess.validUntil
=
validade da credencial
```

Regra:

```text
PublicQuoteAccess.validUntil
<=
QuoteRevision.validUntil
```

POST exige:

```text
ambos válidos.
```

Resultado:

```text
MEDIUM-01 READY_FOR_REREVIEW
```

---

# 148. DR-0001

Regra de stale definida:

```text
DRAFT não invalida versão apresentada.

Nova QuoteItemRevision do mesmo QuoteItem
passa a substituir a anterior para novas decisões
quando for PRESENTED.

Complemento reutilizando a mesma QuoteItemRevision
não invalida aquela versão.
```

Resultado:

```text
HIGH-02 — PARTE DE BANCO/DOMÍNIO DE DADOS ATUALIZADA
```

Os demais documentos ainda precisam incorporar a decisão.

---

# 149. Handoff AG-10 → AG-11

```text
Task:
TASK-0001

Status:
DATA_REVISED

Finding HIGH-01:
CORRIGIDO NO MODELO

Integridade:
- Revision ↔ Quote por FK composta;
- ItemRevision ↔ Item ↔ Quote por FK composta;
- RevisionItem impede cross-quote;
- PublicAccess ↔ Revision ↔ Quote por FK composta;
- Submission ↔ Access ↔ Revision ↔ Quote por FK composta;
- Decision ↔ Submission;
- Decision ↔ item efetivamente apresentado.

DR-0001:
- DRAFT não invalida;
- nova ItemRevision PRESENTED invalida a anterior
  para novas decisões;
- complemento com mesma ItemRevision não invalida.

Validade:
QuoteRevision.validUntil = comercial.
PublicQuoteAccess.validUntil = credencial.
Access nunca amplia validade comercial.

Concorrência crítica:
Decision antiga x apresentação da nova versão.

AG-11 deve atualizar:
- caso de uso de apresentação;
- caso de uso de decisão;
- tratamento stale;
- testes concorrentes.
```

---

# 150. Handoff AG-10 → AG-13

```text
Adicionar testes:

DB cross-quote;
Access cross-quote;
Submission revision mismatch;
Decision item não apresentado;
DRAFT não invalida;
PRESENTED preserva histórico;
concorrência Decision antiga x nova apresentação;
duas validades.
```

---

# 151. Handoff AG-10 → AG-15

```text
Finding:
HIGH-01

Status:
READY_FOR_REREVIEW

Solução:
integridade referencial composta adicionada.

Finding:
MEDIUM-01

Status:
READY_FOR_REREVIEW

Solução:
semântica das duas validades explicitada.

DR-0001:
incorporada ao desenho de dados.

Migration:
não criada porque TASK-0001 continua documental.
```

---

# 152. Resultado do AG-10

```text
TASK:
TASK-0001

STATUS:
DATA_REVISED

REVISION:
2

DATABASE:
PostgreSQL

HIGH-01:
CORRIGIDO

MEDIUM-01:
CORRIGIDO NO ESCOPO DE DADOS

DR-0001:
INCORPORADA

CROSS-QUOTE:
PROTEGIDO POR FK COMPOSTA

TOKEN BRUTO:
NÃO PERSISTIDO

TOKEN DIGEST:
BYTEA / UNIQUE

IDEMPOTÊNCIA:
MODELADA

ATOMICIDADE:
MODELADA

CONCORRÊNCIA:
MODELADA

HISTÓRICO:
PRESERVADO

OUTBOX:
TRANSACIONAL

MIGRATION:
NÃO CRIADA

PRONTO PARA AG-11:
SIM

PRONTO PARA REREVIEW AG-15:
APÓS PROPAGAÇÃO DA DR-0001 NOS DEMAIS DOCUMENTOS
```

---

# 153. Definition of Done — revisão AG-10

- [x] modelo anterior revisado;
- [x] finding HIGH-01 analisado;
- [x] quote_id composto introduzido onde necessário;
- [x] Revision protegida por Quote;
- [x] ItemRevision protegida por Item e Quote;
- [x] RevisionItem protegida contra cross-quote;
- [x] PublicQuoteAccess protegido por Revision e Quote;
- [x] Submission vinculada exatamente ao Access;
- [x] Decision vinculada exatamente à Submission;
- [x] Decision vinculada a item apresentado;
- [x] idempotência preservada;
- [x] atomicidade preservada;
- [x] decisão única preservada;
- [x] token protegido;
- [x] validades diferenciadas;
- [x] DR-0001 incorporada;
- [x] histórico preservado;
- [x] testes adicionais definidos;
- [x] nenhuma migration criada prematuramente.

---

# 154. Regra final

O banco não deve apenas verificar:

```text
"esse ID existe?"
```

Ele deve conseguir verificar, sempre que estruturalmente viável:

```text
"esse ID existe DENTRO DO MESMO CONTEXTO
DE ORÇAMENTO E REVISÃO?"
```

A proteção final da TASK-0001 passa a ser:

```text
DOMÍNIO
+
APPLICATION
+
TRANSAÇÃO
+
CONCORRÊNCIA
+
FOREIGN KEYS COMPOSTAS
+
UNIQUE CONSTRAINTS
+
AUDITORIA
```

Assim:

```text
Quote A
```

não pode receber silenciosamente:

```text
Revision;
ItemRevision;
Access;
Submission;
Decision
```

pertencentes a outro orçamento.

**A INTEGRIDADE DO ORÇAMENTO PASSA A SER DEFENDIDA TANTO PELO CÓDIGO QUANTO PELO POSTGRESQL.**