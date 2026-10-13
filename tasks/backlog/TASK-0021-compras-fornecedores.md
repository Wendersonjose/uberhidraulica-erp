# TASK-0021 — Compras e Fornecedores (inclui importação de NF-e modelo 55)

## Identificação

- Status: `IN_PROGRESS` (I1, I2 e I3 concluídos e validados com a NF-e 21 real; I4 e I5 em aberto)
- Prioridade: alta
- Criada em: `2026-10-13`
- Origem: briefing do proprietário de 2026-10-13; `DR-0021` (DECIDED) libera a abertura
- Decisões relacionadas: `DR-0021`, `DR-0022` (PROPOSTA), `DR-0007` (política monetária), `DR-0015`/`DR-0018` (Financeiro/caixa)
- Proprietário principal: Compras & Fornecedores — `AG-05`
- Participam: `AG-02`, `AG-04` (entrada de estoque e custo médio), `AG-06` (obrigações), `AG-09`, `AG-10`, `AG-11`, `AG-12`, `AG-13`, `AG-15`
- Arquitetura/dados/segurança: `docs/architecture/compras/TASK-0021-arquitetura-compras.md`

## Objetivo

Fluxo operacional de compra rastreável: fornecedor → (solicitação → cotação → pedido, ou compra direta, ou NF-e XML) →
recebimento físico → entrada em estoque com custo → obrigações no Financeiro, sem misturar compra, recebimento, documento
fiscal e pagamento (`AG-05` §130).

## Regras fixas (decididas pelo owner)

- RN-01 Pedido de compra não movimenta estoque; somente recebimento físico confirmado movimenta.
- RN-02 Importar XML não é receber nem pagar; informações de cobrança/pagamento do XML não marcam nada como pago.
- RN-03 A mesma NF-e não é importada duas vezes como compra efetiva (idempotência, auditoria, transação).
- RN-04 NF-e de R$ 1.943,10 em 3 × R$ 647,70 ⇒ 1 compra, 3 obrigações, valor econômico contado uma vez.
- RN-05 Autorização declarada no XML não substitui consulta oficial; a Task não afirma validade fiscal.
- RN-06 Nada de classificação tributária inventada: parâmetros ficam configuráveis e "não validados".
- RN-07 Estornos rastreáveis; histórico operacional nunca é apagado para corrigir lançamento.

## Incrementos (ordem de prioridade do owner)

| # | Entrega | Migration | Estado |
| --- | --- | --- | --- |
| I1 | Domínio puro de NF-e: chave de acesso, CNPJ/CPF (inclui alfanumérico), GTIN, parser XML seguro, validador, testes | nenhuma | **CONCLUÍDO** (31 testes) |
| I2 | Fornecedores PF/PJ, contatos, situação, condições comerciais, trilha de auditoria, API e telas | `V22` (`V22__purchasing_suppliers.sql`) | **CONCLUÍDO** (16 testes de integração + 22 unitários; checkpoint de banco executado) |
| I3 | Prévia e confirmação de NF-e: fornecedor, vínculo de itens, compra, documento, contas a pagar parceladas | `V23` (`V23__purchasing_purchase_nfe.sql`) | **CONCLUÍDO** (21 testes de integração + 7 de aceitação com a NF-e 21 real + 28 unitários; telas entregues) |
| I4 | Recebimento físico total/parcial, divergência, entrada em estoque com custo, estorno | `V24` | próximo (decisões liberadas) |
| I5 | Solicitação, cotação multi-fornecedor, comparação, pedido, aprovação, compra direta, vínculo com OS | `V25+` | pendente |
| I6 | Devolução, crédito do fornecedor, histórico de preços, relatórios | `V26+` | pendente |

## Bloqueios

1. ~~Versão Flyway do Supabase~~ — **resolvido**: o owner confirmou em 2026-10-13 `erp_meta`, última migration `V21` (cash session, `success = true`); a próxima é `V22`. Nenhuma migration foi executada no Supabase.
2. **NF-e real** (`31261067757350000178550010000000211010202158-procNFe.xml`) ainda não está no ambiente; hoje só XML sintético, nunca confundido com a nota fiscal verdadeira.
3. `DR-0022` D1–D7 aguardam decisão do owner antes de qualquer regra de custo, rateio, divergência, obrigação ou importação definitiva.
4. ~~Checkpoint de banco do I2 não executado~~ — **resolvido em 2026-10-13**: Docker subiu, `SupplierIntegrationTest`
   rodou contra PostgreSQL 18 real e a suíte completa foi executada. Ver "Resultado do I2".

