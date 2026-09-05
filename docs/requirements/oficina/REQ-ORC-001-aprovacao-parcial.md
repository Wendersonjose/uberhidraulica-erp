# REQ-ORC-001 — Aprovação Parcial de Orçamento

## 1. Identificação

Código:

```text
REQ-ORC-001
```

Título:

```text
Aprovação parcial de itens do orçamento
```

Módulo:

```text
Oficina / Orçamento
```

Agente responsável:

```text
AG-01 — Produto & Requisitos
```

Agente de domínio:

```text
AG-03 — Domínio Oficina
```

Task relacionada:

```text
TASK-0001 — Aprovação Parcial de Orçamento
```

Status:

```text
APPROVED
```

Prioridade:

```text
HIGH
```

Data:

```text
2026-09-05
```

---

# 2. Objetivo

Permitir que o cliente analise e decida individualmente sobre cada item de um orçamento.

O cliente deve poder:

```text
aprovar um item;
rejeitar um item;
não tomar decisão sobre outro item.
```

O sistema não deve exigir aprovação ou rejeição de todos os itens para que os itens já aprovados possam seguir no fluxo operacional.

---

# 3. Problema de negócio

Na operação da oficina, um orçamento pode conter múltiplos serviços.

O cliente pode desejar executar apenas parte dos serviços apresentados.

Exemplo:

```text
Orçamento

Serviço A
R$ 500
→ cliente aprova

Serviço B
R$ 300
→ cliente rejeita

Serviço C
R$ 700
→ cliente ainda não decidiu
```

O sistema deve representar:

```text
Serviço A → APROVADO
Serviço B → REJEITADO
Serviço C → PENDENTE_APROVACAO
```

O orçamento não pode ser representado apenas por:

```text
aprovado = true
```

ou:

```text
aprovado = false
```

porque isso perde a decisão individual de cada item.

---

# 4. Resultado esperado

O sistema deve permitir que um orçamento possua simultaneamente itens em estados diferentes.

Exemplo:

```text
ORÇAMENTO

Item 1
→ APROVADO

Item 2
→ REJEITADO

Item 3
→ PENDENTE_APROVACAO
```

O item aprovado deve poder seguir para execução independentemente dos demais.

---

# 5. Escopo

Este requisito cobre:

```text
- decisão individual por item;
- aprovação parcial;
- rejeição individual;
- item sem decisão;
- validade do orçamento;
- preservação de decisões anteriores;
- revisões;
- alteração comercial;
- alteração interna;
- complementos;
- reabertura de item rejeitado;
- identificação do cliente;
- evidência da decisão;
- acesso público seguro;
- idempotência da decisão.
```

---

# 6. Fora do escopo

Este requisito não define:

```text
- envio por WhatsApp;
- integração com WhatsApp;
- pagamento;
- recebimento;
- geração de conta a receber;
- emissão de NFS-e;
- cálculo de comissão;
- reserva automática de estoque;
- compra automática;
- implementação Java;
- implementação React;
- estrutura física definitiva do banco.
```

Esses assuntos pertencem a requisitos ou Tasks específicas.

---

# 7. Atores

## 7.1 Cliente

O cliente:

```text
não possui login interno no ERP no MVP.
```

Pode acessar o orçamento através de link público seguro.

---

## 7.2 Gerente

Usuário interno autorizado pode:

```text
criar orçamento;
alterar itens;
adicionar complementos;
reabrir item rejeitado;
reenviar orçamento.
```

A autorização final dependerá das permissões definidas no módulo de segurança.

---

## 7.3 Sistema

O sistema deve:

```text
validar link;
validar revisão;
validar validade;
registrar decisões;
preservar histórico;
registrar evidências;
impedir aplicação de decisão em versão incorreta.
```

---

# 8. Conceitos

## 8.1 Orçamento

Conjunto comercial apresentado ao cliente dentro de uma Ordem de Serviço.

---

## 8.2 Item do orçamento

Unidade individual que pode receber decisão do cliente.

Um item pode corresponder, conforme o domínio da Oficina, a um serviço ou cobrança apresentada ao cliente.

A modelagem definitiva pertence ao AG-03 e AG-02.

---

## 8.3 Revisão

Representação versionada do conteúdo comercial apresentado ao cliente.

