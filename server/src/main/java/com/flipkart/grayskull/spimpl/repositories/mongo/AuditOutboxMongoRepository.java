package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditOutboxEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditOutboxMongoRepository extends MongoRepository<AuditOutboxEntity, String>, AuditOutboxMongoRepositoryCustom {
}
