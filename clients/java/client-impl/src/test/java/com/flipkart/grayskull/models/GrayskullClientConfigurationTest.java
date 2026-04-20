package com.flipkart.grayskull.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrayskullClientConfigurationTest {

    @Test
    void pollIntervalSeconds_defaultIs30() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        assertEquals(30, config.getPollIntervalSeconds());
    }

    @Test
    void setPollIntervalSeconds_valid() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setPollIntervalSeconds(60);
        assertEquals(60, config.getPollIntervalSeconds());
    }

    @Test
    void setPollIntervalSeconds_minimum() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setPollIntervalSeconds(10);
        assertEquals(10, config.getPollIntervalSeconds());
    }

    @Test
    void setPollIntervalSeconds_belowMinimum_throws() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.setPollIntervalSeconds(9));
    }
}
