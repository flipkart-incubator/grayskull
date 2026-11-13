package com.flipkart.grayskull.spi;

import com.flipkart.grayskull.spi.models.AuditEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Destination of the asynchronous (Derby-buffered) audit flush.
 * It stores flushed audit entries together with a per-node checkpoint, so a flush can resume
 * where it stopped and retried batches never create duplicates.
 */
public interface AuditFlushStore {

    /**
     * @param nodeName node (pod) whose local buffer is flushed
     * @return id of the last buffered row already stored for this node, or 0 if none
     */
    long lastFlushedId(String nodeName);

    /**
     * Stores the entries and moves the node's checkpoint to {@code maxId} atomically.
     *
     * @param nodeName node (pod) whose local buffer is flushed
     * @param entries  entries read from the buffer, in buffer order
     * @param maxId    highest buffer row id contained in {@code entries}
     */
    void commitBatch(String nodeName, List<AuditEntry> entries, long maxId);

    /**
     * Records that the node's buffer was fully drained by a flush that started at {@code flushStartedAt}:
     * every entry of this node created before that time is stored.
     *
     * @param nodeName       node (pod) whose local buffer is flushed
     * @param flushStartedAt start time of the flush run that emptied the buffer
     */
    default void markDrained(String nodeName, Instant flushStartedAt) {
    }

    /**
     * @param nodeName node (pod) whose local buffer is flushed
     * @return when the node's checkpoint last changed, if a checkpoint exists
     */
    Optional<Instant> lastCheckpointUpdate(String nodeName);

    /**
     * Deletes the node's checkpoint.
     *
     * @param nodeName node (pod) whose checkpoint is deleted
     */
    void deleteCheckpoint(String nodeName);
}
