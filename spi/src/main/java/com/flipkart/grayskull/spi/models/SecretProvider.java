package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SecretProvider {
    private String name;
    private AuthMechanism authMechanism;
    private Object authAttributes;
    private String principal;
    private Instant creationTime;
    private Instant updatedTime;
}
