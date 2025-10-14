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

        switch (exception) {
            // 1. FALLO DE NEGOCIO ESPECÍFICO (400)
            case InsufficientFundsException insufficientFundsException -> {
                status = Response.Status.BAD_REQUEST.getStatusCode(); // 400
                error = "Insufficient Funds";
            }
            case ServiceUnavailableException serviceUnavailableException -> {
                // Excepción lanzada por el Fallback/Circuit Breaker
                status = Response.Status.SERVICE_UNAVAILABLE.getStatusCode(); // 503
                error = "Service Unavailable (Fault Tolerance)";
            }
            // 2. FALLO DE VALIDACIÓN GENÉRICO (400)
            case IllegalArgumentException illegalArgumentException -> {
                status = Response.Status.BAD_REQUEST.getStatusCode(); // 400
                error = "Bad Request";
            }
            // 3. FALLO CRÍTICO DE ORQUESTACIÓN (500)
            case TransferIncompleteException transferIncompleteException -> {
                status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(); // 500
                error = "Transfer Incomplete - Funds Reverted"; // Indica que el proceso falló a mitad y se compensó.
            }
            // 4. MANEJO DE ERRORES DE INFRAESTRUCTURA (500)
            case MongoCommandException mongoCommandException -> {
                status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(); // 500
                error = "Database Error";
            }
            // 5. MANEJO DE ERRORES POR DEFECTO (500)
            case null, default -> {
                status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(); // 500
                error = "Internal Server Error";
            }
        }

        assert exception != null;
        ApiError apiError = ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .error(error)
                .message(exception.getMessage())
                .path(uriInfo.getPath())
                .build();

        return Response.status(status).entity(apiError).build();
    }
}