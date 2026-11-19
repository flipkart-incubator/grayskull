package com.flipkart.grayskull.audit;

import com.flipkart.grayskull.spi.models.AuditCursor;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Encodes the paging cursor as an opaque, URL-safe string: {@code base64url(epochSecond.nanos|id)}.
 */
@UtilityClass
public class AuditCursorCodec {

    public static String encode(AuditCursor cursor) {
        if (cursor == null) {
            return null;
        }
        String raw = cursor.timestamp().getEpochSecond() + "." + cursor.timestamp().getNano() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException when the value isn't a cursor produced by {@link #encode}
     */
    public static AuditCursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            String[] time = raw.substring(0, separator).split("\\.");
            Instant timestamp = Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1]));
            String id = raw.substring(separator + 1);
            if (id.isEmpty()) {
                throw new IllegalArgumentException("empty id");
            }
            return new AuditCursor(timestamp, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid audit cursor", e);
        }
    }
}
