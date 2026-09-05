# TASK-0001 — Aprovação Parcial de Orçamento

## 1. Identificação

ID: `TASK-0001`

Título: `Aprovação parcial de itens do orçamento`

Status: `BACKLOG`

Prioridade: `HIGH`

Criado em: `2026-09-05`

Criado por: `AG-00 — Orquestrador`

Última atualização: `2026-09-05`

---

## 2. Objetivo

Permitir que o cliente analise os itens de um orçamento individualmente e aprove ou rejeite cada item separadamente.

Os itens aprovados devem poder seguir para execução sem depender da decisão dos demais itens.

Ao final desta tarefa, deve ser possível representar corretamente:

```text
Orçamento:
Item A → APROVADO
Item B → REJEITADO
Item C → PENDENTE_APROVACAO

Resultado:
Item A pode seguir para execução.
Item B não pode ser executado como item aprovado.
Item C continua aguardando decisão.
```

---

## 3. Contexto

Na operação da oficina, um orçamento pode conter vários serviços e o cliente pode decidir executar somente parte deles.

A aprovação total do orçamento como um único booleano não representa corretamente essa operação.

Também é necessário preservar o histórico das decisões do cliente e permitir complementos posteriores sem perder aprovações já realizadas.

---

## 4. Módulo proprietário

Módulo:

```text
Oficina / Orçamento
```

Agente proprietário:

```text
AG-03 — Domínio Oficina
```

---

## 5. Agente responsável

Etapa inicial:

```text
AG-00 — Orquestrador
```

Fluxo previsto:

```text
AG-00
↓
AG-01
↓
AG-03
↓
AG-02
↓
AG-09
↓
AG-10
↓
AG-11
↓
AG-12
↓
AG-13
↓
AG-15
↓
AG-00
```

Nesta primeira simulação, AG-11 e AG-12 não produzirão código de produção.

---

## 6. Agentes envolvidos

### Governança

- [x] AG-00 — Orquestrador
- [x] AG-01 — Produto & Requisitos
- [x] AG-02 — Arquitetura
- [x] AG-15 — Revisor Técnico

### Domínio

- [x] AG-03 — Domínio Oficina
- [ ] AG-04 — Catálogo & Estoque
- [ ] AG-05 — Compras & Fornecedores
- [ ] AG-06 — Financeiro, Comissão & Rentabilidade
- [ ] AG-07 — Conciliação & Integrações Financeiras
- [ ] AG-08 — Fiscal
- [x] AG-09 — Segurança & Auditoria

### Engenharia

- [x] AG-10 — Banco de Dados
- [x] AG-11 — Backend Spring
- [x] AG-12 — Frontend React
- [x] AG-13 — QA & Testes
- [ ] AG-14 — DevOps

---

## 7. Requisitos relacionados

Requisito principal a ser formalizado:

```text
REQ-ORC-001 — Aprovação parcial de orçamento
```

Requisitos complementares previstos:

```text
REQ-ORC-002 — Identificação do cliente na aprovação pública
REQ-ORC-003 — Histórico das decisões do orçamento
REQ-ORC-004 — Validade do orçamento
REQ-ORC-005 — Revisão de item aprovado após alteração comercial
```

Os documentos de requisito serão produzidos pelo AG-01 durante esta simulação.

---

## 8. Decisões relacionadas

Decisões de produto já aprovadas:

```text
- cliente não precisa possuir login no ERP;
- orçamento permite aprovação parcial;
- itens aprovados podem ser executados enquanto outros permanecem pendentes;
- validade padrão do orçamento é de 7 dias;
- expiração afeta itens pendentes, não itens já aprovados;
- alteração comercial de item aprovado exige nova aprovação;
- alteração exclusivamente interna não invalida aprovação;
- item rejeitado pode ser reaberto pelo gerente;
- histórico das decisões deve ser preservado.
```

Decision Requests:

```text
Nenhuma impeditiva identificada no início da TASK.
```

---

