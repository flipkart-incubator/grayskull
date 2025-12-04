package com.flipkart.grayskull.mappers;

import com.flipkart.grayskull.entities.AuditEntryEntity;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.mapstruct.AnnotateWith;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@AnnotateWith(GeneratedMapper.class)
public interface AuditEntryMapper {

    AuditEntryEntity mapToEntity(AuditEntry auditEntry);

    default AuditEntryEntity toEntity(AuditEntry auditEntry) {
        if (auditEntry instanceof AuditEntryEntity entity) {
            return entity;
        }
        return mapToEntity(auditEntry);
    }
}
