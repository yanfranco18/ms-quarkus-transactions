package com.bancario.transaction.mapper;

import com.bancario.transaction.dto.TransactionRequest;
import com.bancario.transaction.dto.TransactionResponse;
import com.bancario.transaction.repository.entity.Transaction;
import org.bson.types.ObjectId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "cdi")
public interface TransactionMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "transactionType", ignore = true)
    @Mapping(target = "transactionDate", ignore = true)
    Transaction toEntity(TransactionRequest request);

    @Mapping(target = "id", source = "id")
    TransactionResponse toResponse(Transaction transaction);

    default String map(ObjectId objectId) { // <-- Renombra el método a un nombre genérico como 'map'
        return objectId != null ? objectId.toHexString() : null;
    }
}