## 9. Regras de negócio

### RN-01 — Decisão individual

Cada item do orçamento deve possuir sua própria decisão.

---

### RN-02 — Aprovação parcial

Um orçamento pode possuir simultaneamente itens:

```text
APROVADO
REJEITADO
PENDENTE_APROVACAO
```

---

### RN-03 — Execução independente

Um item aprovado pode seguir para execução sem aguardar a decisão dos demais itens.

---

### RN-04 — Item pendente

Item pendente não pode ser tratado como aprovado.

---

### RN-05 — Item rejeitado

Item rejeitado não pode ser tratado como aprovado.

---

### RN-06 — Histórico

Decisões anteriores não podem ser apagadas ou sobrescritas silenciosamente.

---

### RN-07 — Identificação do cliente

No fluxo público, a decisão deve registrar:

```text
nome
CPF ou CNPJ
aceite explícito
timestamp
IP
user-agent
```

O token utilizado deve ser rastreável sem necessidade de armazenar seu segredo bruto quando isso representar risco.

---

### RN-08 — Validade

Validade padrão:

```text
7 dias
```

---

### RN-09 — Expiração

A expiração impede nova aprovação de itens ainda pendentes sem renovação válida.

Itens aprovados anteriormente continuam aprovados.

---

### RN-10 — Alteração comercial

Alterações em:

```text
preço
descrição
quantidade
```

invalidam a aprovação para a nova versão do item.

---

### RN-11 — Nova revisão

Após alteração comercial:

```text
nova revisão
→ PENDENTE_APROVACAO
```

A aprovação anterior permanece histórica.

---

### RN-12 — Alteração interna

Mudanças exclusivamente internas, como:

```text
técnico
fornecedor
custo
informação operacional
```

não invalidam a aprovação do cliente quando o objeto comercial permanece igual.

---

### RN-13 — Reabertura de rejeitado

Item rejeitado pode ser reaberto por gerente autorizado.

Uma nova revisão deve ser criada.

A rejeição anterior permanece registrada.

---

### RN-14 — Complemento

Novos itens podem ser adicionados posteriormente.

Itens previamente aprovados continuam aprovados.

Os novos itens aguardam decisão própria.

---

### RN-15 — Versão específica

A decisão do cliente deve estar vinculada exatamente à revisão apresentada.

Um link de uma revisão não pode aprovar silenciosamente outra revisão.

---

### RN-16 — Idempotência funcional

Repetir a mesma decisão já processada não deve produzir registros duplicados com efeito operacional.

---

## 10. Pré-condições

Para aprovação pública:

```text
- OS existente;
- orçamento existente;
- revisão existente;
- item pertencente à revisão apresentada;
- link/token público válido;
- item em estado que permita decisão;
- prazo de aprovação válido.
```

---

## 11. Fluxo principal

```text
1. Gerente cria orçamento para uma OS.

2. O orçamento possui três itens.

3. Sistema cria uma revisão do orçamento.

4. Sistema disponibiliza acesso público seguro ao cliente.

5. Cliente acessa a revisão.

6. Sistema apresenta os itens daquela revisão.

7. Cliente informa identificação exigida.

8. Cliente escolhe individualmente quais itens deseja aprovar ou rejeitar.

9. Cliente realiza aceite explícito.

10. Backend valida:
    - token;
    - revisão;
    - validade;
    - estados;
    - dados obrigatórios.

11. Sistema registra as decisões individualmente.

12. Sistema preserva evidências da decisão.

13. Itens aprovados tornam-se elegíveis para o fluxo operacional de execução.

14. Itens rejeitados permanecem registrados como rejeitados.

15. Itens sem decisão permanecem pendentes.

16. Sistema registra histórico/eventos correspondentes.

17. Cliente recebe confirmação da decisão processada.
```

---

## 12. Fluxos alternativos

### FA-01 — Cliente aprova apenas parte

Condição:

```text
Orçamento possui 3 itens.
Cliente aprova apenas 2.
```

