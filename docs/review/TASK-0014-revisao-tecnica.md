# TASK-0014 — Revisão técnica independente (AG-15)

- Data: `2026-09-18`
- Responsável: `AG-15 — Revisor Técnico`
- Escopo: TASK-0014 (Estoque) e auditoria das decisões provisórias das TASK-0009 a TASK-0014
- Branch: `feature/autonomous-sprint-catalog`

---

## 1. Achados corrigidos nesta revisão

### F-01 — Baixa pela OS não era idempotente: finalização travava após troca de modo `BLOQUEADOR`

**Onde:** `InventoryApplicationService.writeOffForWorkOrder`.

**Defeito.** O índice único parcial `uq_stock_movement_work_order_item` garante que um item físico da
OS gere no máximo uma baixa, mas a aplicação não verificava essa condição antes de inserir. O
resultado era uma `DataIntegrityViolationException` não tratada — `500`, não `409` — em um caminho
inteiramente normal:

```text
modo ITEM_LAUNCH → item lançado → baixa registrada
modo alterado para WORK_ORDER_FINISH
OS finalizada → onFinished percorre TODOS os itens físicos
             → tenta baixar de novo o item que já baixou
             → violação do índice único → 500
```

A OS ficava **impossível de finalizar**, de forma permanente, sem nenhuma mensagem útil. O mesmo
valia para qualquer reprocesso do evento.

**Correção.** A baixa passou a ter chave de idempotência explícita — o item da OS:

1. `StockRepositoryPort.workOrderExitExists(workOrderItemId)` consulta a baixa existente antes de
   movimentar; se já existe, a operação é um no-op e a finalização segue.
2. O índice único continua sendo a autoridade final para o caso concorrente, e a violação agora é
   traduzida em `409 STOCK_WRITE_OFF_CONFLICT` em vez de escapar como `500`.

Cobertura: `changingTheWriteOffModeAfterLaunchDoesNotDiscountTheSameItemTwice`,
`repeatingTheFinishDoesNotDiscountTwice` e
`concurrentWriteOffsOfTheSameWorkOrderItemDiscountOnlyOnce`.

### F-02 — Regra de unidade escrita, mas sem nenhum chamador `MÉDIO`

**Onde:** `ProductUnit.countable()`.

**Defeito.** O predicado que distinguia unidades contáveis não era chamado em lugar nenhum. A regra
da `DR-0014` ("unidades contáveis são inteiras") estava **escrita na documentação e no código, e não
valia em execução**: o sistema aceitava `2,5 UNIDADE` e `0,5 BALDE_20L`.

**Correção.** O predicado foi removido, não implementado — ver F-03. Código que descreve uma
validação inexistente é pior que a ausência do código, porque uma leitura futura acredita nele.

### F-03 — `DR-0014` pré-decidiu uma `DR` ainda aberta `GOVERNANÇA`

**Defeito.** A `DR-0014` registrou como decidido que "quantidades de unidades contáveis são inteiras;
as demais aceitam três casas". Isso é, literalmente, a **Opção C da `DR-0006`**, que está `OPEN` e
cuja pergunta final é exatamente essa. Uma DR de estoque não tem autoridade para fechar a pergunta de
outra DR.

**Correção.** A afirmação foi removida da `DR-0014`, que agora remete explicitamente à `DR-0006`. A
`DR-0006` recebeu a evidência nova da TASK-0014 (as unidades de embalagem, inerentemente contáveis,
reforçam a Opção C) e a precisão provisória em vigor foi registrada como a própria DR exige. Nenhuma
regra comercial foi inventada para fechar a lacuna.

### F-04 — Regra material de produto inativo sem decisão aprovada `GOVERNANÇA`

**Defeito.** "Produto inativo não recebe entrada, mas admite saída, ajuste negativo, estorno e
devolução" é regra de negócio material. O cartão Trello fala apenas de **histórico** preservado; não
responde o que acontece com o saldo vivo. A regra estava apenas como decisão provisória.

**Correção.** Criada a `DR-0016`, que separa as três operações que o campo `active` estava
respondendo de uma vez só: consultar histórico, movimentar estoque existente e entrar em nova OS. O
comportamento atual permanece, agora identificado como pendente de decisão.

### F-05 — Regressão na TASK-0006 não detectada: a suíte completa não havia sido executada `ALTO`

**Onde:** `Task0006ProductItemIntegrationTest`.

