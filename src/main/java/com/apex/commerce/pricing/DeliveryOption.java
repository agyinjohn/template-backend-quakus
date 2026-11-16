package com.apex.commerce.pricing;

import com.apex.commerce.store.Store;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A way a shopper can receive their order, e.g. "Accra delivery" or "Pick up from our shop". */
@Entity
@Table(name = "delivery_options")
public class DeliveryOption extends PanacheEntity {

    @JsonIgnore
    @ManyToOne(optional = false)
    public Store store;

    @Column(nullable = false)
    public String name;

    /** e.g. "1–2 working days" */
    public String description;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal price = BigDecimal.ZERO;

    /** Free when the order's items come to at least this much. Null: never free. */
    @Column(precision = 12, scale = 2)
    public BigDecimal freeOver;

    /** Collected by the shopper: no delivery address needed. */
    @Column(nullable = false)
    public boolean pickup = false;

    @Column(nullable = false)
    public int position = 0;
}
