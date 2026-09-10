# DR-0004 — Status inicial da Ordem de Serviço

- Tipo: `BUSINESS`
- Status: `DECIDED`
- Task: `TASK-0004`
- Origem: `AG-03 — Domínio Oficina`
- Responsável pela decisão: proprietário do produto, com consolidação por AG-01/AG-03
- Criada em: `2026-09-10`

## Problema

Os documentos aprovados permitem que a OS exista antes do orçamento, mas não definem inequivocamente qual estado ela recebe ao ser aberta. Persistir um valor arbitrário criaria parte do workflow sem decisão funcional.

## Opções

- **A — ABERTA:** estado operacional explícito desde o cadastro, simples para a vertical, mas antecipa nomenclatura do workflow.
- **B — EM_DIAGNOSTICO:** aproxima a abertura da etapa técnica, mas presume que toda OS inicia diagnóstico imediatamente.
- **C — sem status nesta fatia:** adia o workflow, mas produz uma OS sem estado e exige migration/contrato posterior.

## Recomendação

Opção A, por expressar apenas a existência aberta da OS e não antecipar orçamento ou execução. A recomendação não é decisão.

## Impacto e bloqueio

Impacta domínio Oficina, coluna/constraint de OS, respostas REST e testes. Bloqueia somente a implementação de OS e a integração vertical; Cliente, Veículo e Serviço independentes continuam.

## Pergunta final

Qual deve ser o status persistido automaticamente ao abrir uma Ordem de Serviço: `ABERTA`, `EM_DIAGNOSTICO` ou nenhum status nesta primeira fatia?

## Decisão final do Owner

- Data: `2026-09-10`
- Opção escolhida: `A — ABERTA`
- Decisão: toda nova Ordem de Serviço nasce automaticamente com o status persistido `ABERTA`.
- Regras resultantes: a OS pode existir antes do orçamento; nesta TASK-0004 não haverá workflow completo, máquina de estados genérica, Kanban configurável, antecipação de estados futuros ou transições ainda não especificadas.
- Impacto na Task: bloqueio removido; implementação mínima da abertura da OS autorizada.
