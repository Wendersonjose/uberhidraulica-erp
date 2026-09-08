# DR-0001 — Momento de Obsolescência de Versão Comercial Pendente

## 1. Identificação

ID:

```text
DR-0001
```

Título:

```text
Momento de obsolescência de versão comercial pendente
```

Tipo:

```text
BUSINESS
```

Status:

```text
DECIDED
```

Criado em:

```text
2026-09-08
```

Criado por:

```text
AG-15 — Revisor Técnico
```

Última atualização:

```text
2026-09-08
```

---

## 2. Task relacionada

Task principal:

```text
TASK-0001 — Aprovação Parcial de Orçamento
```

Outras tasks impactadas:

```text
-
```

---

## 3. Origem

Agente que identificou a necessidade:

```text
AG-15 — Revisor Técnico
```

Módulo relacionado:

```text
Oficina / Orçamento
```

---

## 4. Problema

A especificação da TASK-0001 determina corretamente que uma decisão antiga nunca deve ser aplicada automaticamente a uma nova versão comercial.

Entretanto não estava definido exatamente em qual momento uma versão comercial pendente deixa de aceitar nova decisão quando uma nova versão do mesmo item é criada.

Problema:

```text
Definir quando QuoteItemRevision anterior deixa de aceitar
nova aprovação ou rejeição após a criação de uma nova
QuoteItemRevision para o mesmo QuoteItem.
```

---

## 5. Contexto

Exemplo:

```text
R1 apresentada ao cliente

Item A
A-v1
R$ 500
PENDENTE_APROVACAO
```

Posteriormente o gerente altera comercialmente o item:

```text
A-v2
R$ 600
```

Durante a preparação da alteração:

```text
A-v2 está em uma QuoteRevision DRAFT.
```

A dúvida era se a simples existência desse rascunho já deveria invalidar:

```text
A-v1
```

ou se a condição anterior continuaria válida até que a nova proposta fosse efetivamente apresentada ao cliente.

Também é necessário preservar corretamente o cenário de complemento:

```text
R1:
A-v1

R2:
A-v1
B-v1 novo
```

Nesse caso `A-v1` não sofreu alteração comercial e não deve ser invalidado apenas porque existe uma nova revisão global do orçamento.

---

## 6. Motivo da Decision Request

- [x] regra de negócio ausente;
- [ ] regra contraditória;
- [ ] alteração de requisito;
- [ ] alteração arquitetural;
- [ ] risco financeiro;
- [ ] risco fiscal;
- [x] risco de segurança;
- [x] problema de persistência;
- [ ] expansão de escopo;
- [ ] conflito entre módulos;
- [ ] comportamento de integração externa;
- [ ] outro.

Detalhes:

```text
Sem uma regra explícita, backend, banco, frontend e QA
poderiam adotar momentos diferentes para considerar uma
versão comercial obsoleta.

Isso poderia permitir aprovação de condição comercial
já substituída ou invalidar prematuramente uma proposta
que ainda continua sendo a apresentada ao cliente.
```

---

## 7. Decisão atual relacionada

Existe decisão anterior relacionada?

```text
SIM
```

Decisões relacionadas:

```text
- alteração de preço exige nova aprovação;
- alteração de descrição exige nova aprovação;
- alteração de quantidade exige nova aprovação;
- alteração exclusivamente interna não invalida aprovação;
- complemento não invalida item anteriormente aprovado
  quando sua condição comercial não mudou;
- revisão antiga nunca deve aprovar silenciosamente versão nova.
```

---

## 8. Requisitos relacionados

```text
REQ-ORC-001 — Aprovação Parcial de Orçamento
```

---

## 9. Regras de negócio relacionadas

### RN-01 — Alteração comercial

```text
Mudança de preço, descrição ou quantidade cria uma
nova versão comercial e exige nova aprovação.
```

### RN-02 — Histórico

```text
Versões e decisões anteriores devem permanecer preservadas.
```

### RN-03 — Complemento