Uma revisão deve permitir identificar exatamente:

```text
o que foi apresentado;
quando foi apresentado;
qual preço foi apresentado;
qual descrição foi apresentada;
qual quantidade foi apresentada.
```

---

## 8.4 Decisão

Manifestação do cliente em relação a um item específico de uma revisão específica.

Valores funcionais necessários:

```text
APROVADO
REJEITADO
```

A ausência de decisão mantém o item:

```text
PENDENTE_APROVACAO
```

---

# 9. Regra de aprovação individual

Cada item deve possuir decisão própria.

É proibido exigir que todo orçamento tenha uma única decisão indivisível.

---

# 10. Aprovação parcial

O sistema deve suportar:

```text
Item A → APROVADO
Item B → APROVADO
Item C → PENDENTE_APROVACAO
```

sem transformar automaticamente o item C em:

```text
REJEITADO
```

---

# 11. Rejeição parcial

Também deve ser permitido:

```text
Item A → APROVADO
Item B → REJEITADO
Item C → PENDENTE_APROVACAO
```

---

# 12. Item sem decisão

O cliente não é obrigado a decidir todos os itens em uma única interação.

Um item que não recebeu aprovação nem rejeição permanece:

```text
PENDENTE_APROVACAO
```

---

# 13. Execução dos itens aprovados

Um item aprovado pode seguir para o fluxo operacional de execução.

Exemplo:

```text
Serviço A → APROVADO
Serviço B → PENDENTE_APROVACAO
```

Resultado:

```text
Serviço A pode ser executado.
Serviço B continua aguardando.
```

A execução do item A não depende da decisão sobre o item B.

---

# 14. Item pendente

Item:

```text
PENDENTE_APROVACAO
```

não pode ser tratado como aprovado.

---

# 15. Item rejeitado

Item:

```text
REJEITADO
```

não pode ser iniciado como serviço aprovado sem nova operação válida de reabertura/revisão.

---

# 16. Validade padrão

A validade padrão de um orçamento é:

```text
7 dias
```

---

# 17. Configuração de validade

A validade pertence à apresentação/revisão comercial.

A implementação deve permitir preservar a data efetiva utilizada em cada revisão.

---

# 18. Efeito da expiração

A expiração afeta:

```text
itens ainda pendentes
```

e não deve invalidar automaticamente:

```text
itens aprovados anteriormente dentro da validade.
```

---

# 19. Exemplo de expiração

```text
Dia 1:
orçamento enviado

Dia 3:
Item A aprovado

Dia 8:
orçamento atingiu validade

Resultado:

Item A → continua APROVADO
Item B pendente → não pode receber nova aprovação sem renovação válida
```

---

# 20. Histórico

Nenhuma decisão anterior deve desaparecer porque uma nova revisão foi criada.

O sistema deve permitir reconstruir:

```text
qual item foi apresentado;
qual versão foi apresentada;
qual decisão foi tomada;
quando ocorreu;
quem informou a decisão.
```

---

# 21. Alteração comercial

As seguintes alterações invalidam a aprovação anterior para a nova versão:

```text
preço;
descrição;
quantidade.
```

---

# 22. Efeito da alteração comercial

Exemplo:

```text
Revisão 1

Troca da caixa de direção
R$ 1.500

Cliente:
APROVADO
```

Depois:

```text
Gerente altera preço para R$ 1.700
```

Resultado obrigatório:

```text
Revisão 1
R$ 1.500
→ APROVADO
→ preservada historicamente

Revisão 2
R$ 1.700
→ PENDENTE_APROVACAO
```

---

# 23. Proibição de sobrescrita

É proibido transformar:

```text
Revisão 1
R$ 1.500
APROVADO
```

em:

```text
Revisão 1
R$ 1.700
APROVADO
```

Isso produziria evidência falsa de que o cliente aprovou R$ 1.700.

---

# 24. Alteração de descrição

Alteração de descrição apresentada ao cliente exige nova aprovação.

---

# 25. Alteração de quantidade

Alteração de quantidade apresentada ao cliente exige nova aprovação.

---

# 26. Alteração interna

Alterações que não modificam a oferta comercial ao cliente não invalidam automaticamente a aprovação.

Exemplos:

```text
técnico responsável;
fornecedor;
custo de aquisição;
custo médio;
informação operacional interna.
```

