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
    @Column(unique=true, length=14) String document;
    @Column(length=13) String phone;
    @Column(length=254) String email;
    @Column(name="address_zip_code", length=8) String addressZipCode;
    @Column(name="address_street", length=160) String addressStreet;
    @Column(name="address_number", length=20) String addressNumber;
    @Column(name="address_complement", length=80) String addressComplement;
    @Column(name="address_district", length=80) String addressDistrict;
    @Column(name="address_city", length=80) String addressCity;
    @Column(name="address_state", length=2) String addressState;
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
    @Column(name="model_year") Integer modelYear;
    Long mileage;
    @Column(name="steering_gear_manufacturer", length=100) String steeringGearManufacturer;
    @Column(length=40) String color;
    @Column(length=1000) String notes;
    @Column(nullable=false) boolean active;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
}

@Entity @Table(name="vehicle_ownership", schema="crm")
class VehicleOwnershipEntity {
    @Id UUID id;
    @Column(name="vehicle_id", nullable=false) UUID vehicleId;
    @Column(name="customer_id", nullable=false) UUID customerId;
    @Column(name="started_at", nullable=false) Instant startedAt;
    @Column(name="ended_at") Instant endedAt;
    @Column(name="changed_by") UUID changedBy;
}
