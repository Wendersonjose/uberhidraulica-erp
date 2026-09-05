# TASK — Template de Tarefa

## 1. Identificação

ID: `TASK-XXXX`

Título:

Status: `BACKLOG`

Prioridade: `NORMAL`

Criado em:

Criado por:

Última atualização:

---

## 2. Objetivo

Descrever de forma objetiva o resultado que esta tarefa deve produzir.

A tarefa deve responder:

> O que precisa estar funcionando quando esta tarefa estiver concluída?

---

## 3. Contexto

Descrever:

- problema atual;
- motivo da tarefa;
- situação operacional relacionada;
- impacto esperado;
- origem da necessidade.

Não definir solução técnica nesta seção.

---

## 4. Módulo proprietário

Módulo:

Agente proprietário:

```text
AG-XX — Nome do agente
```

Somente um módulo deve ser considerado proprietário principal da tarefa.

Outros módulos podem participar como dependências.

---

## 5. Agente responsável

Agente responsável pela etapa atual:

```text
AG-XX
```

---

## 6. Agentes envolvidos

Marcar apenas os agentes realmente necessários.

### Governança

- [ ] AG-00 — Orquestrador
- [ ] AG-01 — Produto & Requisitos
- [ ] AG-02 — Arquitetura
- [ ] AG-15 — Revisor Técnico

### Domínio

- [ ] AG-03 — Domínio Oficina
- [ ] AG-04 — Catálogo & Estoque
- [ ] AG-05 — Compras & Fornecedores
- [ ] AG-06 — Financeiro, Comissão & Rentabilidade
- [ ] AG-07 — Conciliação & Integrações Financeiras
- [ ] AG-08 — Fiscal
- [ ] AG-09 — Segurança & Auditoria

### Engenharia

- [ ] AG-10 — Banco de Dados
- [ ] AG-11 — Backend Spring
- [ ] AG-12 — Frontend React
- [ ] AG-13 — QA & Testes
- [ ] AG-14 — DevOps

---

## 7. Requisitos relacionados

Listar os requisitos funcionais relacionados.

Exemplo:

```text
REQ-ORC-001
REQ-ORC-002
REQ-OS-004
```

Requisitos:

```text
-
```

---

## 8. Decisões relacionadas

Listar decisões de produto ou arquitetura relacionadas.

Exemplo:

```text
DR-0012
ADR-0004
```

Decisões:

```text
-
```

---

## 9. Regras de negócio

Documentar somente regras já aprovadas.

### RN-01

Descrição:

### RN-02

Descrição:

### RN-03

Descrição:

Se alguma regra necessária estiver indefinida:

`IMPLEMENTAÇÃO BLOQUEADA`

e deve ser criada uma `DECISION_REQUEST`.

---

## 10. Pré-condições

Condições que precisam existir antes da execução do fluxo.

Exemplo:

```text
- usuário autenticado;
- OS existente;
- orçamento ainda válido;
- usuário possui permissão necessária.
```

Pré-condições:

```text
-
```

---

## 11. Fluxo principal

Descrever o fluxo funcional esperado.

Exemplo:

```text
1. usuário acessa a funcionalidade;
2. sistema apresenta dados;
3. usuário executa ação;
4. sistema valida regras;
5. sistema altera estado;
6. sistema registra histórico;
7. sistema retorna resultado.
```

Fluxo:

```text
1.
2.
3.
```

---

## 12. Fluxos alternativos

### FA-01

Condição:

Comportamento esperado:

### FA-02

Condição:

Comportamento esperado:

---

## 13. Exceções

### EX-01

Situação:

Resultado esperado:

### EX-02

Situação:

Resultado esperado:

---

## 14. Critérios de aceite

Utilizar preferencialmente:

```text
DADO
QUANDO
ENTÃO
```

### CA-01

```text
DADO que
QUANDO
ENTÃO
```

### CA-02

```text
DADO que
QUANDO
ENTÃO
```

### CA-03

```text
DADO que
QUANDO
ENTÃO
```

Todos os critérios de aceite obrigatórios devem estar aprovados antes da implementação.

---

## 15. Dependências

### Dependências funcionais

```text
-
```

### Dependências técnicas

```text
-
```

### Tasks dependentes

```text
-
```

### Tasks bloqueadoras

```text
-
```

---

## 16. Impacto em outros módulos

### Oficina

```text
NÃO | SIM
Detalhes:
```

### Catálogo / Estoque

```text
NÃO | SIM
Detalhes:
```

### Compras

```text
NÃO | SIM
Detalhes:
```

### Financeiro