## Critérios de aceite do I1 (atendidos)

- DADO uma chave de 44 posições QUANDO o dígito verificador confere ENTÃO é aceita e decodificada (UF 31, AAMM 2610, CNPJ do
  emitente, modelo 55, série 1, número 21); QUANDO difere ENTÃO é recusada.
- DADO XML com DOCTYPE/entidade externa ENTÃO é recusado (XXE); XML > 2 MB, vazio ou não-NF-e ENTÃO é recusado.
- DADO nota destinada a outro CNPJ, emitida pela própria oficina, em homologação, sem protocolo, não autorizada, de modelo
  diferente de 55, ou com itens que não somam o total ENTÃO há achado `BLOCKING`.
- DADO emitente com raiz de CNPJ diferente da oficina ENTÃO é informado como pessoa jurídica distinta; raiz igual gera alerta.
- DADO sempre ENTÃO o resultado informa que a consulta oficial e a validade criptográfica da assinatura não foram feitas.

## Fora do escopo

Emissão de NFS-e/NF-e, consulta oficial à SEFAZ, certificado A1, comissões, rentabilidade, multi-tenant, importação de
CT-e, frete rateado entre transportadora e fornecedor (custo adicional separado, I3+).

## Resultado do I2 (2026-10-13)

Entregue: `V22`, módulo `purchasing` (domínio, porta, serviço, adaptador JDBC, API REST, tratamento de erros), permissões
`PURCHASE_VIEW`/`PURCHASE_SUPPLIER_MANAGE`, telas de Fornecedores (lista com busca/filtros, cadastro, edição, detalhe, contatos,
inativação/reativação com motivo, histórico), testes. Reconciliação com a branch antiga: ver
`docs/architecture/compras/TASK-0021-arquitetura-compras.md` §3.1.

### Checkpoint de banco (2026-10-13, PostgreSQL 18 real via Testcontainers)

| Verificação | Resultado |
| --- | --- |
| Compilação Maven | OK |
| Flyway: 22 migrations aplicadas da `V1` à `V22` em banco limpo | OK (`now at version v22`) |
| `ModularityTest` (`ApplicationModules.verify()`) com o módulo `purchasing` | passou |
| Arquitetura monetária: `PurchasingMonetaryArchitectureTest` (sem `double`/`float`) | passou |
| `SupplierIntegrationTest` — 16 cenários, inclui autenticação, permissões e mascaramento de CPF | **16/16** |
| Unitários de Compras: `SupplierServiceTest` 12, `SupplierTest` 10, NF-e (chave, CNPJ/GTIN, parser, validador) 29 | passaram |
| IAM com as permissões novas: `IamAuthenticationIntegrationTest` 11, `ModulePermissionsIntegrationTest` 7 | passaram |
| Sem regressão em OS: `Task0004` 6, `Task0006` 5, `Task0012` 8 | passaram |
| Sem regressão em Estoque: `Task0014InventoryIntegrationTest` 16, `StockAverageCostTest` 5 | passaram |
| Sem regressão em Financeiro: `Task0015` 40, `Task0016CashSession` 5, dashboard 3, domínio 17 | passaram |
| Sem regressão em Orçamento: `Task0007` 19, `Task0008` 20, `Task0013` 5 | passaram |
| Frontend: `tsc -p tsconfig.app.json --noEmit`, oxlint, `npm run build` | saída 0 |
| Frontend: Vitest | **157/157** em 19 arquivos |

Suíte backend completa: **343 testes executados em 40 classes, 0 falhas de asserção e 20 *errors***, todos os 20 na
mesma classe e pela causa de ambiente descrita abaixo. Com a reexecução dessa classe e do `SupplierIntegrationTest`
atualizado (36 testes, `BUILD SUCCESS`), o conjunto efetivamente verificado é de 344 testes sem vermelho remanescente.

**Falha de ambiente, não de código — registrada por honestidade.** Na execução completa a classe
`Task0008PublicQuoteDecisionIntegrationTest` acusou 20 *errors* e o Maven terminou em `BUILD FAILURE`. A causa no log
não é o teste: é `java.nio.file.FileSystemException: …\crm\package-info.class: Não existem recursos de sistema
suficientes para concluir o serviço solicitado` durante o `ClassPathScanning` — esgotamento de recursos do Windows
com a RAM livre em ~0,3 GB (notebook de pouca memória com o Docker ocupando ~5 GB). O arquivo está íntegro e a classe
seguinte passou na mesma execução. Reexecutada isoladamente com memória livre: **20/20**, `BUILD SUCCESS`.
Pelo mesmo motivo o Vitest em paralelo perdeu *workers* (3 arquivos, 16 *errors*); com `--maxWorkers=1`: **157/157**.

