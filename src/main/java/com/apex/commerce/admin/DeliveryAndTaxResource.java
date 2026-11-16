package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.pricing.DeliveryOption;
import com.apex.commerce.pricing.Pricing;
import com.apex.commerce.store.Store;
import com.apex.commerce.store.StoreView;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Store settings → Delivery and Taxes. */
@Path("/api/admin/store")
@RolesAllowed(AppUser.ROLE_OWNER)
public class DeliveryAndTaxResource {

    public record DeliveryInput(
            Long id,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 160) String description,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            @DecimalMin("0.00") BigDecimal freeOver,
            boolean pickup) {}

    public record DeliverySettings(@Size(max = 20, message = "can have at most 20 options") List<@Valid DeliveryInput> options) {}

    public record TaxInput(@NotBlank @Size(max = 40) String name,
                           @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("100.0") BigDecimal rate) {}

    public record TaxSettings(boolean enabled, @Size(max = 3, message = "can have at most 3 taxes") List<@Valid TaxInput> rates,
                              boolean included, boolean applyToDelivery) {}

    @Inject
    CurrentUser currentUser;

    @GET
    @Path("/delivery")
    public List<StoreView.DeliveryOptionView> delivery() {
        return options(currentUser.store()).stream().map(StoreView.DeliveryOptionView::of).toList();
    }

    /** Replaces the whole list; options keep their ids when sent back with them. Empty list = free delivery. */
    @PUT
    @Path("/delivery")
    @Transactional
    public List<StoreView.DeliveryOptionView> setDelivery(@Valid DeliverySettings req) {
        Store store = currentUser.store();
        List<DeliveryInput> inputs = req.options() == null ? List.of() : req.options();
        var names = new HashSet<String>();
        for (DeliveryInput in : inputs) {
            if (!names.add(in.name().trim().toLowerCase())) {
                throw ApiException.badRequest("Two delivery options are called \"" + in.name().trim() + "\"");
            }
        }
        Map<Long, DeliveryOption> existing = options(store).stream().collect(Collectors.toMap(o -> o.id, Function.identity()));
        List<DeliveryOption> keep = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            DeliveryInput in = inputs.get(i);
            DeliveryOption o = in.id() != null && existing.containsKey(in.id()) ? existing.get(in.id()) : new DeliveryOption();
            o.store = store;
            o.name = in.name().trim();
            o.description = in.description() == null || in.description().isBlank() ? null : in.description().trim();
            o.price = in.price();
            o.freeOver = in.freeOver();
            o.pickup = in.pickup();
            o.position = i;
            if (o.id == null) {
                o.persist();
            }
            keep.add(o);
        }
        existing.values().stream().filter(o -> !keep.contains(o)).forEach(DeliveryOption::delete);
        return keep.stream().map(StoreView.DeliveryOptionView::of).toList();
    }

    @GET
    @Path("/taxes")
    public TaxSettings taxes() {
        return view(currentUser.store());
    }

    @PUT
    @Path("/taxes")
    @Transactional
    public TaxSettings setTaxes(@Valid TaxSettings req) {
        List<TaxInput> rates = req.rates() == null ? List.of() : req.rates();
        if (req.enabled() && rates.isEmpty()) {
            throw ApiException.badRequest("Add at least one tax (for example VAT 15%) or turn taxes off");
        }
        Store s = Store.findById(currentUser.store().id);
        s.taxesEnabled = req.enabled();
        s.taxRates = new ArrayList<>(rates.stream().map(r -> new Pricing.TaxRate(r.name().trim(), r.rate())).toList());
        s.taxIncluded = req.included();
        s.taxDelivery = req.applyToDelivery();
        return view(s);
    }

    private static TaxSettings view(Store s) {
        List<TaxInput> rates = s.taxRates == null ? List.of()
                : s.taxRates.stream().map(r -> new TaxInput(r.name(), r.rate())).toList();
        return new TaxSettings(s.taxesEnabled, rates, s.taxIncluded, s.taxDelivery);
    }

    private static List<DeliveryOption> options(Store store) {
        return DeliveryOption.list("store", Sort.ascending("position", "id"), store);
    }
}
