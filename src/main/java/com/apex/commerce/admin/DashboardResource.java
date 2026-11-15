package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.catalog.CatalogViews.ProductView;
import com.apex.commerce.catalog.Category;
import com.apex.commerce.catalog.Product;
import com.apex.commerce.orders.CustomerOrder;
import com.apex.commerce.store.Store;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.math.BigDecimal;
import java.util.List;

@Path("/api/admin/dashboard")
@RolesAllowed(AppUser.ROLE_OWNER)
public class DashboardResource {

    public record Dashboard(long products, long activeProducts, long categories, long orders,
                            long pendingOrders, BigDecimal revenue, String currency,
                            List<CustomerOrder> recentOrders, List<ProductView> lowStock) {}

    static final int LOW_STOCK = 5;

    @Inject
    CurrentUser currentUser;

    @GET
    public Dashboard get() {
        Store s = currentUser.store();
        BigDecimal revenue = CustomerOrder.getEntityManager()
                .createQuery("select coalesce(sum(o.total), 0) from CustomerOrder o "
                        + "where o.store = :s and o.status <> :cancelled", BigDecimal.class)
                .setParameter("s", s)
                .setParameter("cancelled", CustomerOrder.Status.CANCELLED)
                .getSingleResult();
        return new Dashboard(
                Product.count("store", s),
                Product.count("store = ?1 and status = ?2", s, Product.Status.ACTIVE),
                Category.count("store", s),
                CustomerOrder.count("store", s),
                CustomerOrder.count("store = ?1 and status = ?2", s, CustomerOrder.Status.PENDING),
                revenue, s.currency,
                CustomerOrder.<CustomerOrder>find("store", Sort.descending("createdAt", "id"), s).page(0, 5).list(),
                Product.<Product>find("store = ?1 and stock <= ?2", Sort.ascending("stock"), s, LOW_STOCK)
                        .page(0, 5).list().stream().map(ProductView::of).toList());
    }
}