**Defeito.** Com o modo padrão `ITEM_LAUNCH`, lançar um item físico passou a descontar estoque, e sem
saldo o lançamento é recusado. Dois testes da TASK-0006 — `launchesProductOnWorkOrderAndKeepsSnapshot…`
e `acceptsSeveralProductsAndPreservesInclusionOrder` — criam produtos sem estoque e esperavam `201`;
passaram a receber `409 INSUFFICIENT_STOCK`.

A TASK-0014 foi movida para `REVIEW` declarando gates verdes, mas essas duas falhas estavam presentes
no estado entregue. **A suíte completa não havia sido executada** — apenas a da própria Task.

**Correção.** Os dois testes recebem saldo antes do lançamento, por `POST /api/inventory/products/{id}/entries`.
Isso mantém intacto o assunto deles — retrato do item na OS e ordem de inclusão — e ainda passa a
exercitar a configuração padrão de produção. **Nenhuma asserção foi enfraquecida e nenhum `expected`
foi ajustado para caber no novo comportamento**: o que mudou foi a pré-condição do cenário, porque o
comportamento novo tem requisito (cartão "Baixa de estoque pela ordem de serviço" + `README`
"estoque nunca negativo").

O `@BeforeEach` da classe também passou a limpar `inventory.stock_movement` e `inventory.stock_balance`
antes do catálogo — ambos referenciam `productcatalog.product`, e sem isso a limpeza quebraria por
chave estrangeira assim que um teste gerasse saldo.

---

## 2. Auditoria obrigatória do Estoque

| Pergunta | Resposta | Ancoragem |
| --- | --- | --- |
| Estoque pode ficar negativo? | **Não.** | `README`: "Estoque nunca negativo" / "Não será permitido: estoque negativo" — **requisito aprovado**, não decisão provisória |
| Operação sem saldo é recusada? | Sim, `409 INSUFFICIENT_STOCK`, e a operação da OS que a disparou é desfeita | domínio + `CHECK ck_stock_balance_nonnegative` |
| Existe exceção administrativa? | **Não existe, e nenhuma foi criada.** Nem ajuste negativo pode cruzar o zero | documentação é explícita no sentido negativo |
| Concorrência | `SELECT … FOR UPDATE` na linha do saldo serializa toda movimentação do mesmo produto | testado contra PostgreSQL real |
| Idempotência | Chave = item da OS; verificação na aplicação + índice único no banco | F-01 |
| Atomicidade | O ouvinte roda na transação da OS (`Propagation.MANDATORY`); falha em um item desfaz todos | testado |
| Cancelamento devolve estoque? | Sim, movimento `WORK_ORDER_RETURN` ligado à baixa original | cartão "Baixa de estoque pela OS": "Cancelamentos/estornos devem gerar movimentação inversa" |
| Correção altera o movimento antigo? | **Nunca.** Sempre movimento compensatório novo, que referencia o original | cartão "Entradas e ajustes": "Movimentações não são apagadas" |
| Histórico reescrito? | Não. Não existe `UPDATE` nem `DELETE` sobre `stock_movement` em nenhum caminho | verificado no adaptador |
| Contexto de auditoria | Todo movimento grava tipo, sentido, quantidade, saldo e custo resultantes, motivo, origem, OS, item da OS, instante e autor | cartão "Entradas e ajustes" |
| Soma entre unidades incompatíveis | **Impossível por construção**: a quantidade é sempre expressa na unidade cadastrada do produto; a API de movimentação não aceita unidade própria | — |
| Fração por unidade | **Em aberto** — `DR-0006`. Provisoriamente três casas para qualquer unidade | F-03 |
| Produto inativo | Consulta liberada; nova OS recusada (regra aprovada); movimentação **em aberto** — `DR-0016` | F-04 |

### Estratégia de idempotência, em uma frase

A baixa de estoque de um item físico da OS é identificada pelo **`work_order_item_id`**, verificado
na aplicação antes de movimentar e garantido no banco pelo índice único parcial
`uq_stock_movement_work_order_item`; a devolução é identificada pelo movimento que ela estorna
(`reverses_movement_id`, também com índice único), de modo que nem a baixa nem o estorno podem
ocorrer duas vezes, qualquer que seja o caminho — reenvio HTTP, duplo clique, reprocesso de evento ou
timeout seguido de nova tentativa.

---

