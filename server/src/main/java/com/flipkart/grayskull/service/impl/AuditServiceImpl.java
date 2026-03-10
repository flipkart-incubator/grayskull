package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.UserType;
import com.flipkart.grayskull.models.dto.response.AuditEntriesResponse;
import com.flipkart.grayskull.service.interfaces.AuditService;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Service implementation for audit operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements AuditService {

    private final AuditEntryRepository auditEntryRepository;

    @Override
    public AuditEntriesResponse getAuditEntries(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<AuditAction> action, Optional<UserType> userType, Optional<Date> afterTimestamp, int offset, int limit) {
        
        // Convert enum values to Strings for repository layer (SPI is framework-agnostic)
        Optional<String> actionString = action.map(Enum::name);
        Optional<String> userTypeString = userType.map(Enum::name);
        
        List<AuditEntry> entries = auditEntryRepository.findByFilters(projectId, resourceName, resourceType, actionString, userTypeString, afterTimestamp, offset, limit);
        long total = auditEntryRepository.countByFilters(projectId, resourceName, resourceType, actionString, userTypeString, afterTimestamp);

        return new AuditEntriesResponse(entries, total);
    }
}
