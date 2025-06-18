package com.flipkart.grayskull.app;

import org.springframework.boot.SpringApplication;

public class TestGrayskullApplication {

    public static void main(String[] args) {
        SpringApplication.from(GrayskullApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
