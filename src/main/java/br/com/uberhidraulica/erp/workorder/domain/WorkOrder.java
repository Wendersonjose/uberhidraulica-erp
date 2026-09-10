package br.com.uberhidraulica.erp.workorder.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public record WorkOrder(UUID id,Long number,UUID customerId,UUID vehicleId,long entryMileage,Instant openedAt,
                        Status status,Instant createdAt,Instant updatedAt,List<ServiceItem> services) {
    public enum Status { ABERTA }
    public WorkOrder {
        if(id==null||customerId==null||vehicleId==null||openedAt==null||status==null) throw invalid("Dados obrigatórios ausentes");
        if(entryMileage<0) throw invalid("Quilometragem de entrada inválida");
        services=services==null?List.of():List.copyOf(services);
    }
    public static WorkOrder open(UUID customerId,UUID vehicleId,Long mileage,Instant now){
        if(mileage==null) throw invalid("Quilometragem de entrada é obrigatória");
        return new WorkOrder(UUID.randomUUID(),null,customerId,vehicleId,mileage,now,Status.ABERTA,now,now,List.of());
    }
    public WorkOrder withServices(List<ServiceItem> items){return new WorkOrder(id,number,customerId,vehicleId,entryMileage,openedAt,status,createdAt,updatedAt,items);}
    public record ServiceItem(UUID id,UUID serviceId,String name,String description,BigDecimal basePrice,int warrantyDays,Instant addedAt) {}
    private static WorkOrderException invalid(String m){return new WorkOrderException("INVALID_WORK_ORDER",m);}
}
