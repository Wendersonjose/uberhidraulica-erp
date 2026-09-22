package br.com.uberhidraulica.erp.workorder.api;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.workorder.application.WorkOrderApplicationService;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {
    private final WorkOrderApplicationService application;
    private final CustomerVehicleQuery crm;

    public WorkOrderController(WorkOrderApplicationService application, CustomerVehicleQuery crm) {
        this.application = application;
        this.crm = crm;
    }

    @PostMapping
    ResponseEntity<Response> open(@Valid @RequestBody OpenRequest r) {
        var x = Response.from(application.open(r.customerId(), r.vehicleId(), r.entryMileage(), r.complaint(), r.notes()));
        return ResponseEntity.created(URI.create("/api/work-orders/" + x.id())).body(x);
    }

    @GetMapping("/{id}") Response get(@PathVariable UUID id) { return Response.from(application.get(id)); }

    @GetMapping
    List<Response> list(@RequestParam(required = false) UUID customerId, @RequestParam(required = false) UUID vehicleId) {
        return application.list(customerId, vehicleId).stream().map(Response::from).toList();
    }

    @PutMapping("/{id}")
    Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return Response.from(application.updateDetails(id, r.entryMileage(), r.complaint(), r.notes()));
    }

    @PutMapping("/{id}/diagnosis") Response diagnosis(@PathVariable UUID id, @Valid @RequestBody DiagnosisRequest r) { return Response.from(application.registerDiagnosis(id, r.diagnosis())); }
    @PostMapping("/{id}/status") Response move(@PathVariable UUID id, @Valid @RequestBody MoveRequest r) { return Response.from(application.move(id, r.statusId(), r.reason())); }
    @PostMapping("/{id}/start-execution") Response startExecution(@PathVariable UUID id) { return Response.from(application.startExecution(id)); }
    /** Corpo opcional: {@code billingQuoteId} escolhe o orçamento de faturamento quando há mais de um candidato. */
    @org.springframework.security.access.prepost.PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_BILL')")
    @PostMapping("/{id}/finish") Response finish(@PathVariable UUID id, @RequestBody(required = false) FinishRequest r) {
        return Response.from(application.finish(id, r == null ? null : r.billingQuoteId()));
    }
    @PostMapping("/{id}/deliver") Response deliver(@PathVariable UUID id) { return Response.from(application.deliver(id)); }
    @PostMapping("/{id}/cancel") Response cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) { return Response.from(application.cancel(id, r.reason())); }

    @GetMapping("/{id}/status-history")
    List<HistoryResponse> history(@PathVariable UUID id) {
        Map<UUID, String> names = new HashMap<>();
        application.statuses().forEach(s -> names.put(s.id(), s.name()));
        return application.history(id).stream().map(h -> new HistoryResponse(h.id(), h.fromStatusId(), names.get(h.fromStatusId()),
                h.toStatusId(), names.get(h.toStatusId()), h.changedAt(), h.changedBy(), h.reason(), h.automatic())).toList();
    }

    /** Kanban com dados de cliente e veículo para os cartões; `closedDays` limita as colunas de encerramento. */
    @GetMapping("/board")
    List<BoardColumnResponse> board(@RequestParam(defaultValue = "30") int closedDays) {
        Map<UUID, Optional<CustomerVehicleQuery.CustomerReference>> customers = new HashMap<>();
        Map<UUID, Optional<CustomerVehicleQuery.VehicleReference>> vehicles = new HashMap<>();
        return application.board(closedDays).stream().map(column -> new BoardColumnResponse(StatusResponse.from(column.status()),
                column.orders().stream().map(o -> new BoardCard(o.id(), o.number(), o.openedAt(), o.complaint(),
                        customers.computeIfAbsent(o.customerId(), crm::customer).map(CustomerVehicleQuery.CustomerReference::name).orElse(null),
                        vehicles.computeIfAbsent(o.vehicleId(), crm::vehicle).map(v -> v.manufacturer() + " " + v.model() + " • " + v.plate()).orElse(null)))
                        .toList())).toList();
    }

    @PostMapping("/{id}/services")
    ResponseEntity<Response> add(@PathVariable UUID id, @Valid @RequestBody AddServiceRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(application.addService(id, r.serviceId(), r.price())));
    }

    @PostMapping("/{id}/products")
    ResponseEntity<Response> addProduct(@PathVariable UUID id, @Valid @RequestBody AddProductRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(application.addProduct(id, r.productId(), r.quantity())));
    }

    public record OpenRequest(@NotNull UUID customerId, @NotNull UUID vehicleId, @PositiveOrZero Long entryMileage,
                              @NotBlank @Size(max = 2000) String complaint, @Size(max = 2000) String notes) {}
    public record UpdateRequest(@PositiveOrZero Long entryMileage, @NotBlank @Size(max = 2000) String complaint, @Size(max = 2000) String notes) {}
    public record DiagnosisRequest(@NotBlank @Size(max = 4000) String diagnosis) {}
    public record MoveRequest(@NotNull UUID statusId, @Size(max = 500) String reason) {}
    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {}
    public record AddServiceRequest(@NotNull UUID serviceId, @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal price) {}
    public record AddProductRequest(@NotNull UUID productId, @NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 12, fraction = 3) BigDecimal quantity) {}

    public record StatusResponse(UUID id, String name, WorkflowStatus.Stage stage, int position, boolean active, boolean stageDefault) {
        static StatusResponse from(WorkflowStatus s) { return new StatusResponse(s.id(), s.name(), s.stage(), s.position(), s.active(), s.stageDefault()); }
    }

    public record LifecycleResponse(Instant diagnosedAt, UUID diagnosedBy, Instant executionStartedAt, Instant finishedAt, UUID finishedBy, Instant deliveredAt, UUID deliveredBy,
                                    Instant cancelledAt, UUID cancelledBy, String cancellationReason) {
        static LifecycleResponse from(WorkOrder.Lifecycle l) {
            return new LifecycleResponse(l.diagnosedAt(), l.diagnosedBy(), l.executionStartedAt(), l.finishedAt(), l.finishedBy(), l.deliveredAt(), l.deliveredBy(), l.cancelledAt(), l.cancelledBy(), l.cancellationReason());
        }
    }

    /** `status` continua sendo a etapa (ex.: `ABERTA`) para compatibilidade; `statusInfo` traz a coluna configurada. */
    public record Response(UUID id, Long number, UUID customerId, UUID vehicleId, Long entryMileage, Instant openedAt, WorkflowStatus.Stage status,
                           StatusResponse statusInfo, String complaint, String notes, String diagnosis, LifecycleResponse lifecycle,
                           List<ServiceResponse> services, List<ProductResponse> products) {
        static Response from(WorkOrder w) {
            return new Response(w.id(), w.number(), w.customerId(), w.vehicleId(), w.entryMileage(), w.openedAt(), w.stage(), StatusResponse.from(w.status()),
                    w.complaint(), w.notes(), w.diagnosis(), LifecycleResponse.from(w.lifecycle()),
                    w.services().stream().map(ServiceResponse::from).toList(), w.products().stream().map(ProductResponse::from).toList());
        }
    }

    public record ServiceResponse(UUID id, UUID serviceId, String name, String description, BigDecimal basePrice, int warrantyDays, Instant addedAt, String priceSource) {
        static ServiceResponse from(WorkOrder.ServiceItem i) {
            return new ServiceResponse(i.id(), i.serviceId(), i.name(), i.description(), i.basePrice(), i.warrantyDays(), i.addedAt(), i.priceSource());
        }
    }

    public record ProductResponse(UUID id, UUID productId, String description, String internalCode, String unit, BigDecimal quantity, BigDecimal unitPrice, Instant addedAt) {
        static ProductResponse from(WorkOrder.ProductItem i) {
            return new ProductResponse(i.id(), i.productId(), i.description(), i.internalCode(), i.unit(), i.quantity(), i.unitPrice(), i.addedAt());
        }
    }

    public record HistoryResponse(UUID id, UUID fromStatusId, String fromStatusName, UUID toStatusId, String toStatusName, Instant changedAt,
                                  UUID changedBy, String reason, boolean automatic) {}
    public record BoardCard(UUID id, Long number, Instant openedAt, String complaint, String customerName, String vehicleLabel) {}
    public record BoardColumnResponse(StatusResponse status, List<BoardCard> orders) {}
    public record FinishRequest(UUID billingQuoteId) {}
}
