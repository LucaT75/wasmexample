package io.wasmexample.repository;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
// Keep the existing schema to avoid requiring a data migration.
@Table(name = "customer_service")
public class PluginEntity extends PanacheEntity {
    
    @Column(name = "service_name", unique = true, nullable = false)
    public String pluginName;

    @Lob
    @Column(nullable = false)
    public byte[] wasmBinary; 

    
}
