package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.models.dto.response.AuditEntriesResponse;
import com.flipkart.grayskull.service.interfaces.AuditService;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public AuditEntriesResponse getAuditEntries(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, int offset, int limit) {
        
        List<AuditEntry> entries = auditEntryRepository.findByFilters(projectId, resourceName, resourceType, action, offset, limit);
        long total = auditEntryRepository.countByFilters(projectId, resourceName, resourceType, action);

        return new AuditEntriesResponse(entries, total);
    }
}

