package com.apex.commerce.orders;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem extends PanacheEntity {

    @JsonIgnore
    @ManyToOne(optional = false)
    public CustomerOrder order;

    /** Kept even if the product is later deleted. */
    public Long productId;

    @Column(nullable = false)
    public String name;

    /** Set when a specific variant was bought; kept even if the variant is later deleted. */
    public Long variantId;

    /** e.g. "M / Red" */
    public String variantTitle;

    public String imageUrl;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal unitPrice;

    @Column(nullable = false)
    public int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal lineTotal;
}
