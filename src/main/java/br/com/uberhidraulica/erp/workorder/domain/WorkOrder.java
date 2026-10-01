package br.com.uberhidraulica.erp.workorder.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus.Stage;

/**
 * Ordem de Serviço.
 *
 * <p>O status atual é uma coluna configurável ligada a uma etapa fixa (DR-0012). Finalizar, entregar e
 * cancelar só acontecem pelas ações próprias, que registram instante e autor; a movimentação manual
 * fica restrita às etapas operacionais.</p>
 */
public record WorkOrder(UUID id, Long number, UUID customerId, UUID vehicleId, Long entryMileage, Instant openedAt,
                        WorkflowStatus status, String complaint, String notes, String diagnosis, Lifecycle lifecycle,
                        Instant createdAt, Instant updatedAt, List<ServiceItem> services, List<ProductItem> products) {
    public WorkOrder {
        if (id == null || customerId == null || vehicleId == null || openedAt == null || status == null) throw invalid("Dados obrigatórios ausentes");
        if (entryMileage != null && entryMileage < 0) throw invalid("Quilometragem de entrada inválida");
        complaint = optional(complaint, "Defeito/reclamação", 2000);
        notes = optional(notes, "Observações", 2000);
        diagnosis = optional(diagnosis, "Diagnóstico", 4000);
        lifecycle = lifecycle == null ? Lifecycle.EMPTY : lifecycle;
        services = services == null ? List.of() : List.copyOf(services);
        products = products == null ? List.of() : List.copyOf(products);
    }

    private static final Set<Stage> AUTOMATION_STAGES =
            EnumSet.of(Stage.ABERTA, Stage.EM_DIAGNOSTICO, Stage.AGUARDANDO_APROVACAO, Stage.APROVADA, Stage.REPROVADA);

    public Stage stage() { return status.stage(); }

    public static WorkOrder open(UUID customerId, UUID vehicleId, Long mileage, String complaint, String notes,
                                 WorkflowStatus initial, Instant now) {
        if (initial.stage() != Stage.ABERTA) throw invalid("Status inicial deve pertencer à etapa ABERTA");
        requireComplaint(complaint);
        return new WorkOrder(UUID.randomUUID(), null, customerId, vehicleId, mileage, now, initial, complaint, notes, null,
                Lifecycle.EMPTY, now, now, List.of(), List.of());
    }

    public WorkOrder updateDetails(Long mileage, String newComplaint, String newNotes, Instant now) {
        requireOperational();
        requireComplaint(newComplaint);
        return new WorkOrder(id, number, customerId, vehicleId, mileage, openedAt, status, newComplaint, newNotes, diagnosis, lifecycle, createdAt, now, services, products);
    }

    /** Registra ou altera o diagnóstico técnico; o primeiro registro fica com instante e autor. */
    public WorkOrder registerDiagnosis(String text, UUID by, Instant now) {
        requireOperational();
        String normalized = optional(text, "Diagnóstico", 4000);
        if (normalized == null) throw invalid("Diagnóstico é obrigatório");
        Lifecycle updated = lifecycle.diagnosedAt() == null ? lifecycle.withDiagnosed(now, by) : lifecycle;
        return new WorkOrder(id, number, customerId, vehicleId, entryMileage, openedAt, status, complaint, notes, normalized, updated, createdAt, now, services, products);
    }

    /**
     * Transição disparada por regra automática (diagnóstico ou orçamento). Só atua nas etapas anteriores à
     * execução, para nunca tirar uma OS de execução ou de encerramento (DR-0013).
     */
    public Optional<WorkOrder> automaticMoveTo(WorkflowStatus target, Instant now) {
        if (!AUTOMATION_STAGES.contains(stage()) || target.id().equals(status.id()) || !target.active()) return Optional.empty();
        return Optional.of(withStatus(target, lifecycle, now));
    }

    /** Movimentação manual no Kanban, entre status ativos das etapas operacionais. */
    public WorkOrder moveTo(WorkflowStatus target, Instant now) {
        requireOperational();
        if (!target.stage().operational())
            throw new WorkOrderException("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED",
                    "Finalizar, entregar e cancelar usam as ações próprias da OS");
        requireActive(target);
        if (target.id().equals(status.id())) throw new WorkOrderException("WORK_ORDER_ALREADY_IN_STATUS", "A OS já está neste status");
        return withStatus(target, lifecycle, now);
    }

    public WorkOrder startExecution(WorkflowStatus target, Instant now) {
        requireOperational();
        if (stage() == Stage.EM_EXECUCAO) throw new WorkOrderException("WORK_ORDER_ALREADY_IN_EXECUTION", "A OS já está em execução");
        if (stage() == Stage.REPROVADA)
            throw new WorkOrderException("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED", "OS com orçamento reprovado não pode iniciar execução");
        requireStage(target, Stage.EM_EXECUCAO);
        Instant started = lifecycle.executionStartedAt() == null ? now : lifecycle.executionStartedAt();
        return withStatus(target, lifecycle.withExecutionStarted(started), now);
    }

    public WorkOrder finish(WorkflowStatus target, UUID by, Instant now) {
        if (stage() != Stage.EM_EXECUCAO)
            throw new WorkOrderException("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED", "Somente OS em execução pode ser finalizada");
        requireStage(target, Stage.FINALIZADA);
        return withStatus(target, lifecycle.withFinished(now, by), now);
    }

    public WorkOrder deliver(WorkflowStatus target, UUID by, Instant now) {
        if (stage() != Stage.FINALIZADA)
            throw new WorkOrderException("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED", "Somente OS finalizada pode ser entregue");
        requireStage(target, Stage.ENTREGUE);
        return withStatus(target, lifecycle.withDelivered(now, by), now);
    }

    public WorkOrder cancel(WorkflowStatus target, UUID by, String reason, Instant now) {
        if (!stage().operational())
            throw new WorkOrderException("WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED", "OS finalizada, entregue ou cancelada não pode ser cancelada");
        String normalized = optional(reason, "Motivo do cancelamento", 500);
        if (normalized == null) throw invalid("Motivo do cancelamento é obrigatório");
        requireStage(target, Stage.CANCELADA);
        return withStatus(target, lifecycle.withCancelled(now, by, normalized), now);
    }

    /** Itens só entram em OS que ainda está em atendimento (DR-0012, item 9). */
    public void requireOperational() {
        if (!stage().operational())
            throw new WorkOrderException("WORK_ORDER_CLOSED", "OS finalizada, entregue ou cancelada não aceita alterações");
    }

    private WorkOrder withStatus(WorkflowStatus target, Lifecycle newLifecycle, Instant now) {
        return new WorkOrder(id, number, customerId, vehicleId, entryMileage, openedAt, target, complaint, notes, diagnosis, newLifecycle, createdAt, now, services, products);
    }

    private static void requireActive(WorkflowStatus target) {
        if (!target.active()) throw new WorkOrderException("WORK_ORDER_STATUS_INACTIVE", "Status inativo não recebe OS");
    }

    private static void requireStage(WorkflowStatus target, Stage stage) {
        if (target.stage() != stage) throw invalid("Status de destino não pertence à etapa " + stage);
        requireActive(target);
    }

    private static void requireComplaint(String complaint) {
        if (complaint == null || complaint.isBlank()) throw invalid("Defeito/reclamação relatada é obrigatório");
    }

    /** Instantes e autores das ações que encerram etapas; nulos enquanto a ação não ocorreu. */
    public record Lifecycle(Instant diagnosedAt, UUID diagnosedBy, Instant executionStartedAt, Instant finishedAt, UUID finishedBy,
                            Instant deliveredAt, UUID deliveredBy, Instant cancelledAt, UUID cancelledBy, String cancellationReason) {
        public static final Lifecycle EMPTY = new Lifecycle(null, null, null, null, null, null, null, null, null, null);

        Lifecycle withDiagnosed(Instant at, UUID by) { return new Lifecycle(at, by, executionStartedAt, finishedAt, finishedBy, deliveredAt, deliveredBy, cancelledAt, cancelledBy, cancellationReason); }
        Lifecycle withExecutionStarted(Instant at) { return new Lifecycle(diagnosedAt, diagnosedBy, at, finishedAt, finishedBy, deliveredAt, deliveredBy, cancelledAt, cancelledBy, cancellationReason); }
        Lifecycle withFinished(Instant at, UUID by) { return new Lifecycle(diagnosedAt, diagnosedBy, executionStartedAt, at, by, deliveredAt, deliveredBy, cancelledAt, cancelledBy, cancellationReason); }
        Lifecycle withDelivered(Instant at, UUID by) { return new Lifecycle(diagnosedAt, diagnosedBy, executionStartedAt, finishedAt, finishedBy, at, by, cancelledAt, cancelledBy, cancellationReason); }
        Lifecycle withCancelled(Instant at, UUID by, String reason) { return new Lifecycle(diagnosedAt, diagnosedBy, executionStartedAt, finishedAt, finishedBy, deliveredAt, deliveredBy, at, by, reason); }
    }

    /** Mudança de status registrada no histórico da OS. */
    public record StatusChange(UUID id, UUID workOrderId, UUID fromStatusId, UUID toStatusId, Instant changedAt, UUID changedBy,
                               String reason, boolean automatic) {}

    /**
     * Serviço lançado na OS. {@code basePrice} é o preço praticado nesta OS e {@code priceSource} indica se
     * veio do preço do veículo, do grupo, do preço base ou foi informado manualmente; nulo em lançamentos
     * anteriores a essa rastreabilidade.
     */
    public record ServiceItem(UUID id, UUID serviceId, String name, String description, BigDecimal basePrice, int warrantyDays, Instant addedAt, String priceSource) {
        public ServiceItem {
            if (basePrice == null || basePrice.signum() < 0 || basePrice.scale() > 2)
                throw invalid("Preço do serviço deve ser não negativo e possuir no máximo duas casas decimais");
        }
    }

    /**
     * Item físico lançado na OS. Descrição, código interno, unidade e preço unitário são snapshot
     * do catálogo no instante do lançamento: alteração posterior do produto não reescreve a OS.
     */
    public record ProductItem(UUID id, UUID productId, String description, String internalCode, String unit,
                              BigDecimal quantity, BigDecimal unitPrice, Instant addedAt) {
        public ProductItem {
            if (id == null || productId == null || addedAt == null) throw invalid("Dados obrigatórios ausentes");
            description = required(description, "Descrição do produto", 200);
            unit = required(unit, "Unidade do produto", 16);
            internalCode = internalCode == null || internalCode.isBlank() ? null : internalCode.trim();
            if (quantity == null || quantity.signum() <= 0 || quantity.scale() > 3) throw invalid("Quantidade deve ser positiva e possuir no máximo três casas decimais");
            if (unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 2) throw invalid("Preço unitário deve ser não negativo e possuir no máximo duas casas decimais");
        }
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(label + " inválida");
        return value.trim();
    }

    private static String optional(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid(label + " excede o tamanho máximo");
        return value.trim();
    }

    static WorkOrderException invalid(String m) { return new WorkOrderException("INVALID_WORK_ORDER", m); }
}
