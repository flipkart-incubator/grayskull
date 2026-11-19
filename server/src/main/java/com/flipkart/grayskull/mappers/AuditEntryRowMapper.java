package com.flipkart.grayskull.mappers;

import com.flipkart.grayskull.audit.mysql.AuditEventIds;
import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.mapstruct.*;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = AuditEventIds.class)
@AnnotateWith(GeneratedMapper.class)
public interface AuditEntryRowMapper {

    /**
     * Maps an audit to a new MySQL row. The row id is derived from the event id, so the same event always maps to the
     * same row and a re-sent copy is detected as a duplicate.
     *
     * @param auditEntry the audit to store
     * @param eventId    the audit's event id, see {@link AuditEventIds#eventId}
     * @param userType   SERVICE, HUMAN or OTHER, derived from the configured user id prefixes
     * @return new AuditEntryRow object
     */
    @Mapping(target = "rowId", expression = "java(AuditEventIds.rowId(eventId))")
    @Mapping(target = "ts", expression = "java(AuditEventIds.toStoredTimestamp(auditEntry.getTimestamp()))")
    @Mapping(target = "createdAt", ignore = true)
    AuditEntryRow toRow(AuditEntry auditEntry, String eventId, String userType);

    /**
     * Maps a stored row back to the SPI model; the row id becomes the entry id.
     *
     * @param row the stored audit row
     * @return new AuditEntry object
     */
    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "timestamp", source = "ts")
    AuditEntry toEntry(AuditEntryRow row);
}
