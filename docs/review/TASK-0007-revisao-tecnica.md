# Revisão técnica interna — TASK-0007

- Revisor: `AG-15 — Revisor Técnico` (revisão interna da sprint autônoma)
- Data: `2026-09-15`
- Escopo revisado: módulo `quote`, migrations `V8` e `V9`, contratos `WorkOrderQuery` e `CurrentUser`, telas de orçamento e testes
- Atualizado em `2026-09-15` após a aprovação da `DR-0007` e da permissão `QUOTE_PRESENT`
- Resultado: `APPROVED_WITH_NOTES` — nenhum finding `CRITICAL` ou `HIGH` aberto

> Revisão **interna**, feita pelo mesmo agente que implementou. Não substitui a revisão externa
> independente exigida pelo AGENTS.md antes do `DONE`.

## 1. Aderência à especificação aprovada

A TASK-0001 é a especificação mais detalhada do repositório, e foi lida antes de qualquer código. Conferência item a item:

| Exigência aprovada | Situação |
| --- | --- |
| Schema `workshop` | cumprido |
| `UNIQUE(id, quote_id)` em revisão, item e versão de item | cumprido |
| FKs compostas em `quote_revision_item` (finding `HIGH-01`) | cumprido e provado por teste SQL direto |
| `EXPIRED` não persistido | cumprido: `CHECK` aceita só `DRAFT` e `PRESENTED` |
| Proibição de `is_stale` / `current` | cumprido: obsolescência é derivada a cada leitura |
| Obsolescência por item, não por revisão | cumprido |
| `DR-0001` opção B | cumprido e coberto por dois testes opostos |
| Validade comercial de 7 dias | cumprido |
| `Clock` controlável | cumprido |
| Optimistic locking na apresentação | cumprido |
| Dinheiro em `BigDecimal` / `NUMERIC` | cumprido |
| Proibição de IDOR | cumprido: todo acesso valida `quote.workOrderId` |

Divergências estão registradas na Task e, no caso da fronteira modular, em documento de revisão próprio. Nenhuma foi silenciosa.

## 2. Bugs encontrados e corrigidos durante a Task

Ambos foram encontrados pelos testes desta Task, não por inspeção:

### `BUG-07-01` — apresentação não aparecia depois de aplicada

O update condicional é um `@Modifying` JPQL. Sem `clearAutomatically` e `flushAutomatically`, a releitura na mesma transação devolvia a entidade ainda em cache, com `status = DRAFT`, embora o banco já tivesse a linha apresentada.

O sintoma era grave e enganoso: a apresentação funcionava no banco e "não funcionava" na resposta. Corrigido na anotação.

### `BUG-07-02` — ordem dos itens do orçamento era aleatória

Itens criados na mesma revisão recebem o mesmo `createdAt`, e o desempate era o `UUID` — ou seja, aleatório. Um orçamento com dois itens podia listá-los invertidos entre duas leituras.

Corrigido ordenando por `displayOrder` da apresentação, que é a ordem que o cliente efetivamente vê, com `createdAt` e `id` apenas como desempate final.

### `BUG-07-03` — negar acesso a uma rota longa quebrava a auditoria

Encontrado ao testar a permissão `QUOTE_PRESENT`. O registro de acesso negado usa
`método + caminho` como alvo, e `iam.audit_event.target_id` tem 120 caracteres. O caminho de
apresentação tem três UUIDs e ultrapassa esse limite: a gravação da auditoria falhava e a negação
chegava ao cliente como erro de servidor em vez de `403`.

O defeito é anterior a esta Task — qualquer rota longa o dispararia — e só apareceu agora porque
nenhum endpoint protegido era longo o bastante.

Corrigido em duas camadas: o alvo da auditoria é truncado na origem, e o manipulador de acesso
negado passou a registrar falha de auditoria em log de erro sem deixar de responder `403`. A negação
não pode depender do sucesso de um registro acessório.

## 3. Dinheiro

A `DR-0007` foi aprovada em 2026-09-15 e está implementada: quantidade e preço unitário com até
quatro casas, produto calculado com a precisão integral do `BigDecimal`, resultado do item levado a
duas casas com `HALF_UP`, e total da apresentação como soma das parcelas já arredondadas.

Os operandos nunca são arredondados antes da multiplicação — arredondar primeiro daria um total
diferente do que a conta real dá.

Cobertura de fronteira: terceira casa menor, igual e maior que cinco; quantidade fracionária; preço
unitário com quatro casas; e a soma de três itens de `0,125`, que pela política resulta em `0,39` e
não em `0,38`. Há também teste de arquitetura provando que **nenhum** campo, retorno ou parâmetro do
módulo usa `double`, `float`, `Double` ou `Float`.

As duas escalas do sistema (`NUMERIC(15,2)` e `NUMERIC(19,4)`) permanecem, por decisão explícita: a
conversão ocorre na direção segura e não haverá migração especulativa apenas por uniformidade.

## 4. Histórico

Não existe nenhuma operação que atualize descrição, quantidade ou preço de uma versão comercial. Não existe `DELETE`. A única escrita sobre uma linha já criada é a apresentação da revisão, que altera situação e validade, nunca conteúdo comercial.

Teste específico prova que apresentar A-v2 não altera A-v1.

## 5. Segurança

Endpoints herdam autenticação obrigatória e CSRF, ambos verificados por teste, e apresentar exige
`QUOTE_PRESENT`.

