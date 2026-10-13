# TASK-0021 — Compras e Fornecedores (inclui importação de NF-e modelo 55)

## Identificação

- Status: `IN_PROGRESS` (I1 concluído; I2 implementado, pendente apenas o checkpoint de banco; I3+ aguardam o DR-0022)
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
| I2 | Fornecedores PF/PJ, contatos, situação, condições comerciais, trilha de auditoria, API e telas | `V22` (`V22__purchasing_suppliers.sql`) | **implementado**; falta executar a suíte de banco (Testcontainers) |
| I3 | Prévia e confirmação de NF-e: fornecedor, vínculo de itens, compra, documento, contas a pagar parceladas | `V23` | bloqueado: Flyway; `DR-0022` D4–D7, D9, D11 |
| I4 | Recebimento físico total/parcial, divergência, entrada em estoque com custo, estorno | `V24` | bloqueado: Flyway; `DR-0022` D1–D3, D7 |
| I5 | Solicitação, cotação multi-fornecedor, comparação, pedido, aprovação, compra direta, vínculo com OS | `V25+` | pendente |
| I6 | Devolução, crédito do fornecedor, histórico de preços, relatórios | `V26+` | pendente |

## Bloqueios

1. ~~Versão Flyway do Supabase~~ — **resolvido**: o owner confirmou em 2026-10-13 `erp_meta`, última migration `V21` (cash session, `success = true`); a próxima é `V22`. Nenhuma migration foi executada no Supabase.
2. **NF-e real** (`31261067757350000178550010000000211010202158-procNFe.xml`) ainda não está no ambiente; hoje só XML sintético, nunca confundido com a nota fiscal verdadeira.
3. `DR-0022` D1–D7 aguardam decisão do owner antes de qualquer regra de custo, rateio, divergência, obrigação ou importação definitiva.
4. **Checkpoint de banco do I2 não executado**: o Docker Desktop não iniciou (socket travado `dockerInference`/`engine.sock`, problema de ambiente já conhecido). `SupplierIntegrationTest` e a suíte backend completa precisam rodar antes do I2 ser dado como DONE.

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

| Verificação | Resultado |
| --- | --- |
| Backend, unitários (domínio e serviço com repositório em memória), arquitetura (sem float) e `ModularityTest` | passaram |
| Frontend: `tsc -b`, oxlint, Vitest (157 testes, 19 arquivos, 20 novos de Compras) | passaram |
| Backend, integração PostgreSQL (`SupplierIntegrationTest`, 14 cenários, mais `IamAuthenticationIntegrationTest` com o catálogo atualizado) | **não executados** — Docker indisponível |
| Suíte backend completa | **não executada** |

Observação: a suíte do frontend tem testes antigos sensíveis a carga (`work-order-products` estourou o tempo em uma execução
paralela e passou em outra). Os testes novos colam texto e têm folga de tempo para não aumentar essa fragilidade.
