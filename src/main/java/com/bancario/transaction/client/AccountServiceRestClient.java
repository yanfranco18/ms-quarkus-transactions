package com.bancario.transaction.client;

import com.bancario.transaction.dto.AccountResponse;
import com.bancario.transaction.dto.AccountTransactionStatus;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "account-service")
@Path("/accounts")
public interface AccountServiceRestClient {

    @GET
    @Path("/{accountId}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<AccountResponse> getAccountById(@PathParam("accountId") String accountId);

    @PUT
    @Path("/{accountId}/update-balance")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<AccountResponse> updateAccountBalance(@PathParam("accountId") String accountId, AccountResponse accountResponse);

    /**
     * Llama al GET /accounts/{accountId}/transaction-status
     * Utilizado para obtener los límites y el contador actual para aplicar la tarifa.
     */
    @GET
    @Path("/{accountId}/transaction-status")
    Uni<AccountTransactionStatus> getTransactionStatus(@PathParam("accountId") String accountId);

    /**
     * Llama al PATCH /accounts/{accountId}/increment-transactions
     * Utilizado para actualizar el contador de transacciones de forma atómica.
     */
    @PATCH // PATCH es el verbo correcto para actualizar un subrecurso.
    @Path("/{accountId}/increment-transactions")
    Uni<Void> incrementTransactions(@PathParam("accountId") String accountId);

    /**
     * Obtiene los detalles de una cuenta usando su número de cuenta.
     * Utilizado para resolver IDs y validar cuentas de origen/destino en transferencias.
     *
     * El Path coincide con el endpoint expuesto por el Account-Service: /accounts/by-number/{accountNumber}
     */
    @GET
    @Path("/by-number/{accountNumber}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<AccountResponse> getAccountByNumber(@PathParam("accountNumber") String accountNumber);
}