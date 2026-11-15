package com.apex.commerce.catalog;

import com.apex.commerce.store.Store;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "slug"}))
public class Category extends PanacheEntity {

    @JsonIgnore
    @ManyToOne(optional = false)
    public Store store;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String slug;

    public String imageUrl;

    @Column(nullable = false)
    public int position = 0;
}
