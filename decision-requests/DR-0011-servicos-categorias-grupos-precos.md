# DR-0011 — Serviços: categorias, grupos de veículos e prioridade de preço

- Tipo: `FUNCTIONAL`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0011`
- Origem: `AG-04 — Catálogo` / `AG-03 — Oficina`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner

## Problema

Os cartões "Cadastro de serviços", "Listagem, busca e categorias de serviços" e "Preços de serviços
por veículo ou grupo" pedem:

```text
Descrição     opcional        (hoje obrigatória)
Preço base    opcional        (hoje obrigatório)
Categoria     organizar serviços em categorias/grupos (hoje texto livre)
Preço         veículo específico → grupo de veículos → preço base
```

Não existe o conceito de grupo de veículos, e a regra de prioridade fica ambígua se um veículo
pertencer a mais de um grupo com preço para o mesmo serviço.

## Decisão provisória

1. **Descrição e preço base opcionais.** Serviço sem preço aplicável exige preço manual ao ser
   lançado na OS.
2. **Categoria como cadastro próprio** (`servicecatalog.service_category`), com nome único sem
   diferenciar maiúsculas e inativação lógica. As categorias em texto livre existentes são
   migradas para o cadastro. Categoria inativa continua ligada aos serviços que já a usam, mas não
   é oferecida para novos vínculos.
3. **Grupo de veículos no catálogo** (`servicecatalog.vehicle_group`), com nome único e
   inativação lógica. **Cada veículo pertence a no máximo um grupo** — elimina a ambiguidade de
   prioridade sem exigir regra de desempate.
4. **Preço por serviço**: no máximo um preço por (serviço, veículo) e um por (serviço, grupo).
   Resolução: preço do veículo → preço do grupo ativo do veículo → preço base → sem preço.
5. **O preço sugerido pode ser alterado manualmente na OS**; a OS guarda o valor praticado e a
   origem da sugestão, e alterações posteriores de preço não reescrevem OS existentes.
6. **Serviço inativo não pode ser lançado em nova OS** (`409 SERVICE_INACTIVE`).

## Pergunta final

Confirma categorias cadastradas, um grupo por veículo e a prioridade veículo → grupo → base?