Nada de I2 foi dado como verde com gate vermelho: os dois casos vermelhos foram reexecutados até resultado limpo e
a causa está documentada acima.

### Defeitos encontrados e corrigidos no checkpoint

1. **Defeito de produção (`SupplierController`)**: `relatedParty` e `primary` eram `boolean` primitivo nos records de
   entrada. Campo ausente no JSON fazia o Jackson falhar com "Cannot map `null` into type `boolean`" e **todo cadastro
   de fornecedor respondia 400**. Corrigido para `Boolean` com normalização `!= null &&`, padrão já usado em
   `ProductCatalogController`. O formulário do frontend sempre envia o campo, por isso os testes de tela nunca viram.
   Coberto agora por teste nomeado (`optionalBooleansMayBeOmittedAndDefaultToFalse`), além de ser exercido
   implicitamente por todos os outros cenários.
2. **Defeito de teste (`SupplierIntegrationTest`)**: um CPF fixo era usado em dois cenários; como a classe não limpa
   fornecedores entre testes e `uq_supplier_document` é global, o segundo recebia 409 em vez de 201. Corrigido com
   gerador de CPF válido e único por chamada.

Observação: a suíte do frontend tem testes antigos sensíveis a carga (`work-order-products` estourou o tempo em uma execução
paralela e passou em outra). Os testes novos colam texto e têm folga de tempo para não aumentar essa fragilidade.

Contrato REST e roteiro de teste manual (Insomnia): `docs/api/TASK-0021-contrato-rest-fornecedores.md`.

## Resultado do I3 (2026-10-13)

Entregue: `V23`, domínio das decisões aprovadas (custo, rateio, conversão de unidade, plano de parcelas,
verificação de destinatário), contrato público `PurchasePayables` no Financeiro, `gtin`/`ncm` no catálogo,
prévia e confirmação de NF-e, cancelamento e anulação, configuração do módulo, API e telas.

| Verificação | Resultado |
| --- | --- |
| `NfeImportIntegrationTest` — 21 cenários das decisões D1–D11 | **21/21** |
| `RealNfe21AcceptanceTest` — NF-e 21 **real** | **7/7** |
| `AcquisitionPolicyTest` — domínio puro de D1, D2, D7, D4/D11, D9 | **28/28** |
| Flyway `V1`→`V23` em banco limpo | OK |
| `ModularityTest` com os contratos novos | passou |
| Catálogo 8/8, Financeiro 40/40, IAM 11+7 | sem regressão |
| Frontend: Vitest 166/166, `tsc`, lint, build | saída 0 |

### A NF-e 21 real

Validada de ponta a ponta: 12 itens somando **1.943,10**, três duplicatas de **647,70** (23/10, 30/10,
06/11), destinatário `01.637.003/0001-01` reconhecido como o estabelecimento autorizado, emitente
`67.757.350/0001-78` tratado como pessoa jurídica distinta (raízes diferentes, apesar dos nomes parecidos e do
mesmo endereço), protocolo `131267983727896` gravado, três contas a pagar **em aberto** e **nenhuma
movimentação de estoque**.

O arquivo versionado (`src/test/resources/nfe/nfe-21-real-redigida.xml`) é o documento real com o conteúdo de
`<SignatureValue>` e `<X509Certificate>` substituído por marcador: esses campos carregam certificado e dados
pessoais. A estrutura da assinatura foi preservada e a validade criptográfica não é verificada nesta Task
(`D12`), então nada de valor de teste se perde.

**Achado operacional:** 4 dos 12 itens vêm com `uCom = KIT`, que a `D7` aprovada lista entre as unidades que
exigem fator de conversão. Não foi criada equivalência automática para KIT — seria sobrepor a decisão do
proprietário. Na prática o operador informa o fator uma vez por item, e o vínculo passa a lembrá-lo.

### Pendência conhecida do I3, a fechar no I4

Cancelar compra ainda **não** verifica ausência de recebimento ativo, porque recebimento não existe antes do
`I4`. No `I3` o invariante não pode ser violado (não há como existir recebimento), mas a trava da `D5` tem de
ser ligada junto com `goods_receipt`, em `PurchaseService.cancel`.