```text
NÃO | SIM
Detalhes:
```

### Conciliação

```text
NÃO | SIM
Detalhes:
```

### Comissão

```text
NÃO | SIM
Detalhes:
```

### Fiscal

```text
NÃO | SIM
Detalhes:
```

### Segurança

```text
NÃO | SIM
Detalhes:
```

---

## 17. Impacto financeiro

Esta tarefa altera algum dos seguintes conceitos?

- [ ] preço;
- [ ] desconto;
- [ ] custo;
- [ ] receita;
- [ ] despesa;
- [ ] conta a pagar;
- [ ] conta a receber;
- [ ] pagamento;
- [ ] recebimento;
- [ ] caixa;
- [ ] cartão;
- [ ] comissão;
- [ ] estoque valorizado;
- [ ] margem;
- [ ] lucro;
- [ ] imposto;
- [ ] nenhum.

Se houver impacto financeiro:

AG-06 deve participar quando aplicável.

Detalhes:

```text
-
```

---

## 18. Impacto de estoque

Esta tarefa altera:

- [ ] saldo físico;
- [ ] saldo reservado;
- [ ] saldo disponível;
- [ ] custo médio;
- [ ] inventário;
- [ ] ajuste;
- [ ] perda;
- [ ] nenhum.

Detalhes:

```text
-
```

---

## 19. Impacto fiscal

Esta tarefa afeta:

- [ ] NFS-e;
- [ ] DPS;
- [ ] documento fiscal;
- [ ] certificado;
- [ ] tributação;
- [ ] cancelamento fiscal;
- [ ] nenhum.

Se houver impacto:

AG-08 deve participar.

Detalhes:

```text
-
```

---

## 20. Segurança

A tarefa envolve:

- [ ] autenticação;
- [ ] autorização;
- [ ] nova permissão;
- [ ] ação crítica;
- [ ] dados financeiros;
- [ ] dados pessoais;
- [ ] link público;
- [ ] certificado;
- [ ] segredo;
- [ ] integração externa;
- [ ] nenhum.

Se houver impacto relevante:

AG-09 deve participar.

Detalhes:

```text
-
```

---

## 21. Auditoria

A funcionalidade precisa registrar histórico?

```text
SIM | NÃO
```

Quando aplicável, registrar:

- usuário;
- data/hora;
- ação;
- registro afetado;
- valor anterior;
- valor posterior;
- justificativa.

Eventos auditáveis:

```text
-
```

---

## 22. Persistência

Existe alteração de banco?

```text
SIM | NÃO
```

Se sim:

AG-10 deve analisar.

Entidades/conceitos afetados:

```text
-
```

Migrations necessárias:

```text
-
```

Constraints relevantes:

```text
-
```

Índices relevantes:

```text
-
```

Histórico necessário:

```text
-
```

---

## 23. Concorrência

Existe risco de dois usuários/processos alterarem o mesmo recurso simultaneamente?

```text
SIM | NÃO
```

Exemplos:

- última peça disponível;
- fechamento;
- pagamento;
- aprovação;
- conciliação;
- inventário.

Estratégia definida:

```text
-
```

---

## 24. Eventos de domínio

Eventos consumidos:

```text
-
```

Eventos produzidos:

```text
-
```

Exemplo:

```text
ItemOrcamentoAprovado
ServicoConcluido
PecaReservada
```

---

## 25. Integrações externas

Esta tarefa envolve:

- [ ] Itaú;
- [ ] Rede;
- [ ] NFS-e;
- [ ] Object Storage;
- [ ] nenhuma;
- [ ] outra.

Integração:

```text
-
```

Deve avaliar:

- [ ] timeout;
- [ ] retry;
- [ ] idempotência;
- [ ] external_id;
- [ ] reprocessamento;
- [ ] contingência;
- [ ] logs;
- [ ] tratamento de falha.

---

## 26. API

Existe alteração ou criação de API?

```text
SIM | NÃO
```

Endpoints:

```text
-
```

Requests:

```text
-
```

Responses:

```text
-
```

Permissões:

```text
-
```

Erros esperados:

```text
-
```

Documentação OpenAPI necessária:

```text
SIM | NÃO
```

---

## 27. Frontend

Existe alteração de frontend?

```text
SIM | NÃO
```

Telas afetadas:

```text
-
```

Componentes principais:

```text
-
```

Estados relevantes:

```text
-
```

Loading:

```text
-
```

Empty state:

```text
-
```

Erros esperados:

```text
-
```

Permissões de interface:

```text
-
```

---

## 28. Testes obrigatórios

### Unitários

```text
-
```

