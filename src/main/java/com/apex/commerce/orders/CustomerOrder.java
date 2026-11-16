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

    public enum PaymentMethod {
        PAYSTACK("Paid online"), BANK_TRANSFER("Direct bank transfer"), CASH_ON_DELIVERY("Cash on delivery");

        public final String label;

        PaymentMethod(String label) {
            this.label = label;
        }
    }

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

    /** Delivery option chosen at checkout (copied, so later edits to the option don't change the order). */
    public String deliveryName;

    @Column(nullable = false, precision = 12, scale = 2, columnDefinition = "numeric(12,2) default 0")
    public BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean pickup = false;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 2000)
    public java.util.List<com.apex.commerce.pricing.Pricing.TaxLine> taxes = new java.util.ArrayList<>();

    @Column(nullable = false, precision = 12, scale = 2, columnDefinition = "numeric(12,2) default 0")
    public BigDecimal taxTotal = BigDecimal.ZERO;

    /** Prices included the taxes (so {@link #taxTotal} is part of, not added to, the total). */
    @Column(nullable = false, columnDefinition = "boolean default true")
    public boolean taxIncluded = true;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false)
    public String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32) default 'BANK_TRANSFER'")
    public PaymentMethod paymentMethod = PaymentMethod.BANK_TRANSFER;

    /** Set once payment is confirmed (by Paystack, or by the owner marking the order paid). */
    public Instant paidAt;

    /** Paystack transaction reference for online payments. */
    @Column(unique = true)
    public String paymentReference;

    /** How an online payment was made, e.g. "card" or "mobile_money". */
    public String paymentChannel;

    public boolean isPaid() {
        return paidAt != null;
    }

    /** Online orders the shopper hasn't paid for yet. */
    public boolean isAwaitingPayment() {
        return paymentMethod == PaymentMethod.PAYSTACK && paidAt == null && status == Status.PENDING;
    }

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
