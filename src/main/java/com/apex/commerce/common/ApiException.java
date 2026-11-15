package com.apex.commerce.common;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Throw to return {"error": "...", "message": "..."} with the given status. */
public class ApiException extends WebApplicationException {

    public record Body(String error, String message) {}

    public ApiException(Response.Status status, String message) {
        super(message, Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new Body(status.getReasonPhrase(), message))
                .build());
    }

    public static ApiException badRequest(String message) {
        return new ApiException(Response.Status.BAD_REQUEST, message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(Response.Status.NOT_FOUND, what + " not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(Response.Status.CONFLICT, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(Response.Status.UNAUTHORIZED, message);
    }
}