## 3. Auditoria das decisões provisórias (TASK-0009 a TASK-0014)

### Clientes — telefone obrigatório: **requisito aprovado**

A origem foi verificada na fonte, não na alteração dos testes. O cartão Trello "Cadastro de clientes"
(lista "A fazer", quadro "Projetos wenderson") diz textualmente:

```text
• Nome/Razão Social — obrigatório
• Telefone — obrigatório
• CPF/CNPJ — opcional
```

A regra **não** nasceu da adaptação dos testes antigos: ela é anterior a eles e está no cartão que
originou a TASK-0009. A alteração dos testes das TASK-0004/0006/0007/0008 foi consequência da regra,
não sua justificativa. Nada a normalizar e nada a reverter.

O que permanece provisório na `DR-0010` é outra coisa: documento **opcional** (o cartão diz opcional,
mas o modelo da TASK-0004 o exigia) e a estrutura do endereço — ambos ainda sujeitos a ratificação.

### Veículos — troca de proprietário: **requisito aprovado e cumprido**

Cartão "Atualização e troca de proprietário do veículo":

```text
• A troca de proprietário não poderá apagar o histórico anterior do veículo.
• Ordens de serviço já existentes devem manter os dados e vínculos históricos correspondentes.
```

Verificado no código: `CrmApplicationService.changeOwner` encerra o período anterior em
`crm.vehicle_ownership` e abre um novo — o histórico de propriedade é uma série temporal, não um
campo sobrescrito. A OS guarda o `customerId` da abertura e **não é tocada** na troca; nenhuma
consulta de OS deriva o cliente do proprietário atual do veículo. Histórico não é reescrito
retroativamente. Conforme.

### Serviços — precedência de preço: **requisito aprovado e cumprido**

Cartão "Preços de serviços por veículo ou grupo":

```text
• Prioridade: preço específico do veículo → preço do grupo → preço base.
```

`ServiceCatalogApplicationService` resolve exatamente nessa ordem, com `NONE` quando não há preço
algum — em vez de arbitrar zero. Nenhuma regra comercial foi inventada; a precedência não precisava
de DR porque já estava decidida. O fallback para preço-base é o próprio cartão.

### Ordem de Serviço — status configuráveis: **requisito aprovado, invariantes preservadas**

Cartões "Kanban e status das OS" e "Configuração do fluxo e status da OS":

```text
• Regras automáticas de mudança de status poderão ser configuráveis.
• Identificar status especiais: inicial, finalizado e cancelado.
• Evitar remoção de status já usados; preferir inativação.
```

A configurabilidade é aprovada. O ponto de atenção levantado — "configuração de status não pode
transformar o workflow em máquina de estados arbitrária" — está atendido: o status configurável é
**rótulo e posição dentro de uma etapa** (`Stage`), e as etapas são fixas no domínio. Iniciar
execução, finalizar, entregar e cancelar continuam sendo transições do domínio, validadas no backend;
a configuração escolhe qual status representa cada etapa, não quais transições existem. Status
inativo não recebe OS; o padrão de uma etapa não pode ser um status inativo.

### Orçamento — decisão pública × registro interno: **explicitamente diferenciados**

Esta era a preocupação mais séria da auditoria, e o modelo a trata no nível do banco. `V15` adiciona
`channel VARCHAR(16)` a `workshop.quote_decision_submission` com duas constraints simétricas e
mutuamente exclusivas:

```sql
ck_quote_submission_public_evidence:
  channel <> 'PUBLIC_LINK' OR (public_quote_access_id IS NOT NULL AND request_payload_digest IS NOT NULL
    AND customer_name IS NOT NULL AND document_type IS NOT NULL
    AND document_number IS NOT NULL AND ip_address IS NOT NULL)

ck_quote_submission_internal_evidence:
  channel <> 'INTERNAL' OR (recorded_by IS NOT NULL AND contact_channel IS NOT NULL
    AND public_quote_access_id IS NULL)
```

Consequência: um registro interno **não pode** carregar as evidências de uma decisão pública — o
banco recusa. A ação administrativa não substitui silenciosamente a evidência do cliente; ela é um
registro de canal distinto, com autor identificado, canal de contato e instante do servidor. As duas
usam as mesmas regras de validade (revisão apresentada, dentro da validade, item não decidido, não
obsoleto, uma decisão por versão comercial). Conforme ao modelo aprovado da TASK-0001.

