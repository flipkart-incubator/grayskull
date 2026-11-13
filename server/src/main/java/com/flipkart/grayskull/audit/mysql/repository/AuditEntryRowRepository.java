package com.flipkart.grayskull.audit.mysql.repository;

import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.audit.mysql.entity.AuditRowId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface AuditEntryRowRepository extends JpaRepository<AuditEntryRow, AuditRowId>, JpaSpecificationExecutor<AuditEntryRow> {

    /** Row ids among {@code rowIds} that are already stored; used to skip duplicates before inserting. */
    @Query("select r.rowId from AuditEntryRow r where r.rowId in :rowIds")
    Set<UUID> findExistingRowIds(@Param("rowIds") Collection<UUID> rowIds);
}
