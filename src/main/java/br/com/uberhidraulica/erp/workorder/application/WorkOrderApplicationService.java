package br.com.uberhidraulica.erp.workorder.application;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.productcatalog.ProductQuantityRule;
import br.com.uberhidraulica.erp.servicecatalog.ServiceCatalogQuery;
import br.com.uberhidraulica.erp.workorder.WorkOrderEvents;
import br.com.uberhidraulica.erp.workorder.WorkOrderQuery;
import br.com.uberhidraulica.erp.workorder.domain.*;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus.Stage;
import br.com.uberhidraulica.erp.workorder.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class WorkOrderApplicationService implements WorkOrderQuery, br.com.uberhidraulica.erp.workorder.WorkOrderCommercialEvents {
    private final WorkOrderRepositoryPort repository;
    private final CustomerVehicleQuery crm;
    private final ServiceCatalogQuery catalog;
    private final ProductCatalogQuery products;
    private final CurrentUser currentUser;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final Clock clock;

    public WorkOrderApplicationService(WorkOrderRepositoryPort repository, CustomerVehicleQuery crm, ServiceCatalogQuery catalog,
                                       ProductCatalogQuery products, CurrentUser currentUser,
                                       org.springframework.context.ApplicationEventPublisher events) {
        this.repository = repository; this.crm = crm; this.catalog = catalog; this.products = products; this.currentUser = currentUser;
        this.events = events;
        this.clock = Clock.systemUTC();
    }

    // ------------------------------------------------------------------ abertura e consulta

    /** Abre a OS no status padrão da etapa ABERTA; cliente e veículo precisam estar ativos (DR-0012). */
    @Transactional
    public WorkOrder open(UUID customerId, UUID vehicleId, Long mileage, String complaint, String notes) {
        var customer = crm.customer(customerId).orElseThrow(() -> new WorkOrderException("CUSTOMER_NOT_FOUND", "Cliente não encontrado"));
        var vehicle = crm.vehicle(vehicleId).orElseThrow(() -> new WorkOrderException("VEHICLE_NOT_FOUND", "Veículo não encontrado"));
        if (!vehicle.belongsTo(customerId)) throw new WorkOrderException("VEHICLE_CUSTOMER_MISMATCH", "Veículo não pertence ao cliente informado");
        if (!customer.active()) throw new WorkOrderException("CUSTOMER_INACTIVE", "Cliente inativo não pode abrir OS");
        if (!vehicle.active()) throw new WorkOrderException("VEHICLE_INACTIVE", "Veículo inativo não pode abrir OS");
        Instant now = clock.instant();
        WorkOrder opened = repository.save(WorkOrder.open(customerId, vehicleId, mileage, complaint, notes, defaultStatus(Stage.ABERTA), now));
        recordChange(opened.id(), null, opened.status(), now, null, true);
        return opened;
    }

    @Transactional(readOnly = true)
    public WorkOrder get(UUID id) { return repository.findById(id).orElseThrow(WorkOrderApplicationService::notFound); }

    /** Lista geral ou histórico filtrado por cliente ou por veículo; o filtro por veículo prevalece quando ambos vierem. */
    @Transactional(readOnly = true)
    public List<WorkOrder> list(UUID customerId, UUID vehicleId) {
        if (vehicleId != null) return repository.findByVehicleId(vehicleId).stream().filter(w -> customerId == null || w.customerId().equals(customerId)).toList();
        if (customerId != null) return repository.findByCustomerId(customerId);
        return repository.findAll();
    }

    @Transactional
    public WorkOrder updateDetails(UUID id, Long mileage, String complaint, String notes) {
        WorkOrder current = locked(id);
        return repository.save(current.updateDetails(mileage, complaint, notes, clock.instant()));
    }

    /** Diagnóstico técnico; o primeiro registro em OS aberta move para o padrão de EM_DIAGNOSTICO, se a regra estiver ligada. */
    @Transactional
    public WorkOrder registerDiagnosis(UUID id, String diagnosis) {
        WorkOrder current = locked(id);
        Instant now = clock.instant();
        WorkOrder diagnosed = repository.save(current.registerDiagnosis(diagnosis, currentUser.id().orElse(null), now));
        if (current.stage() != Stage.ABERTA || !automationEnabled(AUTOMATION_DIAGNOSIS)) return diagnosed;
        return automatic(diagnosed, Stage.EM_DIAGNOSTICO, "Diagnóstico registrado");
    }

    @Override
    @Transactional
    public void quotePresented(UUID workOrderId) {
        if (!automationEnabled(AUTOMATION_QUOTE_PRESENTED)) return;
        automatic(locked(workOrderId), Stage.AGUARDANDO_APROVACAO, "Orçamento apresentado ao cliente");
    }

    @Override
    @Transactional
    public void quoteDecided(UUID workOrderId, boolean anyApproved, boolean allRejected) {
        if (anyApproved && automationEnabled(AUTOMATION_QUOTE_APPROVED))
            automatic(locked(workOrderId), Stage.APROVADA, "Orçamento aprovado pelo cliente");
        else if (allRejected && automationEnabled(AUTOMATION_QUOTE_REJECTED))
            automatic(locked(workOrderId), Stage.REPROVADA, "Orçamento reprovado pelo cliente");
    }

    public static final String AUTOMATION_DIAGNOSIS = "DIAGNOSIS_REGISTERED";
    public static final String AUTOMATION_QUOTE_PRESENTED = "QUOTE_PRESENTED";
    public static final String AUTOMATION_QUOTE_APPROVED = "QUOTE_APPROVED";
    public static final String AUTOMATION_QUOTE_REJECTED = "QUOTE_REJECTED";

    private boolean automationEnabled(String event) { return repository.automations().getOrDefault(event, false); }

    private WorkOrder automatic(WorkOrder current, Stage stage, String reason) {
        return current.automaticMoveTo(defaultStatus(stage), clock.instant())
                .map(moved -> transition(current, moved, reason, true))
                .orElse(current);
    }

    // ------------------------------------------------------------------ fluxo

    @Transactional
    public WorkOrder move(UUID id, UUID statusId, String reason) {
        WorkOrder current = locked(id);
        WorkflowStatus target = repository.findStatus(statusId).orElseThrow(() -> new WorkOrderException("STATUS_NOT_FOUND", "Status não encontrado"));
        return transition(current, current.moveTo(target, clock.instant()), reason, false);
    }

    @Transactional
    public WorkOrder startExecution(UUID id) {
        WorkOrder current = locked(id);
        return transition(current, current.startExecution(defaultStatus(Stage.EM_EXECUCAO), clock.instant()), null, true);
    }

    /**
     * Finaliza a OS. Estoque e Financeiro reagem na mesma transação: se um deles recusar (saldo
     * insuficiente, falta de base comercial para o recebível), a OS continua em execução.
     *
     * @param billingQuoteId orçamento de faturamento escolhido pelo usuário, ou nulo (DR-0015, F-02)
     */
    @Transactional
    public WorkOrder finish(UUID id, UUID billingQuoteId) {
        WorkOrder current = locked(id);
        Instant now = clock.instant();
        UUID by = currentUser.id().orElse(null);
        WorkOrder finished = transition(current, current.finish(defaultStatus(Stage.FINALIZADA), by, now), null, true);
        events.publishEvent(new WorkOrderEvents.Finished(id, finished.products().stream()
                .map(item -> new WorkOrderEvents.ProductLine(item.id(), item.productId(), item.quantity())).toList(),
                billingQuoteId, now, by));
        return finished;
    }

    @Transactional
    public WorkOrder deliver(UUID id) {
        WorkOrder current = locked(id);
        return transition(current, current.deliver(defaultStatus(Stage.ENTREGUE), currentUser.id().orElse(null), clock.instant()), null, true);
    }

    @Transactional
    public WorkOrder cancel(UUID id, String reason) {
        WorkOrder current = locked(id);
        WorkOrder cancelled = transition(current, current.cancel(defaultStatus(Stage.CANCELADA), currentUser.id().orElse(null), reason, clock.instant()), reason, true);
        events.publishEvent(new WorkOrderEvents.Cancelled(id));
        return cancelled;
    }

    @Transactional(readOnly = true)
    public List<WorkflowStatus> statuses() { return repository.statuses(); }

    @Transactional(readOnly = true)
    public List<WorkOrder.StatusChange> history(UUID id) {
        get(id);
        return repository.statusHistory(id);
    }

    /**
     * Quadro Kanban: status em ordem, com as OS de cada um. Colunas inativas aparecem somente enquanto tiverem
     * OS. Nas etapas de encerramento, só as OS encerradas nos últimos {@code closedDays} dias, para o quadro
     * não crescer sem limite — o histórico completo continua na listagem e no cliente/veículo.
     */
    @Transactional(readOnly = true)
    public List<BoardColumn> board(int closedDays) {
        Instant limit = clock.instant().minus(Duration.ofDays(Math.max(closedDays, 0)));
        Map<UUID, List<WorkOrder>> byStatus = new HashMap<>();
        for (WorkOrder order : repository.findAll()) {
            if (order.stage().closing() && closedAt(order) != null && closedAt(order).isBefore(limit)) continue;
            byStatus.computeIfAbsent(order.status().id(), key -> new ArrayList<>()).add(order);
        }
        return repository.statuses().stream()
                .filter(s -> s.active() || byStatus.containsKey(s.id()))
                .map(s -> new BoardColumn(s, byStatus.getOrDefault(s.id(), List.of())))
                .toList();
    }

    private static Instant closedAt(WorkOrder order) {
        var l = order.lifecycle();
        return switch (order.stage()) {
            case FINALIZADA -> l.finishedAt();
            case ENTREGUE -> l.deliveredAt();
            case CANCELADA -> l.cancelledAt();
            default -> null;
        };
    }

    private WorkOrder transition(WorkOrder before, WorkOrder after, String reason, boolean automatic) {
        WorkOrder saved = repository.save(after);
        recordChange(saved.id(), before.status(), saved.status(), saved.updatedAt(), reason, automatic);
        return saved;
    }

    private void recordChange(UUID orderId, WorkflowStatus from, WorkflowStatus to, Instant at, String reason, boolean automatic) {
        String normalized = reason == null || reason.isBlank() ? null : reason.trim();
        if (normalized != null && normalized.length() > 500) throw new WorkOrderException("INVALID_WORK_ORDER", "Motivo excede 500 caracteres");
        repository.addStatusChange(new WorkOrder.StatusChange(UUID.randomUUID(), orderId, from == null ? null : from.id(), to.id(), at,
                currentUser.id().orElse(null), normalized, automatic));
    }

    private WorkOrder locked(UUID id) { return repository.findByIdForUpdate(id).orElseThrow(WorkOrderApplicationService::notFound); }

    private WorkflowStatus defaultStatus(Stage stage) {
        return repository.statuses().stream().filter(s -> s.stage() == stage && s.stageDefault()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Etapa sem status padrão: " + stage));
    }

    // ------------------------------------------------------------------ itens

    /**
     * Lança um serviço ativo na OS com o preço sugerido para o veículo da OS (veículo → grupo → base) ou com
     * o preço informado manualmente. O valor praticado fica no snapshot da OS (DR-0011).
     */
    @Transactional
    public WorkOrder addService(UUID id, UUID serviceId, BigDecimal manualPrice) {
        var order = locked(id);
        order.requireOperational();
        var service = catalog.service(serviceId).orElseThrow(() -> new WorkOrderException("SERVICE_NOT_FOUND", "Serviço não encontrado"));
        if (!service.active()) throw new WorkOrderException("SERVICE_INACTIVE", "Serviço inativo não pode ser lançado na OS");
        var suggestion = catalog.suggestPrice(serviceId, order.vehicleId());
        BigDecimal price = manualPrice != null ? manualPrice : suggestion.price();
        if (price == null) throw new WorkOrderException("SERVICE_PRICE_REQUIRED", "Serviço sem preço definido: informe o preço praticado");
        String source = manualPrice == null || (suggestion.price() != null && suggestion.price().compareTo(manualPrice) == 0) ? suggestion.source() : "MANUAL";
        var item = new WorkOrder.ServiceItem(UUID.randomUUID(), service.id(), service.name(), service.description(), price,
                service.defaultWarrantyDays(), clock.instant(), source);
        repository.addService(id, item);
        return get(id);
    }

    /**
     * Lança um item físico na OS copiando do catálogo descrição, código interno, unidade e preço.
     *
     * <p>Produto inativo é recusado (AG-04, seção 13) e produto sem preço de venda também: cobrar
     * exige um preço que alguém definiu, e arbitrar um valor aqui inventaria dinheiro.</p>
     */
    @Transactional
    public WorkOrder addProduct(UUID id, UUID productId, BigDecimal quantity) {
        locked(id).requireOperational();
        var product = products.product(productId).orElseThrow(() -> new WorkOrderException("PRODUCT_NOT_FOUND", "Produto não encontrado"));
        if (!product.active()) throw new WorkOrderException("PRODUCT_INACTIVE", "Produto inativo não pode ser lançado na OS");
        if (product.salePrice() == null) throw new WorkOrderException("PRODUCT_WITHOUT_SALE_PRICE", "Produto sem preço de venda definido no catálogo");
        ProductQuantityRule.violation(product.unit(), quantity).ifPresent(message -> {
            throw new WorkOrderException("INVALID_QUANTITY_FOR_UNIT", message);
        });
        var item = new WorkOrder.ProductItem(UUID.randomUUID(), product.id(), product.description(), product.internalCode(),
                product.unit(), quantity, product.salePrice(), clock.instant());
        repository.addProduct(id, item);
        // O Estoque decide se baixa agora; recusar por saldo desfaz o lançamento, que está na mesma transação.
        events.publishEvent(new WorkOrderEvents.ProductLaunched(id, item.id(), product.id(), quantity));
        return get(id);
    }

    // ------------------------------------------------------------------ contrato público

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkOrderReference> workOrder(UUID id) {
        return repository.findById(id).map(w -> new WorkOrderReference(w.id(), w.number(), w.customerId(), w.vehicleId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceItemReference> serviceItems(UUID workOrderId) {
        return repository.findById(workOrderId).map(w -> w.services().stream()
                        .map(i -> new ServiceItemReference(i.id(), i.serviceId(), i.name(), i.description(), i.basePrice())).toList())
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductItemReference> productItems(UUID workOrderId) {
        return repository.findById(workOrderId).map(w -> w.products().stream()
                        .map(i -> new ProductItemReference(i.id(), i.productId(), i.description(), i.unit(), i.quantity(), i.unitPrice())).toList())
                .orElse(List.of());
    }

    static WorkOrderException notFound() { return new WorkOrderException("WORK_ORDER_NOT_FOUND", "Ordem de Serviço não encontrada"); }

    public record BoardColumn(WorkflowStatus status, List<WorkOrder> orders) {}
}
