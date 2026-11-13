package com.flipkart.grayskull.app;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// TODO re-enable once the db/audit Flyway migrations are pushed; the Derby audit logger reads audit_checkpoint on start-up
@Disabled("needs the db/audit migrations")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GrayskullApplicationTests {

    @Test
    void contextLoads() {
    }

}