Comportamento:

```text
2 itens → APROVADO
1 item → PENDENTE_APROVACAO
```

Os dois aprovados podem seguir no fluxo operacional.

---

### FA-02 — Cliente rejeita item

Condição:

```text
Cliente seleciona REJEITAR.
```

Comportamento:

```text
item → REJEITADO
```

A decisão permanece no histórico.

---

### FA-03 — Cliente não decide item

Condição:

```text
Cliente não aprova nem rejeita determinado item.
```

Comportamento:

```text
item → PENDENTE_APROVACAO
```

---

### FA-04 — Complemento

Condição:

```text
Novo problema é identificado após aprovação parcial.
```

Comportamento:

```text
novos itens são incluídos em nova revisão;
itens anteriormente aprovados permanecem válidos;
novos itens aguardam aprovação.
```

---

### FA-05 — Reabertura de rejeitado

Condição:

```text
Gerente decide renegociar item rejeitado.
```

Comportamento:

```text
rejeição original permanece;
nova revisão é criada;
nova revisão do item → PENDENTE_APROVACAO.
```

---

## 13. Exceções

### EX-01 — Link expirado

Situação:

```text
cliente tenta decidir item pendente após validade.
```

Resultado:

```text
aprovação bloqueada;
histórico existente preservado.
```

---

### EX-02 — Revisão desatualizada

Situação:

```text
cliente tenta aprovar revisão diferente da atualmente permitida.
```

Resultado:

```text
não aplicar decisão a outra revisão.
```

---

### EX-03 — Item já decidido

Situação:

```text
mesma decisão é enviada novamente.
```

Resultado:

```text
não produzir efeito duplicado.
```

---

### EX-04 — Token inválido

Resultado:

```text
acesso/ação negado.
```

Não expor informações da OS.

---

### EX-05 — Dados de identificação ausentes

Resultado:

```text
decisão não é concluída.
```

---

## 14. Critérios de aceite

### CA-01 — Aprovação parcial

```text
DADO que um orçamento possui três itens pendentes
QUANDO o cliente aprovar dois itens
ENTÃO os dois itens devem ficar aprovados
E o terceiro deve permanecer pendente.
```

---

### CA-02 — Execução

```text
DADO que um item está aprovado
E outro item do mesmo orçamento está pendente
QUANDO o gerente iniciar o serviço aprovado
ENTÃO o sistema deve permitir sua execução
SEM exigir decisão sobre o item pendente.
```

---

### CA-03 — Rejeição

```text
DADO que um item está pendente
QUANDO o cliente rejeitar o item
ENTÃO ele deve ficar rejeitado
E não deve ser tratado como item aprovado.
```

---

### CA-04 — Histórico

```text
DADO que um cliente aprovou ou rejeitou um item
QUANDO houver alteração posterior no orçamento
ENTÃO a decisão anterior deve continuar disponível no histórico.
```

---

### CA-05 — Alteração de preço

```text
DADO que um item foi aprovado
QUANDO seu preço comercial for alterado
ENTÃO deve ser criada uma nova revisão
E a nova revisão deve ficar pendente de aprovação.
```

---

### CA-06 — Alteração interna

```text
DADO que um item foi aprovado
QUANDO somente o técnico responsável for alterado
ENTÃO a aprovação do cliente deve continuar válida.
```

---

### CA-07 — Expiração de pendente

```text
DADO que um item continua pendente
E a validade de 7 dias expirou
QUANDO o cliente tentar aprová-lo
ENTÃO o sistema deve impedir a aprovação sem renovação válida.
```

---

### CA-08 — Aprovação anterior à expiração

```text
DADO que um item foi aprovado dentro da validade
QUANDO o orçamento posteriormente expirar
ENTÃO o item deve continuar aprovado.
```

---

### CA-09 — Revisão específica

```text
DADO que existe uma revisão nova
QUANDO uma solicitação referente à revisão anterior for enviada
ENTÃO o sistema não deve aplicar a decisão à revisão nova.
```

