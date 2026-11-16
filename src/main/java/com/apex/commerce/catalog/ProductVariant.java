package com.apex.commerce.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One combination of a product's options (e.g. "M / Red"), with its own stock
 * and optionally its own price, SKU and image.
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant extends PanacheEntity {

    @JsonIgnore
    @ManyToOne(optional = false)
    public Product product;

    /** Value for each of the product's options, in option order. Unused slots are null. */
    public String option1;
    public String option2;
    public String option3;

    /** Null means "same as the product". */
    @Column(precision = 12, scale = 2)
    public BigDecimal price;

    @Column(precision = 12, scale = 2)
    public BigDecimal compareAtPrice;

    public String sku;

    @Column(nullable = false)
    public int stock = 0;

    @Column(length = 1000)
    public String imageUrl;

    @Column(nullable = false)
    public int position = 0;

    public List<String> values() {
        List<String> v = new ArrayList<>(3);
        for (String s : new String[] {option1, option2, option3}) {
            if (s != null) {
                v.add(s);
            }
        }
        return v;
    }

    public void setValues(List<String> values) {
        option1 = values.size() > 0 ? values.get(0) : null;
        option2 = values.size() > 1 ? values.get(1) : null;
        option3 = values.size() > 2 ? values.get(2) : null;
    }

    /** "M / Red" */
    public String title() {
        return String.join(" / ", values());
    }

    public BigDecimal effectivePrice() {
        return price != null ? price : product.price;
    }

    public BigDecimal effectiveCompareAtPrice() {
        return price != null ? compareAtPrice : product.compareAtPrice;
    }
}
