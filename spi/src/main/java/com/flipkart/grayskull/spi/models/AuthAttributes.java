package com.flipkart.grayskull.spi.models;

public sealed interface AuthAttributes permits NoneAuthAttributes, BasicAuthAttributes, OAuth2AuthAttributes {
}