```text
Adicionar novo item não invalida automaticamente
versões comerciais não alteradas dos demais itens.
```

### RN-04 — Revisão específica

```text
Uma decisão somente vale para a versão comercial
exata apresentada ao cliente.
```

---

## 10. Opções identificadas

### Opção A

Descrição:

```text
A versão anterior deixa de aceitar decisão assim que
uma nova versão comercial é criada em DRAFT.
```

Vantagens:

```text
Impede qualquer nova decisão sobre a versão anterior
assim que uma alteração é iniciada internamente.
```

Desvantagens:

```text
Um rascunho ainda não apresentado ao cliente passa a alterar
imediatamente a capacidade do cliente de decidir sobre uma
proposta que ainda é oficialmente a proposta apresentada.
```

Impactos:

```text
Maior acoplamento entre trabalho interno em rascunho
e comportamento do link já enviado ao cliente.
```

Riscos:

```text
Invalidar prematuramente proposta ainda vigente.
```

---

### Opção B

Descrição:

```text
A versão anterior continua apta a receber decisão enquanto
a nova versão comercial existir somente como DRAFT.

A versão anterior deixa de aceitar nova decisão quando
a nova versão comercial do MESMO ITEM for efetivamente
PRESENTED ao cliente.
```

Vantagens:

```text
DRAFT permanece como trabalho interno.

Somente uma nova proposta efetivamente apresentada altera
a condição comercial disponível para nova decisão.

Mantém separação clara entre preparação interna e proposta
comercial apresentada ao cliente.

Compatível com complementos que reutilizam uma
QuoteItemRevision não alterada.
```

Desvantagens:

```text
Enquanto a nova versão permanece em DRAFT,
a versão anteriormente apresentada ainda pode ser decidida.
```

Impactos:

```text
Domínio precisa determinar obsolescência por versão do item,
e não apenas pelo número global da QuoteRevision.
```

Riscos:

```text
É necessário controle correto de concorrência caso a nova
versão seja apresentada simultaneamente à decisão do cliente.
```

---

### Opção C

Descrição:

```text
A versão anterior continua apta a receber decisão até que
um usuário revogue manualmente o acesso ou a versão.
```

Vantagens:

```text
Controle operacional explícito.
```

Desvantagens:

```text
Depende de intervenção humana.

Pode manter preço antigo disponível mesmo após uma nova
proposta comercial ter sido apresentada.
```

Impactos:

```text
Exigiria processo operacional adicional de revogação.
```

Riscos:

```text
Aprovação de condição comercial antiga por esquecimento
de revogação.
```

---

## 11. Recomendação do agente

Recomendação:

```text
OPÇÃO B
```

Justificativa:

```text
DRAFT representa preparação interna.

PRESENTED representa a efetiva disponibilização de uma
nova condição comercial ao cliente.

Por isso a nova versão só deve substituir a anterior
para novas decisões quando efetivamente apresentada.
```

---

## 12. Impacto funcional

A decisão altera:

- [x] fluxo de usuário;
- [x] regra de negócio;
- [x] estado;
- [x] aprovação;
- [ ] garantia;
- [x] orçamento;
- [ ] OS;
- [ ] cadastro;
- [ ] relatório;
- [ ] nenhum.

Detalhes:

```text
Define exatamente quando uma versão comercial pendente
deixa de poder receber nova aprovação ou rejeição.
```

---

## 13. Impacto financeiro

A decisão afeta:

- [x] preço;
- [ ] desconto;
- [ ] custo;
- [ ] receita;
- [ ] despesa;
- [ ] comissão;
- [ ] conta a pagar;
- [ ] conta a receber;
- [ ] pagamento;
- [ ] recebimento;
- [ ] saldo;
- [ ] caixa;
- [ ] cartão;
- [ ] margem;
- [ ] lucro;
- [ ] imposto;
- [ ] nenhum.

Detalhes:

```text
A regra protege contra aprovação de uma condição de preço
anterior depois que uma nova condição comercial do mesmo
item foi efetivamente apresentada.
```

