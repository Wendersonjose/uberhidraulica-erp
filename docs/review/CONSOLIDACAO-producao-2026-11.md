# Consolidação para homologação/produção — 2026-11-09

Branch: `consolidation/oficina-producao` → PR para `integration/oficina-consolidada`.
Base analisada: `7c60e74` (cabeça da PR #5, `feature/homologacao-compras-v22-v25`). **Nada foi publicado no Render nem alterado
no Supabase.**

## 1. Linhagem verificada (git, 2026-11-09)

```text
main (a712c11)
├── release/homologacao-producao (e0f4191, PR #3 aberta → main)      18 commits: guarda de produção, HTTPS/Caddy, scripts de ops,
│                                                                     correções de orçamento e de dependências
└── release/supabase-cloud (02aef5f)  ← o Render publica esta branch  4 commits: blueprint Render, porta, região, dependências
        ↘                ↙
integration/oficina-consolidada (214cea9)  = as duas releases + PR #4 (Compras I1–I5, V22–V25)
        ↓
feature/homologacao-compras-v22-v25 (7c60e74, PR #5)  +3 commits: teste de promoção V21→V25, rollback, E2E de Compras
        ↓
consolidation/oficina-producao (esta branch)  + correções desta consolidação
```

- `release/homologacao-producao` e `release/supabase-cloud` são **irmãs**: a produção atual **não contém** a
  `ProductionStartupGuard`, o bloqueio de alterações de orçamento em OS encerrada (`2a0c786`), os scripts de backup/restore nem a
  cadeia de proxy confiável. Promover a consolidação leva tudo isso junto com Compras.
- Nenhum commit novo na PR #5 depois de `7c60e74`. A PR #5 não conflita com a integration (fast-forward: 0 atrás, 3 à frente).
- A PR #3 (`release/homologacao-producao` → `main`) ficou **obsoleta**: todo o seu conteúdo já está na integration. Fechar sem
  merge ou deixar a `main` ser atualizada pela integration quando o proprietário decidir.

## 2. O que está no ar (verificação somente leitura, sem credenciais)

| Verificação | Resultado |
| --- | --- |
| `GET api/actuator/health` | `200 {"status":"UP"}`, sem detalhes internos |
| Bundle do frontend publicado (`index-D0yZPKnJ.js`, `last-modified` 2026-10-13) | contém Caixa, **não contém nenhuma tela de Compras** → código de `release/supabase-cloud` |
| SPA/refresh `/clientes`, CSRF pelo rewrite `/api/*`, `/api/customers` sem sessão, POST sem CSRF | 200 HTML / token JSON / 401 / 403 |
| Cabeçalhos HSTS, CSP, X-Frame-Options | presentes |
| Versão do Flyway no Supabase | **não verificada**: exige `scripts/ops/supabase-readonly-check.sql` com a credencial do proprietário. O código publicado só conhece V1–V21, o que torna V21 o estado provável, mas não comprovado. |

## 3. Correções desta consolidação

| Problema | Evidência | Correção |
| --- | --- | --- |
| `npm audit --audit-level=high` falha (3 HIGH novas desde o CI de outubro: `zod` < 4.6.6 — runtime, `vite` 8.3.0–8.3.2, `undici` < 7.30.1; mais 2 MODERATE em `vitest`) | o job `dependency-scan` reprovaria qualquer PR novo | `npm audit fix` sem `--force` (só lockfile, dentro do semver): `zod` 4.6.7, `vite` 8.3.7, `vitest` 5.1.0, `undici` 7.30.2 → 0 vulnerabilidades |
| Testes de frontend intermitentes: 2 a 5 falhas diferentes por execução da suíte completa, todas passando isoladas | `findBy*`/`waitFor` com 1 s padrão e teste com 5 s estouram sob carga | `asyncUtilTimeout` 5 s (setup) e `testTimeout` 20 s; asserções inalteradas. 3 execuções completas seguidas: 229/229 |

## 4. Auditoria (sem recriar funcionalidade)

- **DR-0023 preservada:** a única via de recebimento (`GoodsReceiptService`) chama `Purchase.requireReceivable()`, que recusa
  `ORDER`/`DIRECT` com `409 PURCHASE_RECEIPT_REQUIRES_INVOICE`. Recebimento sem NF-e continua bloqueado.
- **Contrato frontend × backend:** as 111 rotas `/api/*` usadas pelo frontend têm correspondência entre os 173 mapeamentos dos
  controllers (verificação estática de caminho; os testes de integração e o E2E cobrem os corpos).
- **Produção:** `prod,supabase` → cookie `Secure` + `HttpOnly` + `SameSite=Lax`, `forward-headers-strategy`, health sem detalhes,
  guarda que recusa senha fraca/ausente e cookie sem `Secure`; Flyway isolado em `erp_meta`; pool 5 (2 versões × 5 < 15).
- **Node:** CI usa Node 24; o Render usa Node 22. Build validado localmente em Node 22.20.

## 5. Decisões pendentes (não resolvidas por código)

| Item | Dono | Efeito enquanto aberto |
| --- | --- | --- |
| DR-0023 (pedido × NF-e) | proprietário | pedido/compra direta aprovam mas não recebem; compra sem nota usa a entrada manual do Estoque |
| DR-0020 (política de autenticação/bloqueio) | proprietário | ver `decision-requests/DR-0020-*` |
| Parâmetros de custo IPI/ICMS-ST | contador | a tela avisa que não foram validados |
| CNPJ do estabelecimento (`OWN_TAX_ID`) | proprietário, na tela | nenhuma NF-e confirma sem ele |
| Força de `SUPABASE_DB_PASSWORD` no Render | proprietário | se fraca, a guarda recusa subir a versão nova (a antiga continua no ar) |

## 6. Publicação e rollback

O procedimento exato está em [`PROMOCAO-compras-v22-v25.md`](../architecture/devops/PROMOCAO-compras-v22-v25.md): pré-voo somente
leitura, variáveis do Render, backup com teste de restauração local, fast-forward de `release/supabase-cloud` para o SHA homologado,
smoke `cloud-check.sh` e a tabela de rollback por situação. Esta consolidação só troca o `COMMIT_A_PROMOVER` pelo SHA da
consolidação depois de o PR ficar verde e ser integrado.

Ordem obrigatória: (1) CI verde neste PR → (2) merge na `integration/oficina-consolidada` → (3) homologação manual com o SHA da
integration → (4) `supabase-readonly-check.sql` = V21 → (5) backup + restauração de prova → (6) fast-forward de
`release/supabase-cloud` → (7) `cloud-check.sh` e roteiro de tela do §5 do runbook.