---

### CA-10 — Idempotência

```text
DADO que uma decisão já foi processada
QUANDO a mesma solicitação for enviada novamente
ENTÃO não deve ser produzido efeito operacional duplicado.
```

---

## 15. Dependências

### Funcionais

```text
- Ordem de Serviço;
- cliente;
- veículo;
- serviço da OS;
- orçamento;
- revisão;
- item.
```

### Técnicas

```text
A definir por AG-02.
```

### Tasks dependentes

```text
Nenhuma neste momento.
```

### Tasks bloqueadoras

```text
Nenhuma identificada.
```

---

## 16. Impacto em outros módulos

### Oficina

```text
SIM

Responsabilidade principal.
```

### Catálogo / Estoque

```text
INDIRETO

A aprovação poderá futuramente provocar reserva ou necessidade de compra.
Esse efeito não será implementado nesta simulação.
```

### Compras

```text
INDIRETO

Falta de peça após aprovação pode originar necessidade de compra.
Fora da implementação desta Task de simulação.
```

### Financeiro

```text
NÃO DIRETO

Aprovação não equivale a pagamento ou recebível definitivo.
```

### Conciliação

```text
NÃO
```

### Comissão

```text
NÃO DIRETO

Aprovação não gera comissão.
Comissão depende de conclusão do serviço.
```

### Fiscal

```text
NÃO

Aprovação não equivale à emissão de NFS-e.
```

### Segurança

```text
SIM

Existe link público e registro de evidências.
```

---

## 17. Impacto financeiro

- [x] preço;
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

Detalhes:

```text
O preço é relevante porque alteração do preço de item aprovado
invalida a aprovação para a nova revisão.

A Task não cria pagamento, recebimento ou conta financeira.
```

---

## 18. Impacto de estoque

- [ ] saldo físico;
- [ ] saldo reservado;
- [ ] saldo disponível;
- [ ] custo médio;
- [ ] inventário;
- [ ] ajuste;
- [ ] perda;
- [x] nenhum diretamente nesta simulação.

Observação:

```text
A futura reação à aprovação poderá envolver reserva,
mas pertence a outra etapa/Task.
```

---

## 19. Impacto fiscal

- [ ] NFS-e;
- [ ] DPS;
- [ ] documento fiscal;
- [ ] certificado;
- [ ] tributação;
- [ ] cancelamento fiscal;
- [x] nenhum.

---

## 20. Segurança

A tarefa envolve:

- [ ] autenticação interna;
- [x] autorização;
- [ ] nova permissão interna obrigatoriamente;
- [ ] ação crítica com segunda aprovação;
- [ ] dados financeiros;
- [x] dados pessoais;
- [x] link público;
- [ ] certificado;
- [x] token;
- [ ] integração externa;
- [ ] nenhum.

AG-09 deve participar.

---

## 21. Auditoria

Necessária:

```text
SIM
```

Registrar, quando aplicável:

```text
revisão
item
decisão
nome informado
CPF/CNPJ informado
timestamp
IP
user-agent
identificador do acesso público
dados comerciais apresentados
```

Não registrar segredo do token em texto claro.

---

## 22. Persistência

Existe alteração de banco na futura implementação:

```text
SIM
```

AG-10 deverá analisar.

Conceitos previstos:

```text
quote
quote_revision
quote_item
quote_item_revision
quote_decision
public_access_token
```

Os nomes físicos ainda não estão aprovados.

---

## 23. Concorrência

Existe risco:

```text
SIM
```

Cenários:

```text
- gerente cria nova revisão enquanto cliente aprova a anterior;
- duas solicitações iguais são enviadas simultaneamente;
- gerente altera item enquanto cliente está na tela de aprovação.
```

Estratégia:

```text
A definir por AG-02 e AG-10.
```

---

## 24. Eventos de domínio

Eventos consumidos:

```text
A definir.
```

Eventos produzidos propostos:

```text
QuoteCreated
QuoteSent
QuoteItemApproved
QuoteItemRejected
QuoteItemReopened
QuoteItemRevised
```

AG-03 e AG-02 devem validar nomenclatura e responsabilidade.

---

## 25. Integrações externas

- [ ] Itaú;
- [ ] Rede;
- [ ] NFS-e;
- [ ] Object Storage;
- [x] nenhuma nesta Task.

---

## 26. API

Existe impacto:

```text
SIM
```

Contratos conceituais esperados:

```text
consultar orçamento público por token
registrar decisões do cliente
consultar histórico interno
criar nova revisão
reabrir item rejeitado
```

Endpoints definitivos serão definidos por AG-02/AG-11.

---

## 27. Frontend

Existe impacto:

```text
SIM
```

Telas conceituais:

```text
- orçamento dentro da OS;
- histórico/revisões;
- página pública de aprovação.
```

Estados necessários:

```text
loading
success
error
expired
invalid
pending
approved
rejected
stale revision
```

---

## 28. Testes obrigatórios

### Unitários

```text
- transições de decisão;
- validade;
- invalidação por alteração comercial;
- preservação da aprovação em alteração interna;
- idempotência quando aplicável.
```

### Integração

```text
- persistência de revisão;
- persistência da decisão;
- concorrência;
- API pública.
```

### Persistência

```text
PostgreSQL via Testcontainers quando implementado.
```

### Segurança

```text
- token válido;
- token inválido;
- token expirado;
- acesso limitado;
- ausência de exposição de outra OS.
```

### Frontend

```text
- apresentação dos itens;
- seleção individual;
- estados de sucesso/erro;
- expiração;
- revisão obsoleta.
```

### E2E

```text
Orçamento de 3 itens → aprovar 2 → rejeitar 1.
```

### Regressão

```text
Item aprovado continua aprovado após expiração geral.
```

---

## 29. Casos de teste mínimos

### CT-01 — Aprovação parcial

Entrada:

```text
A = pendente
B = pendente
C = pendente

Cliente:
A = aprovar
B = aprovar
C = sem decisão
```

Resultado:

```text
A = aprovado
B = aprovado
C = pendente
```

---

### CT-02 — Rejeição

Entrada:

```text
A = pendente
Cliente rejeita A
```

Resultado:

```text
A = rejeitado
```

---

### CT-03 — Alteração comercial

Entrada:

```text
A aprovado por R$ 350
Gerente altera para R$ 400
```

Resultado:

```text
Aprovação antiga preservada
Nova revisão = pendente
```

---

### CT-04 — Alteração interna

Entrada:

```text
A aprovado
Gerente altera técnico
```

Resultado:

```text
A continua aprovado
```

---

### CT-05 — Expiração

Entrada:

```text
A aprovado no dia 2
B pendente
Dia 8
```

Resultado:

```text
A = aprovado
B não pode receber nova aprovação sem renovação
```

---

### CT-06 — Solicitação duplicada

Entrada:

```text
mesma aprovação enviada duas vezes
```

Resultado:

```text
uma única decisão efetiva
```

---

## 30. Riscos

### Risco 1 — Aprovar versão errada

Probabilidade:

```text
MÉDIA
```

Impacto:

```text
ALTO
```

Mitigação:

```text
Decisão vinculada a revisão/item específicos.
Validação no backend.
```

---

### Risco 2 — Duplicidade

Probabilidade:

```text
MÉDIA
```

Impacto:

```text
ALTO
```

Mitigação:

```text
Idempotência e constraints adequadas.
```

---

### Risco 3 — Vazamento pelo link público

Probabilidade:

```text
BAIXA/MÉDIA
```

Impacto:

```text
ALTO
```

Mitigação:

```text
Token imprevisível, limitado, expirável e tratamento pelo AG-09.
```

---

### Risco 4 — Perda de histórico

Probabilidade:

```text
MÉDIA
```

Impacto:

```text
ALTO
```

Mitigação:

```text
Modelo versionado.
Não sobrescrever decisões anteriores.
```

---

## 31. Fora do escopo

Não implementar nesta Task:

```text
- WhatsApp;
- envio automático por WhatsApp;
- pagamento;
- recebimento;
- NFS-e;
- comissão;
- reserva automática de estoque;
- necessidade de compra completa;
- código Java;
- código React;
- deploy.
```

Nesta simulação, AG-11 e AG-12 produzirão somente contratos e plano de implementação.

---

## 32. Arquivos e artefatos esperados

```text
tasks/backlog/TASK-0001-aprovacao-parcial-orcamento.md

docs/requirements/oficina/REQ-ORC-001-aprovacao-parcial.md

docs/domain/oficina/aprovacao-parcial-orcamento.md

docs/architecture/...

documentação do modelo de dados da simulação

documentação dos contratos REST da simulação

documentação dos cenários de QA

handoffs entre agentes
```

---

## 33. Handoffs

Fluxo previsto:

```text
AG-00 → AG-01
AG-01 → AG-03
AG-03 → AG-02
AG-02 → AG-09
AG-02 → AG-10
AG-02/AG-10 → AG-11
AG-11 → AG-12
AG-11/AG-12 → AG-13
AG-13 → AG-15
AG-15 → AG-00
```

---

## 34. Decision Requests

Relacionadas:

```text
Nenhuma.
```

Impeditivas abertas:

```text
Nenhuma.
```

---

## 35. Checklist Definition of Ready

- [x] objetivo definido;
- [x] contexto definido;
- [x] módulo proprietário definido;
- [x] agente responsável identificado;
- [ ] requisitos formalmente documentados por AG-01;
- [x] regras preliminares identificadas;
- [x] critérios de aceite preliminares definidos;
- [x] dependências conhecidas;
- [x] impacto financeiro analisado;
- [x] impacto de segurança analisado;
- [x] impacto de auditoria analisado;
- [x] riscos principais conhecidos;
- [x] nenhuma decisão impeditiva pendente.

Status permanece:

```text
BACKLOG
```

até análise formal do AG-01.

---

## 36. Checklist antes de IN_PROGRESS

- [ ] status `READY`;
- [ ] AG-01 concluiu requisitos;
- [ ] AG-03 concluiu análise de domínio;
- [ ] arquitetura aprovada;
- [ ] persistência analisada;
- [ ] segurança analisada;
- [ ] contratos disponíveis;
- [ ] nenhum bloqueio impeditivo.

---

## 37. Checklist antes de REVIEW

Não aplicável ainda.

---

## 38. Checklist Definition of Done

- [ ] requisito aprovado;
- [ ] domínio aprovado;
- [ ] arquitetura aprovada;
- [ ] segurança aprovada;
- [ ] persistência proposta;
- [ ] contratos backend propostos;
- [ ] fluxo frontend proposto;
- [ ] cenários de QA produzidos;
- [ ] AG-15 realizou revisão;
- [ ] nenhuma Decision Request impeditiva;
- [ ] AG-00 realizou validação final.

Nesta simulação, `DONE` significa:

```text
feature completamente especificada e validada,
mas ainda não implementada em código.
```

---

## 39. Resultado da revisão técnica

Revisor:

```text
AG-15
```

Status:

```text
PENDING
```

Findings:

```text
-
```

Apto para DONE:

```text
NÃO
```

---

## 40. Histórico da Task

### Versão 1

Data:

```text
2026-09-05
```

Alteração:

```text
Criação da primeira Task de simulação da governança.
```

Responsável:

```text
AG-00
```

---

## 41. Encerramento

Status final:

```text
PENDENTE
```

Data de encerramento:

```text
-
```

Responsável pelo encerramento:

```text
AG-00
```

Observações finais:

```text
Esta Task será utilizada para validar o processo de trabalho
dos agentes antes da criação do projeto Spring Boot.
```