Não existe geração financeira direta nesta decisão.

---

## 14. Impacto de estoque

A decisão afeta:

- [ ] saldo físico;
- [ ] saldo reservado;
- [ ] saldo disponível;
- [ ] custo médio;
- [ ] inventário;
- [ ] ajuste;
- [ ] perda;
- [x] nenhum.

Detalhes:

```text
Nenhum efeito de estoque nesta Task.
```

---

## 15. Impacto fiscal

A decisão afeta:

- [ ] NFS-e;
- [ ] DPS;
- [ ] documento fiscal;
- [ ] tributação;
- [ ] certificado;
- [ ] cancelamento;
- [ ] integração fiscal;
- [x] nenhum.

---

## 16. Impacto de segurança

A decisão afeta:

- [ ] autenticação;
- [x] autorização;
- [ ] permissão;
- [ ] ação crítica;
- [ ] dados pessoais;
- [ ] dados financeiros;
- [ ] segredo;
- [ ] certificado;
- [x] link público;
- [ ] nenhum.

Detalhes:

```text
O PublicQuoteAccess nunca pode autorizar uma decisão que
o domínio considere obsoleta.

O token não transforma uma versão comercial obsoleta
em uma versão novamente decidível.
```

---

## 17. Impacto arquitetural

A decisão altera:

- [ ] módulo;
- [ ] fronteira;
- [x] contrato público;
- [ ] evento;
- [x] persistência;
- [x] transação;
- [ ] infraestrutura;
- [ ] dependência externa;
- [ ] nenhum.

Detalhes:

```text
O backend precisa validar se a QuoteItemRevision enviada
ainda é a versão comercial vigente para novas decisões.

A validação ocorre dentro da mesma transação da submissão.
```

---

## 18. Impacto em banco de dados

Existe impacto de persistência?

```text
SIM
```

Conceitos afetados:

```text
QuoteRevision
QuoteItem
QuoteItemRevision
QuoteRevisionItem
QuoteDecision
PublicQuoteAccess
```

Migration necessária:

```text
NÃO NESTA SIMULAÇÃO
```

Na implementação real:

```text
SIM, conforme modelo definitivo do AG-10.
```

Histórico precisa ser preservado?

```text
SIM
```

Risco de perda de dados:

```text
BAIXO
```

O risco principal é de associação/decisão incorreta, não de exclusão física.

---

## 19. Impacto em integrações externas

Integrações afetadas:

- [ ] Itaú;
- [ ] Rede;
- [ ] NFS-e;
- [ ] Object Storage;
- [x] nenhuma;
- [ ] outra.

---

## 20. Módulos impactados

- [ ] IAM
- [ ] CRM
- [x] Oficina
- [ ] Catálogo
- [ ] Estoque
- [ ] Compras
- [ ] Financeiro
- [ ] Conciliação
- [ ] Comissão
- [ ] Fiscal
- [ ] Rentabilidade
- [x] Auditoria
- [ ] Integrações

Detalhes:

```text
A decisão pertence funcionalmente ao módulo Oficina.
Auditoria deve preservar qual versão foi efetivamente decidida.
```

---

## 21. Agentes envolvidos na análise

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

## 22. Responsável pela decisão

Responsável principal:

```text
PROPRIETÁRIO DO PRODUTO
```

---

## 23. Necessita decisão do proprietário?

```text
SIM
```

Motivo:

```text
Define comportamento operacional e comercial de aprovação
de orçamento pelo cliente.
```

---

## 24. Impacto se nenhuma decisão fosse tomada

```text
A TASK-0001 permaneceria bloqueada porque a implementação
poderia aceitar ou rejeitar decisões de versões antigas
de forma diferente entre backend, frontend e banco.
```

---

## 25. Task deve ser bloqueada?

Antes da decisão:

```text
SIM
```

Após a decisão registrada neste documento:

```text
NÃO
```

Motivo:

