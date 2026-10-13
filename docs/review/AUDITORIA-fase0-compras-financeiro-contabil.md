# Auditoria da Fase 0 — Compras, Financeiro e Contábil

Data: 2026-10-13 · Escopo: leitura dos dois repositórios; nenhuma alteração no repositório de referência.

## 1. Destino (`uberhidraulica-erp`)

| Item | Achado |
| --- | --- |
| Stack | Spring Boot + Spring Modulith 2.1, Java 17, PostgreSQL (Testcontainers PG 18), Flyway; React 19 + TanStack Query + react-hook-form + zod + Vitest |
| Autenticação | Sessão com cookie (Spring Session); permissões em `iam.permission`, checadas por `@iamAuthorization.hasPermission`. **Sem JWT** |
| Módulos | `crm`, `finance`, `iam`, `inventory`, `productcatalog`, `quote`, `servicecatalog`, `workorder`. **Não existem** Compras, Fornecedores nem Contábil |
| Fornecedor hoje | Texto livre em `finance.payable.supplier` |
| Financeiro existente | Recebível da OS, recebimentos, ajustes, **conta a pagar de valor único** (`V18`), pagamento parcial e estorno, `idempotency_key`, caixa físico (`V21`), fluxo de caixa, dashboard |
| Estoque existente | Saldo por produto, movimentos imutáveis, custo médio ponderado, estorno; `source_type` só `MANUAL`/`WORK_ORDER` |
| Catálogo | Sem GTIN, NCM nem código de fornecedor; unidades `UNIDADE, LITRO, METRO, QUILOGRAMA, GALAO_5L, BALDE_20L` |
| Tenancy | **Single-installation**: sem `tenant_id` (comentário explícito na `V21`; `DR-0021`). O briefing fala em oficinas/tenants — o conceito não existe no banco |
| Migrations | `V1`–`V21` em `main` e nas duas releases |
| Supabase | `application-supabase.yml` isola o histórico Flyway em `erp_meta`; o projeto Supabase já tem tabelas do módulo de salários em `public` ⇒ novas tabelas só em schemas próprios |
| Governança | `AGENTS.md` exige Task → requisito → domínio → arquitetura → segurança → dados → backend → frontend → QA → revisão. `DR-0021` bloqueava Tasks de Compras (agora DECIDED) |

### Branches (remoto, 2026-10-13)

| Branch | Relação com `main` (`a712c11`) | Conteúdo |
| --- | --- | --- |
| `release/supabase-cloud` | +4 | Blueprint do Render, guia Supabase, `PORT`, região us-east-2, override Tomcat/Jackson |
| `release/homologacao-producao` | +18 | Hardening, scripts de operação (backup/restore/update), CI com OSV, guard de produção, TASK-0020, DR-0020 |
| `feature/autonomous-sprint-catalog` (local) | 39 atrás | Desatualizada (parava na `V20`) |

Nenhuma release contém a outra; o merge de teste tem um conflito apenas em comentário do `pom.xml`. **Decisão do owner:**
base = merge das duas releases, criada localmente em `feature/compras-financeiro-contabil-oficina`; nada foi enviado ao remoto.

## 2. Referência (`uberhidraulica-admin`) — somente leitura

SPA React 18 + Vite + Tailwind + Zustand + Axios consumindo a API Node `garageflow-api` (repositório separado, **não
acessado**), com **JWT**, ids numéricos e valores monetários como `number`. O `CLAUDE.md` dela marca "Oficina desativada" e
"Compras/reposição: sem backend". Contratos inferidos de `types/` e `services/`.

### Reaproveitável como conceito

- Importação em duas etapas: prévia somente leitura (`chaveAcesso`, `xmlHash`, `duplicado`, `compraExistenteId`) e confirmação
  em transação única.
- Vínculo aprendido fornecedor + código do fornecedor → produto; cadastro assistido do que não casa.
- Parcelas em centavos com resíduo na última; datas sem deriva de fuso.
- Aprovação ortogonal ao status de recebimento; devolução da compra para revisão da cotação.
- Custos adicionais (frete CIF/FOB, CT-e) separados do total fiscal, com rateio opcional no custo.
- Carteira/fatura consolidada do fornecedor (`AG-05` §75); adiantamentos.
- Contábil: competência com estados, OFX com FITID, hash do arquivo, validação de fechamento, reabertura com motivo,
  trilha de auditoria com antes/depois.

### Lacunas da referência (trabalho novo aqui)

- Não há partidas dobradas, plano de contas, razão nem balancete — o "contábil" dela é conciliação bancária.
- Não há recebimento parcial com divergência aceita, devolução ao fornecedor nem crédito.
- Não há vínculo com OS, custo médio ponderado nem controle de concorrência verificável.
- Aritmética em `number` (ponto flutuante): não reaproveitável.

### Diferenças de arquitetura

| | Referência | Destino |
| --- | --- | --- |
| Regra de negócio | Em outra API (Node) | No monólito Spring Modulith |
| Identificadores | Numéricos | UUID |
| Sessão | JWT em `localStorage` | Cookie de sessão |
| Dinheiro | `number` | `BigDecimal`/`NUMERIC` |
| Histórico | Mutável em partes | Append-only + estorno |

## 3. NF-e de referência

Arquivo `…-procNFe.xml` **não estava no ambiente**. Verificado só pela chave informada: UF 31, AAMM 2610, CNPJ do emitente
`67757350000178` (DV válido), modelo 55, série 1, número 21, DV da chave válido; CNPJ do destinatário `01637003000101` com DV
válido; 3 × 647,70 = 1.943,10. **Não verificados:** leiaute XSD, protocolo, situação na SEFAZ, 12 linhas, NCM/CFOP/CST.

CNPJ alfanumérico (NT Conjunta 2025.001, em vigor desde julho/2026): suportado em `BrazilianTaxId` e `NfeAccessKey`
(módulo 11 com valor = ASCII − 48; chave `[0-9]{6}[A-Z0-9]{12}[0-9]{26}`), confirmado em fontes públicas; exemplo oficial
`12.ABC.345/01DE-35` coberto por teste.

## 4. Pendências para destravar

1. Versão Flyway efetivamente aplicada no Supabase (`SELECT … FROM erp_meta.flyway_schema_history`).
2. XML real da NF-e 21.
3. Decisões do `DR-0022`.
4. Um contador para os pontos tributários/contábeis (`TASK-0023`).
