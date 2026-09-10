package br.com.uberhidraulica.erp.workorder.application;
import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.servicecatalog.ServiceCatalogQuery;
import br.com.uberhidraulica.erp.workorder.domain.*;
import br.com.uberhidraulica.erp.workorder.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class WorkOrderApplicationService {
    private final WorkOrderRepositoryPort repository;
    private final CustomerVehicleQuery crm;
    private final ServiceCatalogQuery catalog;
    public WorkOrderApplicationService(WorkOrderRepositoryPort repository,CustomerVehicleQuery crm,ServiceCatalogQuery catalog){this.repository=repository;this.crm=crm;this.catalog=catalog;}

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
}
