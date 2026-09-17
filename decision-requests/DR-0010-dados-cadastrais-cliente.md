# DR-0010 — Dados cadastrais do cliente: documento opcional, telefone e endereço

- Tipo: `FUNCTIONAL`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0009`
- Origem: `AG-01 — Produto & Requisitos` / `AG-10 — Banco de Dados`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner

## Problema

O cartão Trello "Cadastro de clientes" define:

```text
Nome/Razão Social  obrigatório
Telefone           obrigatório
CPF/CNPJ           opcional
E-mail             opcional
Endereço           opcional
```

e deixa em aberto "as validações específicas de PF/PJ e a estrutura detalhada do endereço".

O modelo implementado na TASK-0004 exige documento (`document NOT NULL`) e não possui telefone,
e-mail nem endereço. A `DR-0005` decidiu a normalização e a unicidade do documento, mas partiu do
documento obrigatório.

## Decisão provisória

1. **Documento opcional.** Quando informado, segue integralmente a `DR-0005`: somente dígitos,
   11 para PF e 14 para PJ, único entre todos os clientes (ativos e inativos). A constraint
   `uq_crm_customer_document` é preservada; `NULL` não conflita.
2. **Telefone obrigatório** na criação e na atualização pela API. Persistido somente com dígitos,
   de 10 a 13 dígitos (fixo, celular, com ou sem DDI 55). A coluna é anulável no banco apenas para
   preservar clientes criados antes desta regra; o próximo salvamento desses clientes exigirá
   telefone.
3. **E-mail opcional**, normalizado em minúsculas, com validação sintática simples. Não é único.
4. **Endereço opcional e estruturado** em colunas próprias: CEP (8 dígitos), logradouro, número,
   complemento, bairro, cidade e UF (duas letras). Nenhum campo do endereço é obrigatório
   isoladamente — a oficina costuma saber só a cidade.
5. **Sem dígito verificador** de CPF/CNPJ nesta Task, mantendo o que a TASK-0008 registrou como
   fora de escopo e os dados de teste existentes.
6. **Tipo de pessoa pode ser alterado** na edição, desde que o documento, se houver, continue
   compatível.

## Pergunta final

Confirma documento opcional, telefone obrigatório e endereço estruturado sem campos obrigatórios?
Deseja validação de dígito verificador de CPF/CNPJ?