### Estado real da TASK-0008 no repositório

`tasks/review/TASK-0008-acesso-publico-decisao-cliente.md` — **Status: `REVIEW`**, não `DONE`.
Implementada (backend, frontend, migration `V10`, testes `Task0008PublicQuoteDecisionIntegrationTest`)
e com revisão técnica registrada em `docs/review/TASK-0008-revisao-tecnica.md`, mas **não encerrada**.
A `DR-0008` (vínculo item de orçamento × item físico) permanece `OPEN`.

---

## 4. Varredura AG-15

| Item | Resultado |
| --- | --- |
| Fronteiras Modulith | Conforme. `inventory` declara `allowedDependencies = {productcatalog, workorder, iam}`; `ModularityTest` verifica no build |
| Repository de outro módulo | Nenhum. O Estoque lê o catálogo por `ProductCatalogQuery.products()`, contrato público criado para isso |
| Dependência circular | Nenhuma. `inventory → workorder` (apenas `WorkOrderEvents`); a OS não conhece o Estoque |
| Dinheiro com `double`/`float` | Nenhuma ocorrência em `src/main`. `BigDecimal` / `NUMERIC` em toda parte |
| Migrations antigas alteradas | Nenhuma. `git diff main..HEAD` mostra todas as migrations como `A`; a mudança em `ck_product_unit` é um `ALTER` dentro da `V16` nova |
| Alteração retroativa de histórico | Nenhuma. Sem `UPDATE`/`DELETE` em `stock_movement`; correção é movimento compensatório |
| Race condition | Endereçada e **testada com PostgreSQL real** (não mock) |
| Idempotência | Endereçada — F-01 |
| Ausência de constraints | Boa cobertura: não negativo, quantidade positiva, motivo do ajuste, coerência origem/OS, estorno único, baixa única por item, domínio dos enums |
| Regras só no frontend | Nenhuma encontrada. Saldo, motivo, modo de baixa e produto inativo são validados no backend |
| N+1 | Nenhum. `stock()` faz duas consultas (catálogo + saldos) — ver observação abaixo |
| Código duplicado | Nada relevante |
| Testes alterados para acomodar comportamento novo sem requisito | **Não.** As alterações da TASK-0009 nos testes antigos decorrem do cartão "Cadastro de clientes" (telefone obrigatório), que é anterior |

### Observações que não viraram correção

- **`InventoryApplicationService.stock()` pagina em memória.** Carrega o catálogo inteiro e todos os
  saldos, filtra e ordena em Java. São duas consultas, não N+1, e para o porte de uma oficina é
  adequado. Vira problema com dezenas de milhares de produtos; nesse momento a filtragem deve descer
  para SQL. Registrado, não corrigido — não é defeito hoje e a Task tem escopo fechado.
- **Endpoints mutáveis exigem sessão autenticada e CSRF, mas não permissão específica.** Isso vale
  para todo o sistema fora do IAM e do Orçamento (`SecurityConfig`: `anyRequest().authenticated()`).
  **Não é um descuido desta Task**: o cartão "Perfis e permissões de acesso" está em "A fazer", e a
  própria TASK-0014 declara "permissão separada para ver custo" como fora de escopo. Corrigir só o
  Estoque criaria uma inconsistência pior. Deve ser tratado de uma vez no cartão de perfis e
  permissões, que precisa cobrir: ver custo, movimentar estoque, ajustar estoque e configurar o modo
  de baixa.

---

## 5. Conclusão

TASK-0014 está **tecnicamente aprovada** após as correções F-01 a F-05.

O defeito F-01 era real e bloqueador, e não estava coberto por nenhum teste — o cenário exercitado
pela suíte original mudava o modo **antes** de lançar o item, nunca depois. Foi encontrado por
inspeção do índice único contra o caminho do ouvinte, e reproduzido antes de ser corrigido.

As duas correções de governança (F-03 e F-04) não mudam comportamento: devolvem à decisão do Owner
duas regras materiais que estavam sendo tratadas como fechadas. Nenhuma regra de negócio foi
inventada nesta revisão para preencher lacuna.

O F-05 deixa uma lição de processo mais importante que o próprio defeito: uma Task que integra
módulos por evento **não pode** declarar gates verdes rodando apenas a própria suíte. O ouvinte de
estoque altera o resultado de um endpoint da OS que a TASK-0014 nunca chama.
