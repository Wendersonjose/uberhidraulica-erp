package br.com.uberhidraulica.erp.workorder.application;
import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.servicecatalog.ServiceCatalogQuery;
import br.com.uberhidraulica.erp.workorder.WorkOrderQuery;
import br.com.uberhidraulica.erp.workorder.domain.*;
import br.com.uberhidraulica.erp.workorder.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class WorkOrderApplicationService implements WorkOrderQuery {
    private final WorkOrderRepositoryPort repository;
    private final CustomerVehicleQuery crm;
    private final ServiceCatalogQuery catalog;
    private final ProductCatalogQuery products;
    public WorkOrderApplicationService(WorkOrderRepositoryPort repository,CustomerVehicleQuery crm,ServiceCatalogQuery catalog,ProductCatalogQuery products){this.repository=repository;this.crm=crm;this.catalog=catalog;this.products=products;}

    @Transactional public WorkOrder open(UUID customerId,UUID vehicleId,Long mileage){
        crm.customer(customerId).orElseThrow(()->new WorkOrderException("CUSTOMER_NOT_FOUND","Cliente não encontrado"));
        var vehicle=crm.vehicle(vehicleId).orElseThrow(()->new WorkOrderException("VEHICLE_NOT_FOUND","Veículo não encontrado"));
        if(!vehicle.belongsTo(customerId)) throw new WorkOrderException("VEHICLE_CUSTOMER_MISMATCH","Veículo não pertence ao cliente informado");
        return repository.save(WorkOrder.open(customerId,vehicleId,mileage,Instant.now()));
    }
    @Transactional(readOnly=true) public WorkOrder get(UUID id){return repository.findById(id).orElseThrow(()->new WorkOrderException("WORK_ORDER_NOT_FOUND","Ordem de Serviço não encontrada"));}
    @Transactional(readOnly=true) public List<WorkOrder> list(){return repository.findAll();}
    @Transactional public WorkOrder addService(UUID id,UUID serviceId){
        get(id);
        var service=catalog.service(serviceId).orElseThrow(()->new WorkOrderException("SERVICE_NOT_FOUND","Serviço não encontrado"));
        var item=new WorkOrder.ServiceItem(UUID.randomUUID(),service.id(),service.name(),service.description(),service.basePrice(),service.defaultWarrantyDays(),Instant.now());
        repository.addService(id,item);
        return get(id);
    }

    @Override @Transactional(readOnly=true) public Optional<WorkOrderReference> workOrder(UUID id){
        return repository.findById(id).map(w->new WorkOrderReference(w.id(),w.number(),w.customerId(),w.vehicleId()));
    }
    @Override @Transactional(readOnly=true) public List<ServiceItemReference> serviceItems(UUID workOrderId){
        return repository.findById(workOrderId).map(w->w.services().stream()
                .map(i->new ServiceItemReference(i.id(),i.serviceId(),i.name(),i.description(),i.basePrice())).toList())
                .orElse(List.of());
    }

    /**
     * Lança um item físico na OS copiando do catálogo descrição, código interno, unidade e preço.
     *
     * <p>Produto inativo é recusado (AG-04, seção 13) e produto sem preço de venda também: cobrar
     * exige um preço que alguém definiu, e arbitrar um valor aqui inventaria dinheiro. Nenhum saldo
     * de estoque é consultado ou reservado — o módulo Estoque não existe.</p>
     */
    @Transactional public WorkOrder addProduct(UUID id,UUID productId,BigDecimal quantity){
        get(id);
        var product=products.product(productId).orElseThrow(()->new WorkOrderException("PRODUCT_NOT_FOUND","Produto não encontrado"));
        if(!product.active()) throw new WorkOrderException("PRODUCT_INACTIVE","Produto inativo não pode ser lançado na OS");
        if(product.salePrice()==null) throw new WorkOrderException("PRODUCT_WITHOUT_SALE_PRICE","Produto sem preço de venda definido no catálogo");
        var item=new WorkOrder.ProductItem(UUID.randomUUID(),product.id(),product.description(),product.internalCode(),
                product.unit(),quantity,product.salePrice(),Instant.now());
        repository.addProduct(id,item);
        return get(id);
    }
}
