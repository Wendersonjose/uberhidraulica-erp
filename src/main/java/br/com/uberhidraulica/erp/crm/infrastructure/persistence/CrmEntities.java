package br.com.uberhidraulica.erp.crm.infrastructure.persistence;
import br.com.uberhidraulica.erp.crm.domain.Customer;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="customer", schema="crm")
class CustomerEntity {
    @Id UUID id;
    @Enumerated(EnumType.STRING) @Column(name="person_type", nullable=false, length=2) Customer.PersonType personType;
    @Column(nullable=false, length=160) String name;
    @Column(nullable=false, unique=true, length=14) String document;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=8) Customer.Status status;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
}

@Entity @Table(name="vehicle", schema="crm")
class VehicleEntity {
    @Id UUID id;
    @Column(name="customer_id", nullable=false) UUID customerId;
    @Column(nullable=false, unique=true, length=10) String plate;
    @Column(nullable=false, length=100) String manufacturer;
    @Column(nullable=false, length=100) String model;
    @Column(name="model_year", nullable=false) int modelYear;
    Long mileage;
    @Column(name="steering_gear_manufacturer", length=100) String steeringGearManufacturer;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
}
