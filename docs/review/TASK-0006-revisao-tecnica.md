# Revisão técnica interna — TASK-0006

- Revisor: `AG-15 — Revisor Técnico` (revisão interna da sprint autônoma)
- Data: `2026-09-14`
- Escopo revisado: lançamento de item físico na OS, migration `V7`, dependência `workorder → productcatalog`, frontend do detalhe da OS e testes
- Resultado: `APPROVED_WITH_NOTES` — nenhum finding `CRITICAL` ou `HIGH` aberto

> Esta é uma revisão **interna**, feita pelo mesmo agente que implementou. Ela não substitui a revisão externa independente exigida pelo AGENTS.md antes do `DONE` definitivo.

## 1. Fronteira modular

`workorder` passou a declarar `allowedDependencies = {"crm", "servicecatalog", "productcatalog"}` e `ApplicationModules.verify()` passa.

O acesso ocorre exclusivamente por `ProductCatalogQuery`. Nenhum repositório, entidade ou tabela do catálogo é tocado pela OS. A tabela `workorder.work_order_product` referencia `productcatalog.product` por FK — o mesmo padrão já aprovado na `V5` para `servicecatalog.service` e `crm.customer`.

## 2. Regra de negócio inventada

Duas recusas não existiam em documento aprovado e foram criadas nesta Task. Ambas são conservadoras, no sentido de **recusar** em vez de supor:

| Recusa | Alternativa rejeitada | Motivo |
| --- | --- | --- |
| produto inativo | lançar mesmo assim | AG-04 seção 13 diz que inativar impede novos usos |
| produto sem preço de venda | assumir zero, ou pedir o preço no request | assumir zero transformaria ausência de decisão em brinde; aceitar preço no request tornaria o snapshot um campo livre |

Ambas estão registradas como `RN-02` e `RN-03` da Task e devem ser confirmadas pelo proprietário. Nenhuma das duas cria dinheiro: elas apenas impedem que dinheiro seja criado por omissão.

## 3. Dinheiro e totalização

Nenhum total é calculado ou persistido pelo backend. A OS guarda `quantity` e `unit_price` e nada mais.

Isso é deliberado: o total exige uma regra de arredondamento que ainda não foi decidida (`DR-0006`), e persistir um derivado antes disso criaria duas fontes de verdade capazes de divergir por um centavo.

`BigDecimal`/`NUMERIC` em toda a cadeia. Nenhum `double`.

## 4. Histórico e snapshot

O item copia descrição, código interno, unidade e preço unitário no instante do lançamento. Há teste específico que altera descrição, código, preço e situação do produto **depois** do lançamento e confirma que a OS não muda.

A FK para a OS impede apagar uma OS que possua item físico vinculado — também coberto por teste.

Não existe `PUT` nem `DELETE` de item lançado.

## 5. Segurança

Endpoint herda a autenticação obrigatória e o CSRF já existentes; ambos verificados por teste (`401` sem sessão, `403` sem token). Nenhuma permissão granular nova.

O request não aceita preço, descrição nem unidade: o cliente escolhe **qual** produto e **quanta** quantidade, nunca **por quanto**.

## 6. Concorrência

Analisada e considerada de baixo risco nesta etapa:

- Dois lançamentos simultâneos do mesmo produto geram duas linhas. É o comportamento pretendido (`RN-08`), porque lançamentos podem ocorrer em momentos e preços diferentes.
- Existe uma janela em que o produto pode ser inativado entre a leitura do catálogo e a gravação do item. A consequência é uma linha de um produto recém-inativado, com o preço que estava vigente na leitura. Nenhum valor é inventado e nenhum saldo é afetado.
- Não há disputa por recurso escasso, porque estoque não é tocado. Quando o módulo Estoque existir, a reserva precisará de proteção transacional própria (AG-04 seções 110 a 112) — e é ali, não aqui, que o bloqueio deve ser implementado.

## 7. Findings

### `F-06-01` — `MEDIUM` — Não existe correção de item lançado por engano

Um item lançado com quantidade errada não pode ser removido nem alterado.

Justificativa da omissão: corrigir histórico é uma decisão sobre rastreabilidade — estorno, cancelamento com motivo, ou edição simples — e escolhê-la sozinho definiria como a oficina audita seus próprios erros.

Ação: fora do escopo declarado da Task, registrado como risco operacional. Deve ser tratado no workflow da OS, com decisão explícita do proprietário.

### `F-06-02` — `MEDIUM` — Total exibido existe apenas no frontend

O detalhe da OS soma serviços e itens para exibição. Nenhum desses valores é validado ou persistido pelo backend.

Justificativa: o total exibido **não é compromisso comercial**. Nenhuma decisão do backend depende dele, e a soma de apresentação já era a prática aprovada na TASK-0004 para serviços.

Ação: aceito enquanto não existir Orçamento. Quando o Orçamento existir, o total apresentado ao cliente terá de ser calculado, versionado e congelado no servidor — o valor de tela não pode virar a base de uma aprovação.

### `F-06-03` — `LOW` — A OS registra consumo sem qualquer efeito físico

Como não há Estoque, a OS pode registrar mais itens do que a oficina possui.

Ação: aceito e documentado. É consequência direta da ordem do roadmap, não de uma omissão acidental.

### `F-06-04` — `LOW` — Quantidade com mais de três casas é rejeitada mesmo quando representável

`2.5000` é recusado por exceder a escala, ainda que o valor seja equivalente a `2.5`.

Ação: aceito. A regra é previsível e conservadora; será revista com a decisão de `DR-0006`.

## 8. Itens verificados sem finding

```text
violação de fronteira modular          nenhuma
falta de constraint                    nenhuma
falta de FK                            nenhuma
alteração retroativa                   nenhuma
snapshot ausente                       nenhum
autorização incorreta                  nenhuma
endpoint inseguro                      nenhum
regra existente apenas no frontend     nenhuma: o filtro do seletor espelha validações que o backend também aplica
dado duplicado                         nenhum: a FK e o snapshot respondem perguntas diferentes
total derivado persistido              nenhum, deliberadamente
```

## 9. Regressão

`Task0004VerticalIntegrationTest` foi mantido e teve apenas a limpeza de dados ampliada para as novas tabelas, sem alteração de asserção. Os fixtures de frontend da TASK-0004 receberam o campo `products` e a rota `GET /api/products`, sem enfraquecer nenhuma verificação existente.

## 10. Conclusão

Apto para revisão externa. Não apto a `DONE` enquanto a suíte completa `mvn test` não for executada — ver a seção de bloqueios do relatório da sprint.