O IAM foi tocado em dois pontos, ambos declarados: a migration `V9` cadastra a permissão nova e a
concede aos perfis aprovados, e o registro de acesso negado foi corrigido (`BUG-07-03`). Nenhuma
regra de autenticação, sessão ou CSRF foi alterada, e nenhuma permissão existente mudou de perfil.

O teste usa **sessão real**, com login e troca obrigatória de senha, em vez de principal simulado. Isso foi necessário porque `created_by` vem do usuário autenticado, e um principal falso não provaria que a autoria é registrada — de fato o teste confere que `createdBy` é o `id` do dono no banco.

A proteção contra IDOR é ativa e testada: um `quoteId` válido acessado pelo caminho de outra OS responde `404`, sem confirmar que o recurso existe em outro lugar.

`CurrentUser` foi acrescentado à API pública do IAM. Expõe apenas a identidade do autor; não concede nem verifica permissão, e não expõe o `IamPrincipal`.

## 6. Concorrência

A apresentação usa update condicional por `version`, testado diretamente pelo port: com versão divergente nada é aplicado e a revisão permanece `DRAFT`.

## 7. Findings

### `F-07-01` — `MEDIUM` — O ponto de serialização ainda não cobre DECIDE × PRESENT

A arquitetura aprovada (seções 33 e 34) pede que apresentar e decidir se coordenem no mesmo contexto lógico do `QuoteItem`. Hoje o ponto de serialização é a **revisão**, porque decisão ainda não existe.

Isso não é violável agora: não há operação de decisão para competir com a apresentação.

Ação: **requisito de entrada da próxima Task.** A submissão de decisão terá de reconferir obsolescência dentro da própria transação e coordenar com a apresentação por item, não por revisão. Registrado aqui para não se perder.

### `F-07-02` — `RESOLVIDO` em 2026-09-15 — Apresentar exige `QUOTE_PRESENT`

O proprietário decidiu: apresentar passou a exigir a permissão explícita `QUOTE_PRESENT`, cadastrada
pela migration `V9` e concedida por perfil a `DONO` e `GERENTE_ADMINISTRATIVO`.

A verificação é do backend, por `@PreAuthorize`. A interface desabilita o botão quando a sessão não
tem a permissão, e isso é conveniência de UX: há teste provando que a chamada direta à API sem a
permissão responde `403` e que a revisão continua em `DRAFT`.

Criar orçamento e compor revisão continuam exigindo apenas sessão autenticada — nenhum dos dois
apresenta valor ao cliente.

### `F-07-03` — `MEDIUM` — Leitura do orçamento faz cinco consultas, e a listagem multiplica

Carregar um orçamento completo executa cinco consultas, porque a derivação de obsolescência precisa de todas as versões e de todas as apresentações. `GET /quotes` carrega cada orçamento assim.

Justificativa: a derivação é obrigatória (`is_stale` é proibido), e otimizar com projeção antes de existir volume seria engenharia prematura.

Ação: aceito. Revisar se uma OS passar a ter muitos orçamentos, ou quando a listagem precisar de paginação.

### `F-07-04` — `RESOLVIDO` em 2026-09-15 — O total lido do banco é conferido

A leitura passou a comparar o total persistido com a fórmula e a falhar em divergência
(`QUOTE_ITEM_REVISION_TOTAL_MISMATCH`), em vez de recalcular em silêncio. Uma linha adulterada
diretamente no banco agora é denunciada, e há teste que a produz de propósito.

### `F-07-05` — `LOW` — Não existe descarte de rascunho

Uma revisão `DRAFT` criada por engano permanece. Rascunho não produz efeito comercial, não aparece ao cliente e não interfere na obsolescência, então o custo é apenas visual.

Ação: aceito; decidir junto com a correção de item lançado (`F-06-01`).

### `F-07-06` — `LOW` — Ajuste de estado durante a renderização no formulário de nova revisão

O formulário reinicia suas linhas quando a última revisão do orçamento muda, ajustando estado durante a renderização. É padrão sancionado pelo React e não gera laço, mas é mais sutil que o resto da tela.

Ação: aceito.

## 8. Itens verificados sem finding

```text
violação de fronteira modular              nenhuma; ModularityTest passa com quote → workorder, iam
acesso a repository de outro módulo        nenhum
falta de constraint                        nenhuma
falta de FK                                nenhuma
coluna de estado derivado                  nenhuma
alteração retroativa                       nenhuma
autorização incorreta                      nenhuma
endpoint inseguro                          nenhum
IDOR                                       coberto e testado
regra apenas no frontend                   nenhuma; o seletor e a validação do formulário
                                           espelham regras que o backend também aplica
dado duplicado                             quote_id redundante é exigido pelo modelo aprovado
                                           para viabilizar as FKs compostas
regra de negócio inventada                 nenhuma sem registro; sete regras novas estão
                                           declaradas como RN-T7-01 a RN-T7-07
arredondamento silencioso                  nenhum
multi-tenancy                              ausente
```

## 9. Regressão

Nenhum teste existente foi enfraquecido. `WorkOrderApplicationService` passou a implementar
`WorkOrderQuery` sem alterar nenhum comportamento anterior, e a suíte completa de backend e de
frontend permanece verde.

## 10. Conclusão

Apto para revisão externa. `F-07-01` deve ser tratado como pré-requisito da Task de decisão pública, e
`F-07-02` como pergunta ao proprietário antes do uso operacional multiusuário.
