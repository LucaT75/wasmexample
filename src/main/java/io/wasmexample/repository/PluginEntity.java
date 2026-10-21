package io.wasmexample.repository;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
// Preserve the existing table name while storing immutable plugin versions.
@Table(name = "customer_service", uniqueConstraints = @UniqueConstraint(
        name = "uk_plugin_version", columnNames = {"service_name", "sha256", "abi_version"}))
public class PluginEntity extends PanacheEntity {
    
    @Column(name = "service_name", nullable = false)
    public String pluginName;

    @Lob
    @Column(nullable = false)
    public byte[] wasmBinary; 

    @Column(name = "sha256", nullable = false, length = 64)
    public String sha256;

    @Column(name = "abi_version", nullable = false)
    public int abiVersion;

    @Column(nullable = false)
    public boolean active;

    
}
