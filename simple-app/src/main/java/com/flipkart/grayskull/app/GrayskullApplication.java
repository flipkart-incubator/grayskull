package com.flipkart.grayskull.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.flipkart.grayskull.app", "com.flipkart.grayskull"})
public class GrayskullApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrayskullApplication.class, args);
    }

}
