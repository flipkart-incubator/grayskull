package com.flipkart.grayskull.spimpl.authn;

import com.flipkart.grayskull.spi.authn.GrayskullUser;

import java.util.Optional;

public record SimpleGrayskullUser(String name, String actorName) implements GrayskullUser {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Optional<String> getActorName() {
        return Optional.ofNullable(actorName);
    }
}
