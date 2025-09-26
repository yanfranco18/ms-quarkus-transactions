package com.bancario.transaction.repository.entity;

import com.bancario.transaction.enums.TransactionType;
import io.quarkus.mongodb.panache.common.MongoEntity;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@MongoEntity(collection = "transactions")
public class Transaction {

    public ObjectId id;
    public String accountId;
    public String customerId;
    public TransactionType transactionType;
    public BigDecimal amount;
    public LocalDateTime transactionDate;
    public String description;
}