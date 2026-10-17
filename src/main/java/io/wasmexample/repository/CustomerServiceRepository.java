package io.wasmexample.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class CustomerServiceRepository implements PanacheRepository<CustomerServiceEntity> {

    @Transactional
    public CustomerServiceEntity saveOrUpdate(String serviceName, byte[] wasmBinary) {
        CustomerServiceEntity entity = find("serviceName", serviceName).firstResult();

        if (entity == null) {
            entity = new CustomerServiceEntity();
            entity.serviceName = serviceName;
            entity.wasmBinary = wasmBinary;
            persist(entity);
        } else {
            // L'entità trovata è gestita da Hibernate: la modifica viene salvata
            // al commit della transazione.
            entity.wasmBinary = wasmBinary;
        }

        return entity;
    }
}