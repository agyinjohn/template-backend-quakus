package com.apex.commerce.common;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns bean-validation failures into {"error","message","fields":{field: problem}}. */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException e) {
        Map<String, String> fields = e.getConstraintViolations().stream().collect(Collectors.toMap(
                v -> {
                    String path = v.getPropertyPath().toString();
                    return path.substring(path.lastIndexOf('.') + 1);
                },
                v -> v.getMessage(),
                (a, b) -> a));
        String message = fields.entrySet().stream()
                .map(en -> en.getKey() + " " + en.getValue())
                .collect(Collectors.joining("; "));
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Bad Request", "message", message, "fields", fields))
                .build();
    }
}
