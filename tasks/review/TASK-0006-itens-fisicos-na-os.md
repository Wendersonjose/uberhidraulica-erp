# TASK-0006 — Lançamento de itens físicos na Ordem de Serviço

## Identificação

- Status: `REVIEW`
- Prioridade: `HIGH`
- Criada em: `2026-09-14`
- Proprietário principal: Oficina — `AG-03`
- Responsável atual: `AG-11`

## Objetivo

Permitir que a OS registre o que foi materialmente aplicado no veículo, e não apenas a mão de obra: lançar peças, fluidos e insumos do catálogo com quantidade, preservando como snapshot a descrição, o código interno, a unidade e o preço unitário vigentes no instante do lançamento.

## Contexto

Até a TASK-0005 a OS só aceitava serviços. O catálogo físico passou a existir, mas nada o consumia: uma OS ainda não conseguia responder "quais peças foram utilizadas?", que é uma das perguntas centrais do sistema segundo o README.

Esta Task é a primeira travessia real entre o catálogo físico e a Oficina e valida o contrato público criado na TASK-0005.

## Escopo

- Item físico da OS com identidade própria, referência ao produto do catálogo e snapshot comercial: descrição, código interno, unidade e preço unitário.
- Quantidade positiva, com até três casas decimais, na unidade-base do produto.
- `POST /api/work-orders/{id}/products`.
- `GET /api/work-orders` e `GET /api/work-orders/{id}` passam a devolver `products`.
- Migration Flyway `V7` com `workorder.work_order_product`, FKs reais para a OS e para o produto.
- Dependência modular `workorder → productcatalog` declarada.
- Tela de detalhe da OS: lista de itens físicos, lançamento e subtotais de apresentação.
- Testes de integração PostgreSQL/Testcontainers e testes de interface.

## Fora do escopo

Saldo, reserva, disponibilidade e movimentação de estoque; baixa por consumo; compra; fornecedor; custo real e custo médio; desconto; acréscimo; total comercial persistido; imposto; remoção ou alteração de item já lançado; orçamento, versionamento comercial e aprovação; workflow da OS; comissão.

## Regras existentes utilizadas

1. Snapshot na OS: quando um item do catálogo é inserido em uma OS, os valores relevantes são copiados e o catálogo futuro não altera o histórico (AG-04, seções 38 e 87).
2. Inativar item impede novos usos e não altera OS antigas (AG-04, seções 12 e 13).
3. Módulos se comunicam por contrato público; a OS consome `ProductCatalogQuery` e nunca o repositório ou a entidade do catálogo.
4. Controller → Application → Domain → Repository Port → Persistence Adapter.
5. Dinheiro em `BigDecimal`/`NUMERIC`; nunca `double` ou `float`.
6. Registros históricos não sofrem exclusão física.
7. Endpoints internos exigem sessão autenticada e mutações preservam CSRF.
8. Persistência PostgreSQL é testada com Testcontainers.
9. O MVP é de oficina única.

## Regras definidas nesta Task

1. `RN-01` — O item físico da OS é um snapshot. Descrição, código interno, unidade e preço unitário são copiados do catálogo no instante do lançamento e nunca reescritos por alteração posterior do produto.
2. `RN-02` — Produto inativo não pode ser lançado. A recusa responde `409 PRODUCT_INACTIVE`.
3. `RN-03` — Produto sem preço de venda definido não pode ser lançado. A recusa responde `409 PRODUCT_WITHOUT_SALE_PRICE`. **Justificativa:** cobrar exige um preço que alguém definiu; arbitrar um valor no lançamento inventaria dinheiro, e assumir zero transformaria ausência de decisão em brinde.
4. `RN-04` — Quantidade é obrigatória, estritamente positiva e limitada a três casas decimais, na unidade-base do produto. A precisão é provisória e está registrada em `DR-0006`.
5. `RN-05` — Nenhum total é calculado ou persistido pelo backend. A OS guarda quantidade e preço unitário; qualquer totalização é apresentação até que a regra de arredondamento comercial seja decidida.
6. `RN-06` — O lançamento não consulta, reserva nem baixa saldo de estoque. Enquanto o módulo Estoque não existir, a OS não afirma nada sobre disponibilidade física.
7. `RN-07` — Não existe remoção nem alteração de item já lançado nesta Task. Corrigir um lançamento é uma decisão de histórico que ainda não foi tomada.
8. `RN-08` — Um mesmo produto pode ser lançado mais de uma vez na mesma OS; cada lançamento é uma linha própria, porque podem ocorrer em momentos e preços diferentes.

## Critérios de aceite

