# DR-0012 — Fluxo configurável de status da OS, abertura, execução, entrega e cancelamento

- Tipo: `DOMAIN`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0012`
- Origem: `AG-03 — Domínio Oficina`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner
- Supera: `DR-0004` ("toda OS nasce ABERTA; sem workflow adicional") no que diz respeito ao workflow

## Problema

Os cartões "Abertura de ordem de serviço", "Kanban e status das ordens de serviço", "Execução,
finalização e entrega da OS", "Cancelamento de ordem de serviço" e "Configuração do fluxo e status
da OS" pedem status configuráveis, com status especiais (inicial, finalizado, cancelado), regras
automáticas e histórico. Hoje a OS só conhece `ABERTA`.

Além disso, o cartão de abertura torna a **quilometragem de entrada opcional** e o
**defeito/reclamação obrigatório**, o contrário do critério 9 da TASK-0004.

## Decisão provisória

1. **Etapas fixas, status configuráveis.** O sistema conhece nove etapas com semântica própria:

   ```text
   ABERTA  EM_DIAGNOSTICO  AGUARDANDO_APROVACAO  APROVADA  REPROVADA
   EM_EXECUCAO  FINALIZADA  ENTREGUE  CANCELADA
   ```

   A oficina cadastra os status que aparecem no Kanban (nome, ordem, ativo) e associa cada um a
   uma etapa. Várias colunas podem compartilhar a etapa (ex.: "Em execução" e "Aguardando peça"
   em `EM_EXECUCAO`). A carga inicial cria um status por etapa.
2. **Status especiais** são os status padrão das etapas `ABERTA` (inicial), `FINALIZADA`,
   `ENTREGUE` e `CANCELADA`. Cada etapa tem exatamente um status padrão, usado pelas regras
   automáticas; o padrão não pode ser inativado.
3. **Regras automáticas** movem a OS para o status padrão da etapa correspondente quando o evento
   ocorre: abertura → `ABERTA`; início da execução → `EM_EXECUCAO`; finalização → `FINALIZADA`;
   entrega → `ENTREGUE`; cancelamento → `CANCELADA`. Eventos de aprovação são tratados na Task do
   orçamento. Cada regra automática pode ser desligada, exceto as quatro das etapas especiais.
4. **Movimentação manual** no Kanban é livre entre status ativos das etapas operacionais
   (`ABERTA` a `EM_EXECUCAO`). As etapas `FINALIZADA`, `ENTREGUE` e `CANCELADA` só são alcançadas
   pelas ações próprias, que registram data/hora, usuário e, no cancelamento, motivo obrigatório.
5. **Estados finais.** OS `CANCELADA` e `ENTREGUE` não voltam ao fluxo. OS `FINALIZADA` só pode ser
   entregue. Não há reabertura nesta Task.
6. **Histórico** de toda mudança de status: de, para, instante, usuário, motivo e se foi automática.
7. **Status usados não são excluídos**, somente inativados. Status inativo não recebe OS, mas as OS
   que já estão nele continuam visíveis na coluna até serem movidas.
8. **Abertura**: quilometragem de entrada opcional; defeito/reclamação obrigatório; observações
   opcionais; cliente e veículo devem estar **ativos** e o veículo deve pertencer ao cliente.
9. **Itens da OS** só podem ser lançados enquanto a OS não estiver finalizada, entregue ou cancelada.

## Pergunta final

Confirma etapas fixas com status configuráveis, movimentação manual restrita às etapas
operacionais e ausência de reabertura de OS finalizada?
