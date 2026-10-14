# Promoção de Compras (V22–V25) para Render + Supabase

Este é o procedimento exato para colocar a versão com Compras em produção. **Nada aqui foi executado em produção.** Os passos
marcados `[VOCÊ]` mexem em produção e só rodam com a sua confirmação final.

- Origem: `integration/oficina-consolidada` (contém `release/supabase-cloud` e `release/homologacao-producao` como ancestrais).
- Destino: `release/supabase-cloud` — a branch que o Render lê para o backend (`uberhidraulica-erp-api`) e o frontend (`uberhidraulica-erp-web`).
- Banco: Supabase em **V21**; a promoção aplica **V22, V23, V24 e V25**, todas aditivas.
- Commit a promover: `COMMIT_A_PROMOVER` (o mesmo para backend e frontend, porque os dois serviços leem a mesma branch).

## 1. O que muda em produção

| Camada | Mudança |
| --- | --- |
| Banco | schema novo `purchasing` (14 tabelas, 1 view); **2 colunas nulas** em `productcatalog.product` (`gtin`, `ncm`); **1 coluna nula** em `inventory.stock_movement` (`purchase_receipt_item_id`) e a restrição de origem passa a aceitar `PURCHASE`; 3 índices; 9 permissões `PURCHASE_*` concedidas a DONO e GERENTE_ADMINISTRATIVO (GERENTE_FINANCEIRO só consulta). Nenhum `DELETE`, `UPDATE` ou `TRUNCATE` de dados; os únicos `DROP` trocam restrições por versões mais amplas. |
| Backend | módulo Compras (fornecedores, NF-e, recebimento, pedido), contrato de entrada de estoque por compra, contas a pagar geradas pela NF-e; **`ProductionStartupGuard`** (vem da `release/homologacao-producao`): a aplicação **recusa subir** com senha do banco fraca/curta ou cookie sem `Secure`. |
| Frontend | telas de Compras; build `npm ci && npm run build` com Node 22 (verificado). |

O que **não** muda: nenhuma tabela, coluna ou linha existente é alterada; `public` (módulo de salários) não é tocado.

## 2. Evidência (CI do PR, PostgreSQL 18 real)

| Requisito | Prova |
| --- | --- |
| V22–V25 aplicam sobre V21 com dados, sem alterar linhas, só no schema `purchasing` | `MigrationUpgradeFromProductionIntegrationTest` |
| Versão antiga do aplicativo valida o Flyway e grava normalmente com o banco na V25 (rollback do app) | idem, teste 5 |
| Rollback do banco devolve a V21, recusa quando há estoque/contas de compra e permite promover de novo | idem, testes 6–7 + `scripts/ops/rollback-v22-v25.sql` |
| Estoque: entrada só no recebimento, custo médio, parcial/total, divergência, estorno, concorrência | `GoodsReceiptIntegrationTest` (18) |
| Contas a pagar: NF-e 21 = 1 compra, 3 × 647,70; receber não paga; pedido não gera conta | `RealNfe21AcceptanceTest` (7), `NfeImportIntegrationTest` (22), `purchasing_flow.py` |
| Autenticação e permissões | `IamAuthenticationIntegrationTest`, `ModulePermissionsIntegrationTest`, `Task0019SecurityHardeningIntegrationTest`, `PurchaseOrderIntegrationTest` |
| Stack completo (compose): E2E da oficina + Compras, reinício, backup, restore, HTTPS | `deploy-smoke` (`scripts/deploy/compose-smoke.sh`) |
| Sem operação destrutiva nas migrations | revisão: nenhum `DELETE/UPDATE/TRUNCATE`; `DROP CONSTRAINT/TRIGGER` só de objetos criados em V22–V23 ou da restrição de origem do estoque, que é recriada mais ampla |

## 3. Pré-voo `[VOCÊ]` — antes do comando de promoção

**3.1. Verificação somente leitura do banco de produção.** Rode `scripts/ops/supabase-readonly-check.sql` (SQL Editor do Supabase ou
`psql`). Todos os blocos têm o resultado esperado comentado. **Se qualquer um divergir, pare.** Em especial: última versão do Flyway = 21,
sem falha, e o schema `purchasing` ainda não existe.

**3.2. Variáveis do Render (backend)**, em *Environment* do serviço `uberhidraulica-erp-api`:

| Variável | Deve ser |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod,supabase` |
| `SESSION_COOKIE_SECURE` | `true` |
| `FLYWAY_DEFAULT_SCHEMA` | `erp_meta` |
| `SUPABASE_DB_URL` | `jdbc:postgresql://<HOST-DO-SESSION-POOLER>:5432/postgres?sslmode=require` (porta **5432**, nunca 6543) |
| `SUPABASE_DB_PASSWORD` | **12+ caracteres, ≥ 5 caracteres distintos, não ser valor conhecido** (a guarda recusa e o deploy novo não sobe) |
| `IAM_BOOTSTRAP_OWNER_PASSWORD` | ausente (o Dono já existe) ou forte |
| `APP_ENVIRONMENT` | **não definir** (o padrão é `production`) |

Se a guarda recusar, o Render **mantém a versão antiga no ar** (o healthcheck do novo falha) e o log diz qual variável corrigir, sem repetir o valor.

**3.3. Backup antes de tudo** (na sua máquina, com `pg_dump` 15+; a senha vem do ambiente, nunca no comando):

```bash
export PGPASSWORD='<senha do banco>'
pg_dump "host=<HOST-DO-SESSION-POOLER> port=5432 dbname=postgres user=postgres.<PROJECT_REF> sslmode=require" -F c \
  --schema=erp_meta --schema=iam --schema=crm --schema=servicecatalog --schema=productcatalog \
  --schema=inventory --schema=workorder --schema=workshop --schema=finance \
  -f erp-antes-v22-$(date +%Y%m%d-%H%M).dump
pg_restore --list erp-antes-v22-*.dump > /dev/null && echo "backup legível"
sha256sum erp-antes-v22-*.dump
```

