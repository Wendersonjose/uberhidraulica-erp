# DR-0009 — "Tenant" nos cartões do Trello versus MVP de oficina única

- Tipo: `ARCHITECTURE`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0009` e todas as Tasks originadas do quadro Trello "Projetos wenderson"
- Origem: `AG-00 — Orquestrador` / `AG-02 — Arquitetura`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner ("você tem toda a permissão de criar")

## Problema

Os cartões do backlog no Trello descrevem as regras com o vocabulário de SaaS multi-oficina:
"unicidade por oficina (tenant), não global no SaaS", "somente dados do tenant autenticado",
"impedir movimentação entre tenants".

Toda a documentação aprovada até aqui afirma o contrário, de forma explícita e testada:

```text
REQ-SEG-001      o MVP atende uma única oficina
TASK-0003        nenhuma tabela recebe tenant_id; sem tenant context
TASK-0005        nenhuma coluna de tenant (revisão confirma "ausente, conforme exigido")
DR-0005          unicidade global do documento do cliente
```

## Opções

- **A — manter oficina única e ler "tenant" como a própria oficina:** com um único tenant, "único
  por oficina" e "único global" coincidem, e "somente dados do tenant autenticado" é satisfeito
  porque todo usuário autenticado pertence à única oficina existente. Nenhuma coluna ou filtro
  especulativo é criado.
- **B — introduzir multi-tenancy agora:** `tenant_id` em todas as tabelas, resolução de tenant no
  login, filtros obrigatórios, índices compostos e reescrita de IAM, CRM, OS, catálogo e orçamento.

## Recomendação e decisão provisória

**Opção A.** A opção B reescreve quatro Tasks aprovadas para atender um cenário que o README
declara fora do escopo inicial ("O sistema será inicialmente utilizado por uma única oficina").
A opção A não fecha a porta: toda unicidade nova é declarada por constraint nomeada, o que permite
torná-la composta com `tenant_id` numa migration futura.

## Consequências

- Regras "por tenant" dos cartões são implementadas como regras da oficina única.
- Não há `tenant_id`, tenant context nem teste de isolamento entre tenants.
- Se o Owner decidir pela opção B, abrir Task própria de migração para multi-tenancy antes de
  qualquer deploy com mais de uma oficina.

## Pergunta final

O ERP continua sendo de oficina única no MVP, ou a multi-tenancy deve ser introduzida agora?
