package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.catalog.CatalogViews.CategoryView;
import com.apex.commerce.catalog.Category;
import com.apex.commerce.catalog.Product;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.common.Slugs;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/admin/categories")
@RolesAllowed(AppUser.ROLE_OWNER)
public class CategoryAdminResource {

    public record CategoryRequest(@NotBlank @Size(max = 80) String name, String imageUrl, Integer position) {}

    @Inject
    CurrentUser currentUser;

    @GET
    public List<CategoryView> list() {
        return Category.<Category>find("store", Sort.ascending("position", "name"), currentUser.store())
                .list().stream().map(CategoryView::of).toList();
    }

    @POST
    @Transactional
    public Response create(@Valid CategoryRequest req) {
        Category c = new Category();
        c.store = currentUser.store();
        c.name = req.name().trim();
        c.slug = Slugs.unique(c.name, s -> Category.count("store = ?1 and slug = ?2", c.store, s) > 0);
        c.imageUrl = req.imageUrl();
        c.position = req.position() == null ? (int) Category.count("store", c.store) : req.position();
        c.persist();
        return Response.status(Response.Status.CREATED).entity(CategoryView.of(c)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public CategoryView update(@PathParam("id") Long id, @Valid CategoryRequest req) {
        Category c = owned(id);
        if (!c.name.equals(req.name().trim())) {
            c.name = req.name().trim();
            c.slug = Slugs.unique(c.name,
                    s -> Category.count("store = ?1 and slug = ?2 and id <> ?3", c.store, s, c.id) > 0);
        }
        c.imageUrl = req.imageUrl();
        if (req.position() != null) {
            c.position = req.position();
        }
        return CategoryView.of(c);
    }

    /** Products in the category are kept and become uncategorised. */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Category c = owned(id);
        Product.update("category = null where category = ?1", c);
        c.delete();
        return Response.noContent().build();
    }

    private Category owned(Long id) {
        Category c = Category.findById(id);
        if (c == null || !c.store.id.equals(currentUser.store().id)) {
            throw ApiException.notFound("Category");
        }
        return c;
    }
}