Guarde o arquivo **fora** do notebook e do Git. Para provar que é recuperável, restaure em um banco descartável **local** (nunca no Supabase):

```bash
createdb erp_verifica && pg_restore --no-owner --no-privileges -d erp_verifica erp-antes-v22-*.dump \
  && psql -d erp_verifica -c "select max(version::int) from erp_meta.flyway_schema_history where success"   # deve mostrar 21
dropdb erp_verifica
```

(Alternativa/extra: *Database → Backups* do Supabase, conforme o seu plano.)

## 4. Promoção `[VOCÊ]` — comandos exatos

O push abaixo **dispara o deploy automático** dos dois serviços no Render. É um *fast-forward* (nada é reescrito) e falha sozinho se
`release/supabase-cloud` tiver andado.

```bash
git fetch origin
git checkout release/supabase-cloud
git pull --ff-only origin release/supabase-cloud
git merge --ff-only COMMIT_A_PROMOVER          # deve terminar em "Fast-forward"; qualquer outra coisa: pare
git log --oneline -1                           # confirme o SHA
git push origin release/supabase-cloud
```

Acompanhe no painel do Render, **nos dois serviços**, a aba *Deploys*: o commit exibido tem de ser `COMMIT_A_PROMOVER` em ambos.

- **Backend:** o log mostra `Migrating schema ... to version "22"`, `"23"`, `"24"`, `"25"` e depois `Started UberhidraulicaErpApplication`. Se o serviço
  não tem *Auto-Deploy*, use *Manual Deploy → Deploy a specific commit* com o SHA, **primeiro o backend**, depois o frontend.
- **Frontend (Static Site):** publica o mesmo commit. Durante os minutos em que o backend novo ainda constrói, só as telas novas de
  Compras ficam sem resposta; o restante usa a API antiga sem diferença.
- O Render mantém a versão antiga e a nova rodando juntas durante o deploy; a versão antiga continua válida com o banco na V25 (testado).

## 5. Smoke tests pós-deploy `[VOCÊ]`

```bash
ERP_URL=https://uberhidraulica-erp-web.onrender.com \
ERP_BACKEND_URL=https://uberhidraulica-erp-api.onrender.com \
ERP_EMAIL='<e-mail do Dono>' ERP_PASSWORD='<senha do Dono>' \
scripts/deploy/cloud-check.sh --create-test-data
```

Cobre saúde do backend, SPA e refresh, CSRF, login, cookie `Secure/HttpOnly/SameSite`, sessão, **clientes, veículos e OS** (dados
"ZZ TESTE CLOUD"), logout, e **fornecedores, compras, estoque, contas a pagar e painel financeiro** (somente leitura). Saída esperada:
`ERP ONLINE E FUNCIONANDO (0 falhas).` A senha e o e-mail vêm do ambiente e nunca são impressos.

Depois, pela tela, como Dono (é o primeiro uso real de Compras):

1. *Compras → Configurações*: informar o **CNPJ completo** do estabelecimento (`OWN_TAX_ID`). Sem ele nenhuma NF-e confirma (D9).
2. *Financeiro → Configurações*: ter ao menos uma **categoria de despesa** (a importação exige uma).
3. *Compras → Importar NF-e*: importar a NF-e 21 → conferir **1 compra, 3 contas a pagar de R$ 647,70, soma R$ 1.943,10** e estoque ainda zerado.
4. Receber a mercadoria na compra → o estoque entra só no recebimento, ao custo de aquisição.

## 6. Rollback

**Decida pelo estado, não pela pressa:**

| Situação | O que fazer |
| --- | --- |
| Deploy não subiu (guarda de produção, falha de migration) | Nada a desfazer no app: o Render mantém a versão antiga. Corrija a causa (log do backend). Se uma migration falhou no meio, o Flyway marca `success = false` e **cada migration roda em transação**: nada fica pela metade. |
| Subiu, mas há problema no app, **sem uso real de Compras** | 1) Render → cada serviço → *Deploys* → *Rollback* para o deploy anterior (a versão antiga roda sobre o banco na V25). 2) Se quiser o banco de volta na V21: `psql "<conexão>" -v ON_ERROR_STOP=1 -f scripts/ops/rollback-v22-v25.sql`. |
| Já houve **uso real** de Compras (NF-e importada, estoque recebido) | **Não use o script de rollback** (ele recusa se há estoque ou contas de compra). Faça rollback só do app, corrija para frente, ou restaure o backup do passo 3.3 sabendo que perde o que foi feito depois dele. |
| Restaurar o backup | Em banco descartável primeiro (passo 3.3). Restaurar sobre produção é decisão sua e só depois de pausar o app (Render → *Suspend*). |

O script de rollback roda em **uma transação** e tem travas; foi testado em CI (recusa, devolve a V21 e permite promover de novo). O
histórico do Flyway é `erp_meta`.

## 7. Limites conhecidos desta versão (não impedem a publicação)

- **Só a compra de NF-e dá entrada em estoque.** Pedido e compra direta são aprovados mas não recebem (DR-0023, aberta); compra sem
  nota usa a entrada manual do Estoque.
- A autorização da NF-e é a **declarada no XML**; não há consulta à SEFAZ (D12).
- Os parâmetros de custo (IPI/ICMS-ST) **não foram validados pelo contador**; a tela avisa.
- Não há consulta de Contábil, cotação nem devolução de compra (fora do escopo desta entrega).
