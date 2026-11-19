package com.flipkart.grayskull.audit;

import com.flipkart.grayskull.spi.models.AuditCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditCursorCodecTest {

    @Test
    void roundTrip_keepsTimestampAndId() {
        AuditCursor cursor = new AuditCursor(Instant.parse("2026-10-15T07:03:32.553594Z"), "4f9c5b8e-1d2a-3b4c-8d9e-0f1a2b3c4d5e");

        String encoded = AuditCursorCodec.encode(cursor);

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(AuditCursorCodec.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void nullOrBlank_meansFirstPage() {
        assertThat(AuditCursorCodec.encode(null)).isNull();
        assertThat(AuditCursorCodec.decode(null)).isNull();
        assertThat(AuditCursorCodec.decode(" ")).isNull();
    }

    @Test
    void decode_rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> AuditCursorCodec.decode("not-a-cursor"));
        String withoutId = AuditCursorCodec.encode(new AuditCursor(Instant.parse("2026-10-15T07:03:32Z"), ""));
        assertThrows(IllegalArgumentException.class, () -> AuditCursorCodec.decode(withoutId));
    }
}
