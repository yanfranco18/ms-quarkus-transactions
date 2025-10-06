package com.bancario.transaction.resource;

import com.bancario.transaction.dto.TransactionRequest;
import com.bancario.transaction.dto.TransactionResponse;
import com.bancario.transaction.dto.TransferRequest;
import com.bancario.transaction.service.TransactionService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transactions", description = "Operations for managing bank account transactions.")
public class TransactionResource {

    @Inject
    TransactionService transactionService;

    @POST
    @Path("/deposit")
    @Operation(summary = "Process a deposit into a bank account.")
    @APIResponse(
            responseCode = "200",
            description = "Deposit successful",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class)
            )
    )
    @APIResponse(responseCode = "400", description = "Invalid request or account not found/inactive")
    @APIResponse(responseCode = "500", description = "Internal server error")
    public Uni<Response> processDeposit(
            @RequestBody(
                    description = "Transaction details for a deposit",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionRequest.class))
            )
            TransactionRequest request) {
        return transactionService.processDeposit(request)
                .onItem().transform(transactionResponse ->
                        Response.ok(transactionResponse).build()
                );
    }

    @POST
    @Path("/withdrawal")
    @Operation(summary = "Process a withdrawal from a bank account.")
    @APIResponse(
            responseCode = "200",
            description = "Withdrawal successful",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class)
            )
    )
    @APIResponse(responseCode = "400", description = "Invalid request, insufficient funds, or account not found/inactive")
    @APIResponse(responseCode = "500", description = "Internal server error")
    public Uni<Response> processWithdrawal(
            @RequestBody(
                    description = "Transaction details for a withdrawal",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionRequest.class))
            )
            TransactionRequest request) {
        return transactionService.processWithdrawal(request)
                .onItem().transform(transactionResponse ->
                        Response.ok(transactionResponse).build()
                );
    }

    @POST
    @Path("/payment")
    @Operation(summary = "Process a payment for a credit product.")
    @APIResponse(
            responseCode = "200",
            description = "Payment successful",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class)
            )
    )
    @APIResponse(responseCode = "400", description = "Invalid request, no pending debt, or product not found/inactive")
    @APIResponse(responseCode = "500", description = "Internal server error")
    public Uni<Response> processPayment(
            @RequestBody(
                    description = "Transaction details for a credit payment",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionRequest.class))
            )
            TransactionRequest request) {
        return transactionService.processPayment(request)
                .onItem().transform(transactionResponse ->
                        Response.ok(transactionResponse).build()
                );
    }

    @POST
    @Path("/consumption")
    @Operation(summary = "Processes a consumption for a credit card.",
            description = "Charges a consumption to a credit card, updating the amount used based on the available credit limit.")
    @APIResponse(
            responseCode = "200",
            description = "Consumption successful",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class)
            )
    )
    @APIResponse(responseCode = "400", description = "Invalid request, account is not a credit card, or consumption exceeds the available limit.")
    @APIResponse(responseCode = "500", description = "Internal server error")
    public Uni<Response> processConsumption(
            @RequestBody(
                    description = "Transaction details for a credit card consumption",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionRequest.class))
            )
            TransactionRequest request) {
        return transactionService.processConsumption(request)
                .onItem().transform(transactionResponse ->
                        Response.ok(transactionResponse).build()
                );
    }

    @GET
    @Operation(summary = "Obtiene todos los movimientos para una cuenta específica.")
    @APIResponse(
            responseCode = "200",
            description = "Consulta de movimientos exitosa",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class)
            )
    )
    @APIResponse(responseCode = "500", description = "Error interno del servidor")
    public Multi<TransactionResponse> getTransactionsByAccountId(
            @QueryParam("accountId") String accountId) {

        // Nota: Este método debe devolver Multi<TransactionResponse>
        // para un flujo de datos reactivo.
        return transactionService.findByAccountId(accountId);
    }

    @POST
    @Path("/transfers")
    @Operation(summary = "Realiza una transferencia de fondos entre dos cuentas.",
            description = "Orquesta el débito de la cuenta de origen y el crédito de la cuenta de destino, aplicando las reglas de tarificación.")

    @RequestBody(required = true, description = "Datos de la cuenta de origen, destino y monto.",
            content = @Content(schema = @Schema(implementation = TransferRequest.class)))

    @APIResponse(responseCode = "200", description = "Transferencia exitosa.",
            content = @Content(schema = @Schema(implementation = TransactionResponse.class)))
    @APIResponse(responseCode = "400", description = "Solicitud inválida, cuenta no encontrada, saldo insuficiente, o cuenta no activa (Manejado por GlobalExceptionMapper).",
            content = @Content(mediaType = MediaType.APPLICATION_JSON))
    @APIResponse(responseCode = "500", description = "Fallo interno del servidor o error de compensación.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON))
    public Uni<Response> processTransfer(@Valid TransferRequest request) {
        return transactionService.processTransfer(request)
                .onItem().transform(response ->
                        Response.ok(response).build()
                );
    }
}