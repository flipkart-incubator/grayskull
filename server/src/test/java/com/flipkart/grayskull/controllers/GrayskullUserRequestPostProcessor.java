package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.spi.authn.GrayskullUser;
import com.flipkart.grayskull.spimpl.authn.SimpleGrayskullUser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

public class GrayskullUserRequestPostProcessor implements RequestPostProcessor {

    private final String userName;
    private String actorName;

    private GrayskullUserRequestPostProcessor(String userName) {
        this.userName = userName;
    }

    public static  GrayskullUserRequestPostProcessor user(String userName) {
        return new GrayskullUserRequestPostProcessor(userName);
    }

    public GrayskullUserRequestPostProcessor actor(String actorName) {
        this.actorName = actorName;
        return this;
    }

    @Override
    public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
        GrayskullUser user = new SimpleGrayskullUser(this.userName, actorName);
        RequestPostProcessor authenticationPostProcessor = authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        return authenticationPostProcessor.postProcessRequest(request);
    }
}
