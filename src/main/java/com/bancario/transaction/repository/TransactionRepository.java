package com.bancario.transaction.repository;

import com.bancario.transaction.repository.entity.Transaction;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class TransactionRepository implements ReactivePanacheMongoRepository<Transaction> {
    /**
     * Declara el método de búsqueda por accountId.
     * Panache se encarga automáticamente de la implementación de la consulta
     * basada en el nombre del método (find by AccountId).
     */
    public Uni<List<Transaction>> findByAccountId(String accountId) {
        // Usamos el método 'find' de Panache para construir la consulta.
        // El 'find' devuelve un Multi, y luego lo convertimos a un Uni<List>
        return find("accountId", accountId)
                .list()
                .onItem().transformToUni(list -> Uni.createFrom().item(list));
    }
}