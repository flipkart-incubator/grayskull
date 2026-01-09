package com.flipkart.grayskull.spi.authn;

import lombok.EqualsAndHashCode;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public class GrayskullAuthentication extends AbstractAuthenticationToken {
    private static final List<GrantedAuthority> AUTHORITIES = AuthorityUtils.createAuthorityList("ROLE_USER");

    private final String principal;
    private final String actor;

    public GrayskullAuthentication(String principal, String actor) {
        super(AUTHORITIES);
        this.principal = principal;
        this.actor = actor;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getPrincipal() {
        return principal;
    }

    public String getActor() {
        return actor;
    }
}
