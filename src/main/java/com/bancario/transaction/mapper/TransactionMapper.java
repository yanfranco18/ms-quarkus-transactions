package com.bancario.transaction.mapper;

import com.bancario.transaction.dto.CommissionReportDto;
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
    @Mapping(target = "externalReference", ignore = true)
    @Mapping(target = "fee", ignore = true)
    @Mapping(target = "productType", ignore = true)
    @Mapping(target = "productName", ignore = true)
    Transaction toEntity(TransactionRequest request);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "externalReference", source = "externalReference")
    @Mapping(target = "fee", source = "fee") // <-- Mapeo añadido
    TransactionResponse toResponse(Transaction transaction);

    @Mapping(target = "accountId", source = "accountId")
    @Mapping(target = "productType", source = "productType")
    @Mapping(target = "productName", source = "productName")
    @Mapping(target = "fee", source = "fee")
    @Mapping(target = "transactionDate", source = "transactionDate")
    CommissionReportDto toCommissionReportDto(Transaction transaction);

    default String map(ObjectId objectId) {
        return objectId != null ? objectId.toHexString() : null;
    }
}