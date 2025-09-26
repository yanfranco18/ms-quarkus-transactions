package com.bancario.transaction.client;

import com.bancario.transaction.dto.AccountResponse;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

// Esta anotación le dice a Quarkus que es un cliente REST.
// El 'baseUri' se toma de 'application.properties'.
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
}