### Integração

```text
-
```

### Persistência

```text
-
```

### Segurança

```text
-
```

### Frontend

```text
-
```

### E2E

```text
-
```

### Regressão

```text
-
```

---

## 29. Casos de teste mínimos

### CT-01 — Fluxo principal

Entrada:

```text
-
```

Resultado esperado:

```text
-
```

### CT-02 — Regra inválida

Entrada:

```text
-
```

Resultado esperado:

```text
-
```

### CT-03 — Limite

Entrada:

```text
-
```

Resultado esperado:

```text
-
```

---

## 30. Riscos

### Risco 1

Descrição:

Probabilidade:

```text
BAIXA | MÉDIA | ALTA
```

Impacto:

```text
BAIXO | MÉDIO | ALTO | CRÍTICO
```

Mitigação:

---

### Risco 2

Descrição:

Probabilidade:

```text
BAIXA | MÉDIA | ALTA
```

Impacto:

```text
BAIXO | MÉDIO | ALTO | CRÍTICO
```

Mitigação:

---

## 31. Fora do escopo

Registrar explicitamente o que esta tarefa não deve implementar.

```text
-
```

Itens `FUTURE` não devem entrar silenciosamente nesta tarefa.

---

## 32. Arquivos e artefatos esperados

Exemplo:

```text
código backend
migration
endpoint
componente React
teste
documentação
ADR
```

Artefatos:

```text
-
```

---

## 33. Handoffs

### Handoff 1

Origem:

```text
AG-XX
```

Destino:

```text
AG-XX
```

Status:

```text
PENDENTE | CONCLUÍDO
```

Arquivo relacionado:

```text
-
```

---

## 34. Decision Requests

Decision Requests relacionadas:

```text
-
```

Decision Requests impeditivas abertas:

```text
-
```

Se houver uma Decision Request impeditiva:

`STATUS DA TASK = BLOCKED`

---

## 35. Checklist Definition of Ready

Antes de alterar para `READY`:

- [ ] objetivo definido;
- [ ] contexto definido;
- [ ] módulo proprietário definido;
- [ ] agente responsável identificado;
- [ ] requisitos relacionados;
- [ ] regras de negócio definidas;
- [ ] critérios de aceite definidos;
- [ ] dependências conhecidas;
- [ ] impacto financeiro analisado;
- [ ] impacto de segurança analisado;
- [ ] impacto de auditoria analisado;
- [ ] riscos principais conhecidos;
- [ ] nenhuma decisão impeditiva pendente.

---

## 36. Checklist antes de IN_PROGRESS

- [ ] status atual é `READY`;
- [ ] responsável definido;
- [ ] dependências obrigatórias concluídas;
- [ ] arquitetura aprovada quando necessária;
- [ ] banco analisado quando necessário;
- [ ] contratos disponíveis;
- [ ] nenhum bloqueio impeditivo.

---

## 37. Checklist antes de REVIEW

- [ ] implementação concluída;
- [ ] critérios implementados;
- [ ] testes criados;
- [ ] testes executados;
- [ ] migrations executadas em ambiente de teste;
- [ ] documentação atualizada;
- [ ] segurança considerada;
- [ ] auditoria considerada;
- [ ] nenhuma Decision Request impeditiva aberta;
- [ ] handoff para AG-15 preparado.

---

## 38. Checklist Definition of Done

Antes de alterar para `DONE`:

- [ ] critérios de aceite atendidos;
- [ ] testes passaram;
- [ ] AG-13 validou quando necessário;
- [ ] AG-15 concluiu revisão;
- [ ] nenhum finding CRITICAL aberto;
- [ ] nenhum finding HIGH impeditivo aberto;
- [ ] arquitetura respeitada;
- [ ] segurança validada;
- [ ] auditoria validada;
- [ ] documentação atualizada;
- [ ] migrations revisadas;
- [ ] API documentada quando aplicável;
- [ ] nenhuma Decision Request impeditiva;
- [ ] AG-00 realizou validação final.

---

## 39. Resultado da revisão técnica

Revisor:

`AG-15`

Status:

```text
PENDING
APPROVED
CHANGES_REQUESTED
BLOCKED
REQUIRES_DECISION
```

Findings:

```text
-
```

Apto para DONE:

```text
SIM | NÃO
```

---

## 40. Histórico da Task

### Versão 1

Data:

Alteração:

Responsável:

```text
-
```

---

## 41. Encerramento

Status final:

```text
DONE | CANCELLED
```

Data de encerramento:

Responsável pelo encerramento:

`AG-00`

Observações finais:

```text
-
```