```text
A ambiguidade funcional foi resolvida pelo proprietário.
```

---

## 26. Prazo da decisão

Existe prazo operacional?

```text
NÃO
```

A decisão foi tomada imediatamente durante a especificação.

---

## 27. Evidências

```text
TASK-0001

REQ-ORC-001

Documento de domínio da aprovação parcial

Documento de arquitetura da aprovação parcial

Parecer do AG-15

Fluxos de complemento

Fluxos de alteração comercial
```

---

## 28. Pergunta final para decisão

```text
Quando uma nova versão comercial de um item é criada,
em qual momento a versão anterior deixa de aceitar
nova decisão do cliente?
```

---

# 29. Análise dos agentes

## AG-01 — Produto & Requisitos

Análise:

```text
A regra precisa preservar a proposta efetivamente apresentada
ao cliente sem permitir que trabalho interno não enviado
altere silenciosamente sua capacidade de decisão.
```

Recomendação:

```text
Opção B.
```

---

## AG-02 — Arquitetura

Aplicável?

```text
SIM
```

Análise:

```text
A obsolescência deve ser determinada por versão comercial
do item, não pela simples existência de uma QuoteRevision
global mais recente.
```

Recomendação:

```text
A apresentação de uma nova QuoteItemRevision do mesmo item
é o marco de substituição para novas decisões.
```

---

## AG-03 — Domínio Oficina

Análise:

```text
DRAFT representa preparação interna.

PRESENTED representa uma condição efetivamente disponibilizada
ao cliente.

Complementos que reutilizam a mesma QuoteItemRevision
não constituem alteração comercial daquele item.
```

Recomendação:

```text
Opção B.
```

---

## AG-09 — Segurança & Auditoria

Análise:

```text
PublicQuoteAccess não pode superar as invariantes comerciais
do domínio.

Mesmo com token válido, versão comercial obsoleta deve
ser rejeitada para nova decisão.
```

---

## AG-10 — Banco de Dados

Aplicável?

```text
SIM
```

Análise:

```text
O modelo precisa permitir identificar qual versão comercial
do item está vigente e quais revisões a apresentaram,
preservando as versões anteriores.
```

---

## AG-15 — Revisor Técnico

Aplicável?

```text
SIM
```

Observações:

```text
A opção B preserva a separação entre trabalho interno
e proposta efetivamente apresentada.

Também evita considerar uma revisão global de complemento
como substituição automática de todos os itens.
```

---

# 30. Decisão final

Status:

```text
DECIDED
```

Opção escolhida:

```text
B
```

Decisão:

```text
Uma QuoteItemRevision anteriormente apresentada continua
apta a receber nova decisão enquanto a nova versão comercial
do mesmo QuoteItem existir somente em DRAFT.

A versão anterior deixa de aceitar nova decisão quando
uma nova QuoteItemRevision do MESMO QuoteItem for
efetivamente PRESENTED ao cliente.

A mera criação de uma nova QuoteRevision global não torna
automaticamente todas as versões anteriores obsoletas.

Quando uma nova QuoteRevision reutiliza a mesma
QuoteItemRevision sem alteração comercial, essa versão
não se torna obsoleta apenas por estar presente em uma
nova revisão global.
```

---

## 31. Justificativa da decisão

```text
Rascunho é atividade interna e não deve alterar a proposta
que o cliente efetivamente recebeu.

Uma nova proposta comercial passa a substituir a anterior
somente quando é apresentada ao cliente.

A regra também preserva corretamente complementos:
adicionar outro item ao orçamento não modifica
automaticamente uma versão comercial que permaneceu igual.
```

---

## 32. Responsável pela decisão final

Nome / Papel:

```text
Proprietário do produto
```

Agente:

```text
PROPRIETÁRIO DO PRODUTO
```

---

## 33. Data da decisão

```text
2026-09-08
```

---

## 34. Regras resultantes

### Regra 1 — DRAFT não invalida versão apresentada

