package br.com.uberhidraulica.erp.workorder.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public record WorkOrder(UUID id,Long number,UUID customerId,UUID vehicleId,long entryMileage,Instant openedAt,
                        Status status,Instant createdAt,Instant updatedAt,List<ServiceItem> services,
                        List<ProductItem> products) {
    public enum Status { ABERTA }
    public WorkOrder {
        if(id==null||customerId==null||vehicleId==null||openedAt==null||status==null) throw invalid("Dados obrigatórios ausentes");
        if(entryMileage<0) throw invalid("Quilometragem de entrada inválida");
        services=services==null?List.of():List.copyOf(services);
        products=products==null?List.of():List.copyOf(products);
    }
    public static WorkOrder open(UUID customerId,UUID vehicleId,Long mileage,Instant now){
        if(mileage==null) throw invalid("Quilometragem de entrada é obrigatória");
        return new WorkOrder(UUID.randomUUID(),null,customerId,vehicleId,mileage,now,Status.ABERTA,now,now,List.of(),List.of());
    }
    public record ServiceItem(UUID id,UUID serviceId,String name,String description,BigDecimal basePrice,int warrantyDays,Instant addedAt) {}
    /**
     * Item físico lançado na OS. Descrição, código interno, unidade e preço unitário são snapshot
     * do catálogo no instante do lançamento: alteração posterior do produto não reescreve a OS.
     *
     * <p>Nenhum total é calculado ou persistido aqui. A regra comercial de arredondamento ainda
     * não foi decidida (DR-0006) e totalizar cedo produziria um valor com autoridade que ele não tem.</p>
     */
    public record ProductItem(UUID id,UUID productId,String description,String internalCode,String unit,
                              BigDecimal quantity,BigDecimal unitPrice,Instant addedAt) {
        public ProductItem {
            if(id==null||productId==null||addedAt==null) throw invalid("Dados obrigatórios ausentes");
            description=required(description,"Descrição do produto",200);
            unit=required(unit,"Unidade do produto",16);
            internalCode=internalCode==null||internalCode.isBlank()?null:internalCode.trim();
            if(quantity==null||quantity.signum()<=0||quantity.scale()>3) throw invalid("Quantidade deve ser positiva e possuir no máximo três casas decimais");
            if(unitPrice==null||unitPrice.signum()<0||unitPrice.scale()>2) throw invalid("Preço unitário deve ser não negativo e possuir no máximo duas casas decimais");
        }
    }
    private static String required(String value,String label,int max){
        if(value==null||value.isBlank()||value.trim().length()>max) throw invalid(label+" inválida");
        return value.trim();
    }
    private static WorkOrderException invalid(String m){return new WorkOrderException("INVALID_WORK_ORDER",m);}
}
