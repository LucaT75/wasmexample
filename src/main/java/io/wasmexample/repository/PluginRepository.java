package io.wasmexample.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PluginRepository implements PanacheRepository<PluginEntity> {

    @Transactional
    public PluginEntity saveOrUpdate(String pluginName, byte[] wasmBinary) {
        PluginEntity entity = find("pluginName", pluginName).firstResult();

        if (entity == null) {
            entity = new PluginEntity();
            entity.pluginName = pluginName;
            entity.wasmBinary = wasmBinary;
            persist(entity);
        } else {
            // Hibernate manages the entity and persists the change when the transaction commits.
            entity.wasmBinary = wasmBinary;
        }

        return entity;
    }
}