---

# 27. Exemplo de alteração interna

```text
Cliente aprovou:
Serviço X
R$ 500
```

Depois:

```text
Técnico A
→ substituído por Técnico B
```

Se descrição, quantidade e preço apresentados ao cliente permanecerem iguais:

```text
aprovação continua válida.
```

---

# 28. Complementos

Novos serviços podem ser identificados depois de uma aprovação parcial.

O sistema deve permitir adicionar novos itens sem invalidar automaticamente os itens anteriormente aprovados.

---

# 29. Exemplo de complemento

Situação inicial:

```text
Item A
→ APROVADO

Item B
→ APROVADO
```

Durante execução:

```text
novo problema identificado
```

Resultado:

```text
Item A
→ continua APROVADO

Item B
→ continua APROVADO

Item C
→ PENDENTE_APROVACAO
```

---

# 30. Reabertura de item rejeitado

Gerente autorizado pode reabrir um item anteriormente rejeitado.

---

# 31. Preservação da rejeição

Reabrir não significa apagar a rejeição anterior.

Exemplo:

```text
Revisão 1
Item A
R$ 700
→ REJEITADO
```

Gerente renegocia:

```text
Revisão 2
Item A
R$ 600
→ PENDENTE_APROVACAO
```

A decisão:

```text
REJEITADO na revisão 1
```

continua no histórico.

---

# 32. Decisão vinculada à revisão

Uma decisão deve estar vinculada exatamente à revisão que o cliente visualizou.

---

# 33. Revisão antiga

Se uma nova revisão substituir comercialmente uma revisão anterior, uma ação realizada utilizando a revisão anterior não pode ser aplicada silenciosamente à revisão nova.

---

# 34. Regra contra aprovação implícita

O sistema não pode concluir:

```text
"o cliente aprovou a versão nova porque já tinha aprovado a antiga"
```

quando houve mudança que exige nova aprovação.

---

# 35. Acesso do cliente

O cliente não precisa criar conta.

O sistema deve fornecer:

```text
link público seguro
```

para acesso ao orçamento.

---

# 36. Token público

O link deve utilizar token:

```text
imprevisível;
limitado ao recurso;
com validade;
revogável quando necessário.
```

A definição técnica pertence ao AG-09 e AG-02.

---

# 37. Identificação para decisão

Para concluir uma decisão pública, registrar:

```text
nome;
CPF ou CNPJ;
aceite explícito.
```

---

# 38. Evidências técnicas

Também registrar:

```text
timestamp;
IP;
user-agent.
```

Esses dados servem como evidência técnica da operação.

---

# 39. Aceite explícito

O cliente deve executar uma ação clara de confirmação.

Não considerar mera abertura do link como aprovação.

---

# 40. Dados apresentados

O sistema deve ser capaz de identificar o conteúdo comercial apresentado no momento da decisão.

No mínimo:

```text
item;
descrição;
quantidade;
preço;
revisão.
```

---

# 41. Auditoria

A operação deve preservar dados suficientes para demonstrar:

```text
quem declarou a decisão;
qual decisão;
sobre qual item;
sobre qual revisão;
quando;
sobre quais condições comerciais.
```

---

# 42. Token e auditoria

O segredo bruto utilizado no link não precisa ser armazenado de forma recuperável para fins de auditoria.

O AG-09 deverá definir estratégia segura de identificação do acesso.

---

# 43. Idempotência

Solicitação repetida não pode criar efeito operacional duplicado.

Exemplo:

```text
cliente clica em APROVAR;
rede demora;
cliente clica novamente;
duas requisições chegam.
```

Resultado:

```text
uma única aprovação efetiva.
```

---

# 44. Concorrência

O requisito deve funcionar corretamente mesmo quando:

```text
cliente está visualizando revisão antiga
e gerente cria nova revisão.
```

---

# 45. Regra de concorrência

Nenhuma decisão deve migrar automaticamente de uma revisão para outra.

---

# 46. Relação com a Ordem de Serviço

O orçamento pertence ao contexto de uma Ordem de Serviço existente.

A OS existe antes do orçamento.

---

# 47. Aprovação não cria OS

A aprovação de orçamento não cria uma nova OS.

---

# 48. Aprovação não fecha OS

