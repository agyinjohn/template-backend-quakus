package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.catalog.CatalogViews.Page;
import com.apex.commerce.catalog.Product;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.orders.CustomerOrder;
import com.apex.commerce.orders.OrderItem;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Path("/api/admin/orders")
@RolesAllowed(AppUser.ROLE_OWNER)
public class OrderAdminResource {

    public record StatusUpdate(@NotNull CustomerOrder.Status status) {}

    @Inject
    CurrentUser currentUser;

    @GET
    public Page<CustomerOrder> list(@QueryParam("status") CustomerOrder.Status status,
                                    @QueryParam("page") @DefaultValue("0") int page,
                                    @QueryParam("size") @DefaultValue("25") int size) {
        size = Math.min(Math.max(size, 1), 100);
        var query = status == null
                ? CustomerOrder.<CustomerOrder>find("store", Sort.descending("createdAt", "id"), currentUser.store())
                : CustomerOrder.<CustomerOrder>find("store = ?1 and status = ?2",
                        Sort.descending("createdAt", "id"), currentUser.store(), status);
        return new Page<>(query.page(page, size).list(), query.count(), page, size);
    }

    @GET
    @Path("/{id}")
    public CustomerOrder get(@PathParam("id") Long id) {
        return owned(id);
    }

    /** Cancelling an order puts its items back in stock. */
    @PATCH
    @Path("/{id}")
    @Transactional
    public CustomerOrder updateStatus(@PathParam("id") Long id, @Valid StatusUpdate req) {
        CustomerOrder o = owned(id);
        if (o.status == CustomerOrder.Status.CANCELLED && req.status() != CustomerOrder.Status.CANCELLED) {
            throw ApiException.badRequest("A cancelled order can't be reopened");
        }
        if (req.status() == CustomerOrder.Status.CANCELLED && o.status != CustomerOrder.Status.CANCELLED) {
            for (OrderItem item : o.items) {
                if (item.productId != null) {
                    Product.update("stock = stock + ?1 where id = ?2", item.quantity, item.productId);
                }
            }
        }
        o.status = req.status();
        return o;
    }

    private CustomerOrder owned(Long id) {
        CustomerOrder o = CustomerOrder.findById(id);
        if (o == null || !o.store.id.equals(currentUser.store().id)) {
            throw ApiException.notFound("Order");
        }
        return o;
    }
}