1. `CA-01` — Lançar produto ativo com preço e quantidade válida retorna `201` e a OS com o item, incluindo descrição, código interno, unidade, quantidade e preço unitário.
2. `CA-02` — Alterar descrição, código interno, preço ou situação do produto no catálogo após o lançamento não modifica o item já lançado na OS.
3. `CA-03` — Produto inexistente retorna `404 PRODUCT_NOT_FOUND`; OS inexistente retorna `404 WORK_ORDER_NOT_FOUND`.
4. `CA-04` — Produto inativo retorna `409 PRODUCT_INACTIVE`.
5. `CA-05` — Produto sem preço de venda retorna `409 PRODUCT_WITHOUT_SALE_PRICE`.
6. `CA-06` — Quantidade zero, negativa ou com mais de três casas decimais retorna `400 VALIDATION_FAILED` e nada é gravado.
7. `CA-07` — Vários itens podem ser lançados e a ordem de inclusão é preservada na consulta.
8. `CA-08` — Produto sem código interno gera item sem código interno, sem substituição por texto vazio.
9. `CA-09` — As constraints da `V7` rejeitam, em PostgreSQL real, produto inexistente, OS inexistente, quantidade não positiva, preço negativo, descrição e unidade em branco; e impedem apagar uma OS que possua item vinculado.
10. `CA-10` — O endpoint exige autenticação e CSRF.
11. `CA-11` — `ApplicationModules.verify()` continua passando com a dependência `workorder → productcatalog` declarada.
12. `CA-12` — O detalhe da OS lista os itens físicos, separa subtotal de serviços, subtotal de itens e total da OS, e o seletor de lançamento só oferece produto ativo com preço de venda.
13. `CA-13` — `mvn test`, gates do frontend e `git diff --check` passam, sem regressão nas verticais de Cliente, Veículo, Serviço e OS.

## Módulos envolvidos

- `workorder` (proprietário), consumindo `productcatalog` pelo contrato público.
- `crm` e `servicecatalog` inalterados.
- Agentes: AG-00, AG-02, AG-03, AG-04, AG-09, AG-10, AG-11, AG-12, AG-13 e AG-15.

## Decision Requests

- `DR-0006` — `OPEN`, não bloqueadora nesta Task, porém **determinante** para a totalização comercial. Enquanto estiver aberta, a precisão de três casas e a ausência de total persistido são explicitamente provisórias.

## Riscos

1. **Preço unitário sem regra de reajuste** — se o preço do catálogo mudar entre o lançamento e a apresentação ao cliente, a OS mantém o valor do lançamento. Isso é intencional, mas a decisão de "qual preço vale" pertence ao Orçamento e ainda não existe. Probabilidade `MÉDIA`, impacto `MÉDIO`.
2. **Ausência de estoque** — a OS registra consumo comercial sem qualquer efeito físico. Até o módulo Estoque existir, o sistema pode registrar mais itens do que a oficina possui. Probabilidade `ALTA`, impacto `MÉDIO`, mitigado por ser um registro declaradamente comercial.
3. **Item lançado por engano** — não há remoção. Probabilidade `MÉDIA`, impacto `BAIXO` no MVP, mas exige decisão de histórico antes do uso operacional intenso.

## Resultado dos gates

- `mvn test`: `55` testes, `0` failures, `0` errors, `0` skipped, `BUILD SUCCESS`.
- `ModularityTest` / `ApplicationModules.verify()`: `PASS` com a dependência `workorder → productcatalog`.
- `Task0006ProductItemIntegrationTest`: `5` testes `PASS` em PostgreSQL 18 real via Testcontainers.
- `Task0004VerticalIntegrationTest`: `6` testes `PASS`, sem alteração de asserção.
- Frontend: `55` testes `PASS`; TypeScript, build e lint verdes, `0` warnings.
- `git diff --check`: `PASS`.

## Critérios de aceite

`13/13 IMPLEMENTADOS`.

## Revisão

- Revisão interna no papel do `AG-15`: `APPROVED_WITH_NOTES`, em `docs/review/TASK-0006-revisao-tecnica.md`.
- Findings abertos: `F-06-01` e `F-06-02` `MEDIUM` aceitos com justificativa, `F-06-03` e `F-06-04` `LOW`. Nenhum `CRITICAL` ou `HIGH`.
- Revisão externa independente: `PENDENTE`. A Task permanece em `REVIEW` e não é declarada `DONE`.

## Conferência solicitada ao proprietário

`RN-02` e `RN-03` são regras novas criadas nesta Task, sem documento aprovado anterior. Ambas recusam o lançamento em vez de supor um valor: produto inativo e produto sem preço de venda não podem ser lançados. Requerem confirmação explícita, especialmente `RN-03`, que impede cobrar item cujo preço ninguém definiu.

## Histórico

- 2026-09-14 — Task criada como continuação direta da TASK-0005, sendo o primeiro consumo real do contrato público do catálogo de produtos.
- 2026-09-14 — Backend, migration `V7`, frontend e testes implementados; todos os gates aplicáveis verdes; revisão interna concluída. Status movido para `REVIEW`, aguardando revisão externa independente.
