# TASK-0020 — Preparação para produção: HTTPS, proxy confiável, operação e homologação real

## Identificação

- Status: `DONE` como preparação — o estado do sistema continua `READY_FOR_HOMOLOGATION`; `READY_FOR_PRODUCTION` **não**
  foi declarado (ver os bloqueadores B1–B5 em [`docs/review/RELEASE-producao.md`](../../docs/review/RELEASE-producao.md))
- Prioridade: `HIGH`
- Criada em: `2026-10-05`
- Origem: pedido do proprietário para levar o sistema de `READY_FOR_HOMOLOGATION` a `READY_FOR_PRODUCTION` sem abrir módulos novos
- Proprietário principal: Orquestrador (`AG-00`), com DevOps (`AG-14`), Segurança (`AG-09`), QA (`AG-13`) e Revisor (`AG-15`)
- Revisão: [`docs/review/RELEASE-producao.md`](../../docs/review/RELEASE-producao.md)

## Objetivo

Exercitar o sistema como em produção (Docker, HTTPS, restart, backup, restore, atualização, rollback, jornada completa no
navegador), corrigir o que a execução real expôs e separar, sem inventar decisão de negócio, o que bloqueia produção, o que
a homologação ainda precisa provar e o que é pós-MVP.

## Entregue

| Área | O que mudou |
| --- | --- |
| Produção falha fechada | `ProductionStartupGuard` (EnvironmentPostProcessor): sem senha de banco, com senha fraca/conhecida, cookie sem `Secure` fora de `APP_ENVIRONMENT=homologation`, ou `APP_ENVIRONMENT` sem o perfil `prod` → o backend não sobe, antes de abrir conexão |
| Proxy confiável | `TRUSTED_PROXY_CIDRS`; `X-Forwarded-For`/`Proto` só de proxies listados; `real_ip_recursive`; valor que aceitaria qualquer origem derruba o container; `scripts/deploy/proxy-trust-check.sh` (37 verificações reais) |
| HTTPS | `compose.https.yaml` + `deploy/caddy/Caddyfile` (Let's Encrypt automático, redirect, HSTS, nginx sem porta publicada) |
| Logs | token do link público mascarado no log de acesso do nginx (URI e Referer); `/actuator/**` além do health responde 404 |
| Operação | `scripts/ops/`: `preflight`, `backup` (cópia externa obrigatória, código 3 sem ela), `verify-backup` (banco descartável), `restore`, `update` (backup + smoke + histórico + rollback) |
| Orçamento × OS encerrada (D1) | `WorkOrderCommercialEvents.lockForCommercialChange` informa se a OS aceita mudança comercial; orçamento/revisão/apresentação/link/decisão em OS finalizada, entregue ou cancelada → 409 `WORK_ORDER_CLOSED` |
| Frontend | página pública do orçamento em cartões no celular; sem sondagem de `eval` (CSP); aviso sem categoria de despesa |
| CI | `dependency-scan` (OSV para o Maven + `npm audit`), Dependabot semanal, smoke com troca HTTP→HTTPS e scripts de operação, `proxy-trust-check` |
| Decisões | `DR-0020` com resumo objetivo para o proprietário (problema, risco, opções, impacto, recomendação); `DR-0019` mantida como limitação do piloto; `DR-0021` intocada |

## Migrations

Nenhuma. As 21 migrations existentes permanecem imutáveis.

## Fora do escopo (decisão `DR-0021`)

Compras, Comissões, Conciliação, Fiscal/NFS-e, rentabilidade avançada, garantia avançada, reserva de estoque, multi-tenant.
Nenhum foi iniciado.

## O que não foi provado aqui

Certificado Let's Encrypt real, backup copiado para um destino externo real, balanceador real do provedor, servidor real.
Veja a seção 2 de `docs/review/RELEASE-producao.md`.