A aprovação de itens não significa:

```text
OS concluída;
OS fechada;
veículo entregue.
```

---

# 49. Aprovação e estoque

A aprovação poderá futuramente disparar processos relacionados a:

```text
reserva de peça;
necessidade de compra.
```

Esses efeitos pertencem aos módulos correspondentes.

Não fazem parte desta Task de simulação.

---

# 50. Aprovação e financeiro

Aprovação não significa automaticamente:

```text
pagamento;
recebimento;
liquidação;
conciliação.
```

---

# 51. Aprovação e comissão

Aprovação não gera comissão.

A comissão torna-se elegível de acordo com as regras do serviço concluído.

---

# 52. Aprovação e fiscal

Aprovação não significa emissão de NFS-e.

---

# 53. Estados funcionais mínimos

Para decisão do item:

```text
PENDENTE_APROVACAO
APROVADO
REJEITADO
```

Estados adicionais técnicos ou de revisão poderão ser definidos pelo AG-03/AG-02 sem alterar a semântica aprovada.

---

# 54. Transições funcionais

Fluxo básico:

```text
PENDENTE_APROVACAO
    ├── aprovar
    │      ↓
    │   APROVADO
    │
    └── rejeitar
           ↓
        REJEITADO
```

---

# 55. Alteração comercial de aprovado

```text
APROVADO
↓
alteração comercial
↓
aprovação histórica preservada
+
nova revisão
↓
PENDENTE_APROVACAO
```

---

# 56. Reabertura de rejeitado

```text
REJEITADO
↓
gerente autorizado reabre
↓
rejeição histórica preservada
+
nova revisão
↓
PENDENTE_APROVACAO
```

---

# 57. Regra sobre estados históricos

Não modificar o estado histórico para simular nova decisão.

Criar nova representação/revisão quando necessário.

---

# 58. Cenário principal

Dado:

```text
Orçamento possui:

A — R$ 300
B — R$ 500
C — R$ 700
```

Quando:

```text
cliente aprova A;
cliente rejeita B;
cliente não decide C.
```

Então:

```text
A → APROVADO
B → REJEITADO
C → PENDENTE_APROVACAO
```

E:

```text
A pode seguir para execução.
```

---

# 59. Critério de aceite CA-01

```gherkin
DADO que um orçamento possui três itens pendentes
QUANDO o cliente aprovar dois itens
ENTÃO os dois itens devem ficar aprovados
E o terceiro deve permanecer pendente
```

---

# 60. Critério de aceite CA-02

```gherkin
DADO que um item está aprovado
E outro item do mesmo orçamento está pendente
QUANDO o usuário autorizado iniciar o serviço aprovado
ENTÃO o sistema deve permitir sua execução
SEM exigir decisão sobre o item pendente
```

---

# 61. Critério de aceite CA-03

```gherkin
DADO que um item está pendente
QUANDO o cliente rejeitar esse item
ENTÃO o item deve ficar rejeitado
E não deve ser tratado como item aprovado
```

---

# 62. Critério de aceite CA-04

```gherkin
DADO que um item possui uma decisão registrada
QUANDO uma nova revisão for criada
ENTÃO a decisão anterior deve permanecer disponível no histórico
```

---

# 63. Critério de aceite CA-05

```gherkin
DADO que um item foi aprovado
QUANDO o preço apresentado ao cliente for alterado
ENTÃO a aprovação anterior deve ser preservada historicamente
E uma nova revisão deve ficar pendente de aprovação
```

---

# 64. Critério de aceite CA-06

```gherkin
DADO que um item foi aprovado
QUANDO apenas o técnico responsável for alterado
ENTÃO a aprovação do cliente deve permanecer válida
```

---

# 65. Critério de aceite CA-07

```gherkin
DADO que um item está pendente
E a revisão atingiu sua data de validade
QUANDO o cliente tentar aprovar esse item
ENTÃO a nova aprovação deve ser impedida
ATÉ existir condição válida para nova decisão
```

---

# 66. Critério de aceite CA-08

```gherkin
DADO que um item foi aprovado durante a validade
QUANDO a revisão posteriormente expirar
ENTÃO o item deve permanecer aprovado
```

---

# 67. Critério de aceite CA-09

