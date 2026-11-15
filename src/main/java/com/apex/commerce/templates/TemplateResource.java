package com.apex.commerce.templates;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;

@Path("/api/templates")
public class TemplateResource {

    @GET
    public List<TemplateCatalog.Template> list() {
        return TemplateCatalog.ALL;
    }
}
