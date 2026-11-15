package com.apex.commerce.orders;

import com.apex.commerce.store.Store;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class CustomerOrder extends PanacheEntity {

    public enum Status { PENDING, PAID, SHIPPED, DELIVERED, CANCELLED }

    @JsonIgnore
    @ManyToOne(optional = false)
    public Store store;

    @Column(nullable = false, unique = true)
    public String number;

    @Column(nullable = false)
    public String customerName;

    @Column(nullable = false)
    public String email;

    public String phone;
    public String address;
    public String city;
    public String country;

    @Column(length = 2000)
    public String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false)
    public String currency = "USD";

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
