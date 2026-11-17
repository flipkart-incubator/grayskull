package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.entities.AuditSyncStatus;
import com.mongodb.ReadPreference;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class AuditOutboxMongoRepositoryImpl implements AuditOutboxMongoRepositoryCustom {

    static final String SYNC_STATUS = "syncStatus";
    static final String TIMESTAMP = "timestamp";
    static final String SYNCED_AT = "syncedAt";

    private final MongoTemplate mongoTemplate;

    @Override
    public List<AuditOutboxEntity> findPending(int limit) {
        Query query = Query.query(where(SYNC_STATUS).is(AuditSyncStatus.PENDING))
                .with(Sort.by(Sort.Direction.ASC, TIMESTAMP))
                .limit(limit)
                .withReadPreference(ReadPreference.primary());
        return mongoTemplate.find(query, AuditOutboxEntity.class);
    }

    @Override
    public long markSynced(Collection<String> ids, Instant syncedAt) {
        Query query = Query.query(where("id").in(ids).and(SYNC_STATUS).is(AuditSyncStatus.PENDING));
        Update update = new Update().set(SYNC_STATUS, AuditSyncStatus.SYNCED).set(SYNCED_AT, syncedAt);
        return mongoTemplate.updateMulti(query, update, AuditOutboxEntity.class).getModifiedCount();
    }

    @Override
    public long deleteSyncedBefore(Instant cutoff) {
        Query query = Query.query(where(SYNC_STATUS).is(AuditSyncStatus.SYNCED).and(SYNCED_AT).lt(cutoff));
        return mongoTemplate.remove(query, AuditOutboxEntity.class).getDeletedCount();
    }
}
