package com.apex.commerce.media;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/")
public class MediaResource {

    public record Uploaded(String url, String path) {}

    @Inject
    MediaStorage storage;

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "app.public-url")
    String publicUrl;

    /** multipart/form-data with a "file" field. Returns the public URL to store on a product. */
    @POST
    @Path("api/admin/uploads")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed(AppUser.ROLE_OWNER)
    public Response upload(@RestForm("file") FileUpload file) {
        if (file == null) {
            throw com.apex.commerce.common.ApiException.badRequest("Send the image in a form field named 'file'");
        }
        String rel = storage.save(currentUser.store().id, file.uploadedFile(), file.contentType(), file.size());
        String path = "/media/" + rel;
        return Response.status(Response.Status.CREATED)
                .entity(new Uploaded(publicUrl.replaceAll("/$", "") + path, path)).build();
    }

    @GET
    @Path("media/{store}/{name}")
    public Response serve(@PathParam("store") String store, @PathParam("name") String name) throws IOException {
        var file = storage.resolve(store + "/" + name).orElseThrow(NotFoundException::new);
        String type = MediaStorage.ALLOWED.entrySet().stream()
                .filter(e -> name.endsWith(e.getValue())).map(e -> e.getKey()).findFirst()
                .orElse("application/octet-stream");
        return Response.ok(Files.newInputStream(file), type)
                .header("Cache-Control", "public, max-age=31536000, immutable").build();
    }
}
