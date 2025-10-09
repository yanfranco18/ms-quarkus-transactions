package com.bancario.transaction.repository;

import com.bancario.transaction.repository.entity.Transaction;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /**
     * Consulta transacciones con comisión cobrada (fee > 0) dentro de un rango de fechas.
     * * @param startDate La fecha de inicio del periodo (inclusiva).
     * @param endDate La fecha de fin del periodo (exclusiva, ya que en el servicio se usa endDate.plusDays(1)).
     * @return Uni que emite una lista de entidades Transaction.
     */
    public Uni<List<Transaction>> findCommissionsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {

        // 1. Definición del query en formato MongoDB Extended JSON:
        // Criterios:
        // - 'fee' debe ser mayor que el parámetro 1 (BigDecimal.ZERO).
        // - 'transactionDate' debe ser mayor o igual al parámetro 2 (startDate).
        // - 'transactionDate' debe ser menor que el parámetro 3 (endDate).
        String query = "{ 'fee' : { $gt: ?1 }, 'transactionDate' : { $gte: ?2, $lt: ?3 } }";

        // 2. Ejecución de la consulta reactiva
        return find(query, BigDecimal.ZERO, startDate, endDate).list();
    }
}