```text
Criar uma nova QuoteItemRevision dentro de uma
QuoteRevision DRAFT não invalida imediatamente a versão
comercial anteriormente apresentada.
```

### Regra 2 — PRESENTED substitui versão anterior do mesmo item

```text
Quando uma nova QuoteItemRevision do mesmo QuoteItem
é apresentada ao cliente, a versão comercial anterior
deixa de aceitar nova decisão.
```

### Regra 3 — Histórico permanece

```text
A obsolescência para novas decisões não altera nem apaga
aprovações ou rejeições anteriormente registradas.
```

### Regra 4 — Complemento não invalida item não alterado

```text
Uma nova QuoteRevision que reutiliza a mesma
QuoteItemRevision não torna essa versão obsoleta apenas
porque a revisão global é mais nova.
```

### Regra 5 — Obsolescência é por item/versionamento comercial

```text
A existência de uma QuoteRevision global posterior não é,
isoladamente, suficiente para determinar stale status
de todos os itens.
```

### Regra 6 — Token não supera stale

```text
Mesmo com PublicQuoteAccess válido, uma QuoteItemRevision
comercialmente substituída não pode receber nova decisão.
```

---

## 35. Requisitos que precisam ser atualizados

```text
REQ-ORC-001 — Aprovação Parcial de Orçamento
```

Adicionar regra explícita de:

```text
momento de obsolescência da versão comercial.
```

---

## 36. Documentação que precisa ser atualizada

- [x] docs/requirements
- [x] docs/domain
- [x] docs/architecture
- [x] docs/api
- [ ] ADR
- [x] Task
- [x] testes
- [ ] nenhuma.

Detalhes:

```text
Também revisar documentação de banco e frontend
quando necessário para manter consistência.
```

---

## 37. Tasks impactadas após decisão

```text
TASK-0001
```

---

## 38. Status das tasks após decisão

Task:

```text
TASK-0001
```

Novo status:

```text
READY
```

Motivo:

```text
A Decision Request deixou de ser bloqueadora porque
a decisão funcional foi tomada.

A Task ainda precisa das correções apontadas pelo AG-15
antes da aprovação final.
```

---

## 39. ADR necessária?

```text
NÃO
```

Motivo:

```text
A decisão é predominantemente uma regra funcional
do domínio de orçamento e não uma decisão arquitetural
global do sistema.
```

---

## 40. Migration necessária?

Nesta simulação:

```text
NÃO
```

Na implementação:

```text
A migration real deverá refletir o modelo definitivo
aprovado pelo AG-10.
```

---

## 41. Testes necessários após decisão

Adicionar explicitamente:

```text
1. nova ItemRevision DRAFT não invalida versão apresentada;

2. nova ItemRevision PRESENTED invalida versão anterior
   para novas decisões;

3. aprovação registrada antes da substituição permanece válida;

4. nova QuoteRevision com complemento e mesma ItemRevision
   não invalida o item não alterado;

5. token válido não permite decidir ItemRevision
   comercialmente substituída;

6. concorrência entre apresentação da nova versão
   e decisão da versão anterior.
```

---

## 42. Handoff após decisão

Fluxo:

```text
DR-0001 DECIDED
↓
AG-01
↓
AG-03
↓
AG-02
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

Objetivo:

```text
propagar a decisão aos contratos antes de encerrar
a TASK-0001.
```

---

## 43. Encerramento

Decision Request:

```text
RESOLVIDA
```

Decisão:

```text
OPÇÃO B
```

Bloqueio funcional:

```text
REMOVIDO
```

Regra oficial:

```text
DRAFT NÃO INVALIDA.

UMA NOVA VERSÃO COMERCIAL DO MESMO ITEM PASSA A
SUBSTITUIR A ANTERIOR PARA NOVAS DECISÕES QUANDO
FOR EFETIVAMENTE PRESENTED AO CLIENTE.

COMPLEMENTO QUE REUTILIZA A MESMA ITEM REVISION
NÃO INVALIDA ESSA VERSÃO.
```