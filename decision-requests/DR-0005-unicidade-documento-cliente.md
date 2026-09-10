# DR-0005 — Escopo e normalização da unicidade de CPF/CNPJ

- Tipo: `BUSINESS`
- Status: `DECIDED`
- Task: `TASK-0004`
- Origem: `AG-01 — Produto & Requisitos`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-10`

## Problema

A TASK exige unicidade de CPF/CNPJ “conforme regra aprovada”, porém nenhum documento vigente define se a unicidade é global, por tipo, somente entre clientes ativos, nem confirma a forma canônica usada na comparação.

## Opções

- **A — global sobre dígitos:** remover pontuação e exigir unicidade entre todos os clientes, inclusive inativos.
- **B — por tipo sobre dígitos:** unicidade do par tipo/documento; permite colisão literal entre tipos.
- **C — somente ativos:** permite reutilização após inativação, com risco de duplicar identidade histórica.

## Recomendação

Opção A, preservando identidade e histórico; CPF deve conter 11 dígitos e CNPJ 14 dígitos. A recomendação não é decisão.

## Impacto e bloqueio

Impacta normalização, validação, índice único, reativação e testes PostgreSQL. Bloqueia a migration e conclusão do Cliente, mas não o desenho de domínio/API nem Veículo após existir contrato de Cliente.

## Pergunta final

CPF/CNPJ deve ser normalizado para somente dígitos e ser único globalmente, por tipo de pessoa, ou apenas entre clientes ativos?

## Decisão final do Owner

- Data: `2026-09-10`
- Opção escolhida: `A — global sobre dígitos`
- Decisão: remover toda pontuação/formatação, persistir e comparar somente dígitos, com unicidade global entre clientes.
- Regras resultantes: documentos formatados e não formatados representam a mesma identidade; a unicidade independe de `ACTIVE`/`INACTIVE`; inativação não libera documento; não haverá exclusão física para reutilização.
- Impacto na Task: bloqueio removido; normalização na aplicação e constraint `UNIQUE` PostgreSQL obrigatórias.
