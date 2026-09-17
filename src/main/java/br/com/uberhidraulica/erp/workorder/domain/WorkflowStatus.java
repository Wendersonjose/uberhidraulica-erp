package br.com.uberhidraulica.erp.workorder.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Coluna do Kanban configurada pela oficina e associada a uma etapa fixa do fluxo (DR-0012).
 *
 * <p>O status padrão de cada etapa é o alvo das regras automáticas e não pode ser inativado.</p>
 */
public record WorkflowStatus(UUID id, String name, Stage stage, int position, boolean active, boolean stageDefault,
                             Instant createdAt, Instant updatedAt) {
    public WorkflowStatus {
        if (id == null || stage == null) throw WorkOrder.invalid("Status incompleto");
        if (name == null || name.isBlank() || name.trim().length() > 60) throw WorkOrder.invalid("Nome do status inválido");
        name = name.trim();
        if (stageDefault && !active) throw WorkOrder.invalid("Status padrão não pode ficar inativo");
    }

    public static WorkflowStatus create(String name, Stage stage, int position, Instant now) {
        return new WorkflowStatus(UUID.randomUUID(), name, stage, position, true, false, now, now);
    }

    public WorkflowStatus rename(String newName, Instant now) {
        return new WorkflowStatus(id, newName, stage, position, active, stageDefault, createdAt, now);
    }

    public WorkflowStatus moveTo(int newPosition, Instant now) {
        return new WorkflowStatus(id, name, stage, newPosition, active, stageDefault, createdAt, now);
    }

    public WorkflowStatus withActive(boolean value, Instant now) {
        if (value == active)
            throw new WorkOrderException(value ? "STATUS_ALREADY_ACTIVE" : "STATUS_ALREADY_INACTIVE", value ? "Status já está ativo" : "Status já está inativo");
        if (!value && stageDefault)
            throw new WorkOrderException("STATUS_DEFAULT_CANNOT_BE_INACTIVATED", "O status padrão da etapa não pode ser inativado; defina outro padrão antes");
        return new WorkflowStatus(id, name, stage, position, value, stageDefault, createdAt, now);
    }

    public WorkflowStatus withDefault(boolean value, Instant now) {
        return new WorkflowStatus(id, name, stage, position, active, value, createdAt, now);
    }

    /** Etapas fixas com semântica própria; a ordem declarada é a ordem natural do atendimento. */
    public enum Stage {
        ABERTA, EM_DIAGNOSTICO, AGUARDANDO_APROVACAO, APROVADA, REPROVADA, EM_EXECUCAO, FINALIZADA, ENTREGUE, CANCELADA;

        /** Etapas em que a OS ainda está em atendimento e aceita movimentação manual e novos itens. */
        public boolean operational() { return ordinal() <= EM_EXECUCAO.ordinal(); }

        /** Etapas alcançadas somente pelas ações próprias de finalizar, entregar e cancelar. */
        public boolean closing() { return !operational(); }
    }
}
