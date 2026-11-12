package com.flipkart.grayskull.mappers;

import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.mapstruct.*;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@AnnotateWith(GeneratedMapper.class)
public interface AuditOutboxMapper {

    /**
     * Maps a write audit to a new PENDING outbox entry. The id is left empty so MongoDB assigns it on insert;
     * it becomes the audit's event id.
     *
     * @param auditEntry the audit to queue for the audit store
     * @return new AuditOutboxEntity object
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "syncStatus", constant = "PENDING")
    @Mapping(target = "syncedAt", ignore = true)
    AuditOutboxEntity toPendingEntry(AuditEntry auditEntry);
}
