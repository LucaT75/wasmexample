package io.wasmexample.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PluginRepository implements PanacheRepository<PluginEntity> {

    @Transactional
    public PluginEntity saveOrActivateVersion(String pluginName, byte[] wasmBinary,
            String sha256, int abiVersion) {
        update("active = false where pluginName = ?1 and active = true", pluginName);

        PluginEntity entity = find("pluginName = ?1 and sha256 = ?2 and abiVersion = ?3",
                pluginName, sha256, abiVersion).firstResult();
        if (entity == null) {
            entity = new PluginEntity();
            entity.pluginName = pluginName;
            entity.sha256 = sha256;
            entity.abiVersion = abiVersion;
            entity.wasmBinary = wasmBinary.clone();
            entity.active = true;
            persist(entity);
        } else {
            entity.active = true;
        }
        return entity;
    }

    @Transactional
    public boolean activateVersion(String pluginName, String sha256, int abiVersion) {
        PluginEntity version = find("pluginName = ?1 and sha256 = ?2 and abiVersion = ?3",
                pluginName, sha256, abiVersion).firstResult();
        if (version == null) {
            return false;
        }
        update("active = false where pluginName = ?1 and active = true", pluginName);
        version.active = true;
        return true;
    }
}