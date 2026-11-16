package com.apex.commerce.media;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;

/** Serves uploaded images (public, cached for a year: file names are unique). */
@Path("/media")
public class MediaFileResource {

    @Inject
    MediaStorage storage;

    @GET
    @Path("/{store}/{name}")
    public Response serve(@PathParam("store") String store, @PathParam("name") String name) throws IOException {
        var file = storage.resolve(store + "/" + name).orElseThrow(NotFoundException::new);
        String type = MediaStorage.ALLOWED.entrySet().stream()
                .filter(e -> name.endsWith(e.getValue())).map(e -> e.getKey()).findFirst()
                .orElse("application/octet-stream");
        return Response.ok(Files.newInputStream(file), type)
                .header("Cache-Control", "public, max-age=31536000, immutable").build();
    }
}
