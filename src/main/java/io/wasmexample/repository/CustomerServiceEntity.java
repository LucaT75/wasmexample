/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package io.wasmexample.service;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;

@Entity
public class CustomerServiceEntity extends PanacheEntity {
    
    @Column(unique = true, nullable = false)
    public String serviceName; // Es: "promotion-search"

    @Lob
    @Column(nullable = false)
    public byte[] wasmBinary; 
}