```gherkin
DADO que existe uma revisão comercial mais nova
QUANDO uma solicitação da revisão anterior for recebida
ENTÃO o sistema não deve aplicar essa decisão à revisão nova
```

---

# 68. Critério de aceite CA-10

```gherkin
DADO que uma decisão foi processada
QUANDO a mesma operação for reenviada
ENTÃO nenhuma decisão operacional duplicada deve ser criada
```

---

# 69. Critério de aceite CA-11

```gherkin
DADO que o cliente rejeitou um item
QUANDO um gerente autorizado reabrir esse item
ENTÃO a rejeição anterior deve permanecer no histórico
E uma nova revisão deve aguardar decisão
```

---

# 70. Critério de aceite CA-12

```gherkin
DADO que itens anteriores já foram aprovados
QUANDO um novo item for acrescentado como complemento
ENTÃO os itens anteriormente aprovados devem permanecer aprovados
E o novo item deve aguardar decisão
```

---

# 71. Critério de aceite CA-13

```gherkin
DADO que o cliente acessa o orçamento por link público
QUANDO tentar registrar uma decisão
ENTÃO nome
E CPF ou CNPJ
E aceite explícito
DEVEM ser informados conforme validação aplicável
```

---

# 72. Critério de aceite CA-14

```gherkin
DADO um token público inválido
QUANDO alguém tentar consultar o orçamento
ENTÃO o sistema deve negar o acesso
E não deve revelar dados da OS
```

---

# 73. Critério de aceite CA-15

```gherkin
DADO que o cliente confirmou uma decisão
QUANDO a decisão for persistida
ENTÃO o sistema deve registrar timestamp
E IP
E user-agent
E revisão
E itens decididos
```

---

# 74. Cenários de erro

O sistema deve tratar explicitamente:

```text
token inválido;
token expirado;
revisão inválida;
revisão obsoleta;
item inexistente;
item não pertencente à revisão;
item em estado incompatível;
identificação ausente;
solicitação duplicada;
concorrência com nova revisão.
```

---

# 75. Mensagens

A interface deve diferenciar, no mínimo:

```text
link inválido;
link expirado;
orçamento atualizado;
decisão registrada;
item já decidido;
erro inesperado.
```

A redação final pertence ao AG-12.

---

# 76. Requisitos de segurança

AG-09 deve garantir análise de:

```text
entropia do token;
expiração;
revogação;
escopo;
CSRF quando aplicável;
rate limiting quando necessário;
exposição de dados;
logs;
armazenamento seguro;
auditoria.
```

---

# 77. Dados pessoais

O fluxo manipula:

```text
nome;
CPF/CNPJ;
IP;
user-agent.
```

O acesso a esses dados deve respeitar necessidade operacional.

---

# 78. Persistência histórica

O modelo de dados deve permitir preservar:

```text
orçamento;
revisão;
item;
conteúdo comercial;
decisão;
evidência.
```

---

# 79. Proibição de DELETE histórico

Decisão do cliente não deve ser apagada fisicamente como fluxo normal de negócio.

---

# 80. API

A futura API deverá permitir, conceitualmente:

```text
consultar revisão pública;
registrar decisões;
consultar histórico interno;
criar revisão;
reabrir item.
```

Os endpoints definitivos não pertencem ao AG-01.

---

# 81. Frontend interno

Deve ser possível visualizar:

```text
itens;
status individual;
revisões;
histórico de decisão.
```

---

# 82. Frontend público

Deve ser possível:

```text
visualizar orçamento;
identificar itens;
aprovar item;
rejeitar item;
deixar item sem decisão;
informar identificação;
confirmar aceite.
```

---

# 83. Não obrigatoriedade de decisão completa

A interface não deve obrigar o cliente a selecionar:

```text
APROVAR
ou
REJEITAR
```

para todos os itens antes de enviar decisões.

---

# 84. Preservação de não decisão

Item omitido na submissão deve permanecer pendente.

Não converter automaticamente em rejeitado.

---

# 85. Regras não definidas neste requisito

Este requisito não define:

```text
estrutura exata das tabelas;
nomes de endpoints;
nome de classes Java;
nome de componentes React;
algoritmo de geração de token;
estratégia exata de locking;
status HTTP específicos;
estrutura final de eventos.
```

Esses pontos serão tratados pelos agentes técnicos.

---

