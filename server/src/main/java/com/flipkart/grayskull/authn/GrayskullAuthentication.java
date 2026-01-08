package com.flipkart.grayskull.authn;

import com.flipkart.grayskull.spi.authn.GrayskullUser;
import lombok.EqualsAndHashCode;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public class GrayskullAuthentication extends AbstractAuthenticationToken {
    private static final List<GrantedAuthority> AUTHORITIES = AuthorityUtils.createAuthorityList("ROLE_USER");

    private final GrayskullUser principal;

    public GrayskullAuthentication(GrayskullUser principal) {
        super(AUTHORITIES);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public GrayskullUser getPrincipal() {
        return principal;
    }
}
