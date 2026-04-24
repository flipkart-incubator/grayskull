package com.flipkart.grayskull.models.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BatchSecretItem} (Lombok-generated equality helpers).
 */
@DisplayName("BatchSecretItem")
class BatchSecretItemTest {

    private static BatchSecretItem item(String projectId, String secretName, int dataVersion) {
        return BatchSecretItem.builder()
                .projectId(projectId)
                .secretName(secretName)
                .dataVersion(dataVersion)
                .publicPart("pub")
                .privatePart("priv")
                .build();
    }

    @Nested
    @DisplayName("canEqual (Lombok symmetric equality guard)")
    class CanEqualTests {

        @Test
        @DisplayName("Should return true when other is a BatchSecretItem")
        void shouldReturnTrue_whenOtherIsBatchSecretItem() {
            BatchSecretItem left = item("p", "s", 1);
            BatchSecretItem right = item("p", "s", 1);

            assertThat(left.canEqual(right)).isTrue();
            assertThat(right.canEqual(left)).isTrue();
        }

        @Test
        @DisplayName("Should return false when other is not a BatchSecretItem")
        void shouldReturnFalse_whenOtherIsNotBatchSecretItem() {
            BatchSecretItem batchSecretItem = item("p", "s", 1);

            assertThat(batchSecretItem.canEqual(new Object())).isFalse();
            assertThat(batchSecretItem.canEqual("not-a-batch-item")).isFalse();
        }
    }

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when subclass fields and SecretDataResponse fields match")
        void shouldBeEqual_whenAllRelevantFieldsMatch() {
            BatchSecretItem a = item("proj", "name", 3);
            BatchSecretItem b = item("proj", "name", 3);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Should not be equal when subclass fields differ")
        void shouldNotBeEqual_whenSubclassFieldsDiffer() {
            BatchSecretItem a = item("proj", "a", 1);
            BatchSecretItem b = item("proj", "b", 1);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