# 86. Dependências funcionais

Dependências:

```text
Cliente
Veículo
Ordem de Serviço
Serviço da OS
Orçamento
Item de orçamento
```

---

# 87. Dependências futuras

Podem reagir à aprovação:

```text
Estoque
Compras
Workflow
```

Mas não fazem parte da implementação desta simulação.

---

# 88. Riscos de produto

## 88.1 Perda de decisão

Impacto:

```text
ALTO
```

Mitigação:

```text
histórico imutável/versionado.
```

---

## 88.2 Aprovação de preço diferente

Impacto:

```text
CRÍTICO
```

Mitigação:

```text
decisão vinculada à revisão comercial específica.
```

---

## 88.3 Aprovação duplicada

Impacto:

```text
ALTO
```

Mitigação:

```text
idempotência.
```

---

## 88.4 Exposição pública indevida

Impacto:

```text
ALTO
```

Mitigação:

```text
AG-09 participa obrigatoriamente.
```

---

# 89. Decision Requests

Decision Requests impeditivas:

```text
NENHUMA
```

---

# 90. Pontos que não devem virar Decision Request

Já estão aprovados:

```text
aprovação parcial;
validade padrão de 7 dias;
itens aprovados sobrevivem à expiração;
preço alterado exige nova aprovação;
descrição alterada exige nova aprovação;
quantidade alterada exige nova aprovação;
alteração interna não invalida;
rejeitado pode ser reaberto;
cliente sem login;
identificação por nome + CPF/CNPJ;
registro de IP e user-agent.
```

Não reabrir essas decisões sem novo requisito do proprietário.

---

# 91. Rastreabilidade

Task:

```text
TASK-0001
```

Requisito:

```text
REQ-ORC-001
```

Agentes obrigatórios:

```text
AG-00
AG-01
AG-03
AG-02
AG-09
AG-10
AG-11
AG-12
AG-13
AG-15
```

---

# 92. Resultado do AG-01

```text
TASK:
TASK-0001

STATUS:
PRODUCT_APPROVED

REQUISITO:
REQ-ORC-001

MÓDULO:
OFICINA / ORÇAMENTO

REGRAS:
DEFINIDAS

CRITÉRIOS DE ACEITE:
DEFINIDOS

AMBIGUIDADES IMPEDITIVAS:
NENHUMA

DECISION REQUESTS:
NENHUMA

PRONTO PARA AG-03:
SIM
```

---

# 93. Handoff AG-01 → AG-03

```text
Task:
TASK-0001

Requisito:
REQ-ORC-001

Objetivo:
Permitir decisão individual dos itens do orçamento.

Estados funcionais mínimos:
PENDENTE_APROVACAO
APROVADO
REJEITADO

Invariantes de produto:
- decisão é individual;
- aprovação parcial é permitida;
- item aprovado pode seguir independentemente;
- alteração comercial exige nova aprovação;
- alteração interna não invalida;
- decisão anterior permanece histórica;
- expiração não invalida item já aprovado;
- revisão antiga não aprova revisão nova;
- requisição duplicada não cria efeito duplicado.

Segurança:
Link público seguro.
Nome + CPF/CNPJ.
Aceite explícito.
Timestamp.
IP.
User-agent.

Decision Requests:
Nenhuma.

Resultado esperado do AG-03:
Definir modelo de domínio, agregados, entidades,
value objects, estados, transições, invariantes,
eventos e contratos necessários.
```

---

# 94. Definition of Ready para domínio

- [x] problema definido;
- [x] objetivo definido;
- [x] atores definidos;
- [x] escopo definido;
- [x] regras aprovadas;
- [x] critérios de aceite definidos;
- [x] segurança identificada;
- [x] riscos identificados;
- [x] histórico obrigatório identificado;
- [x] concorrência identificada;
- [x] nenhuma Decision Request impeditiva.

Resultado:

```text
READY_FOR_DOMAIN
```

---

# 95. Regra final

A decisão do cliente deve representar exatamente:

```text
O QUE ELE VIU
+
O QUE ELE DECIDIU
+
QUANDO ELE DECIDIU
```

Nunca:

```text
uma versão posterior inferida pelo sistema.
```

**APROVAÇÃO DE ORÇAMENTO É POR ITEM E POR REVISÃO.**