package br.com.uberhidraulica.erp.workorder.api;
import br.com.uberhidraulica.erp.workorder.application.WorkOrderApplicationService;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/work-orders")
public class WorkOrderController {
 private final WorkOrderApplicationService application; public WorkOrderController(WorkOrderApplicationService application){this.application=application;}
 @PostMapping ResponseEntity<Response> open(@Valid @RequestBody OpenRequest r){var x=Response.from(application.open(r.customerId(),r.vehicleId(),r.entryMileage()));return ResponseEntity.created(URI.create("/api/work-orders/"+x.id())).body(x);}
 @GetMapping("/{id}") Response get(@PathVariable UUID id){return Response.from(application.get(id));}
 @GetMapping List<Response> list(){return application.list().stream().map(Response::from).toList();}
 @PostMapping("/{id}/services") ResponseEntity<Response> add(@PathVariable UUID id,@Valid @RequestBody AddServiceRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(application.addService(id,r.serviceId())));}
 public record OpenRequest(@NotNull UUID customerId,@NotNull UUID vehicleId,@NotNull @PositiveOrZero Long entryMileage){}
 public record AddServiceRequest(@NotNull UUID serviceId){}
 public record Response(UUID id,Long number,UUID customerId,UUID vehicleId,long entryMileage,Instant openedAt,WorkOrder.Status status,List<ServiceResponse> services){static Response from(WorkOrder w){return new Response(w.id(),w.number(),w.customerId(),w.vehicleId(),w.entryMileage(),w.openedAt(),w.status(),w.services().stream().map(ServiceResponse::from).toList());}}
 public record ServiceResponse(UUID id,UUID serviceId,String name,String description,BigDecimal basePrice,int warrantyDays,Instant addedAt){static ServiceResponse from(WorkOrder.ServiceItem i){return new ServiceResponse(i.id(),i.serviceId(),i.name(),i.description(),i.basePrice(),i.warrantyDays(),i.addedAt());}}
}
