package com.bancario.transaction.exception;

import com.mongodb.MongoCommandException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.LocalDateTime;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(Exception exception) {
        int status;
        String error;

        // 1. MANEJO ESPECÍFICO (Insuficiencia de Fondos - La más específica)
        switch (exception) {
            case InsufficientFundsException insufficientFundsException -> {
                status = Response.Status.BAD_REQUEST.getStatusCode(); // 400

                error = "Insufficient Funds"; // Mensaje específico para el error de negocio
            }
            // 2. MANEJO GENÉRICO DE VALIDACIÓN DE NEGOCIO (Menos específico)
            case IllegalArgumentException illegalArgumentException -> {
                status = Response.Status.BAD_REQUEST.getStatusCode(); // 400

                error = "Bad Request";
            }
            // 3. MANEJO DE ERRORES DE INFRAESTRUCTURA
            case MongoCommandException mongoCommandException -> {
                status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(); // 500

                error = "Database Error";
            }
            // 4. MANEJO DE ERRORES POR DEFECTO
            case null, default -> {
                status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(); // 500

                error = "Internal Server Error";
            }
        }

        ApiError apiError = ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .error(error)
                .message(exception.getMessage())
                .path(uriInfo.getPath()) // Usando UriInfo para obtener la ruta
                .build();

        return Response.status(status).entity(apiError).build();
    }
}