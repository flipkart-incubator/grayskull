package com.flipkart.grayskull.spimpl.authz;

import com.flipkart.grayskull.configuration.AuthorizationProperties;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimpleAuthorizationProviderTest {

    private static AuthorizationProperties.Rule rule(String user, String project, String secret, Set<String> actions) {
        AuthorizationProperties.Rule rule = new AuthorizationProperties.Rule();
        rule.setUser(user);
        rule.setProject(project);
        rule.setSecret(secret);
        rule.setActions(actions);
        return rule;
    }

    @Test
    void isAuthorized_WhenAuthenticationIsNull_ReturnsFalse() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(List.of(rule("test-user", "test-project", null, Set.of("secrets.list"))));

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        AuthorizationContext ctx = AuthorizationContext.forProject(null, Project.builder().id("test-project").build());

        assertFalse(provider.isAuthorized(ctx, "secrets.list"));
    }

    @Test
    void isAuthorized_WhenRulesAreNull_ReturnsFalse() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(null);

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        Authentication auth = mock();
        when(auth.getName()).thenReturn("test-user");

        AuthorizationContext ctx = AuthorizationContext.forProject(auth, Project.builder().id("test-project").build());

        assertFalse(provider.isAuthorized(ctx, "secrets.list"));
    }

    @Test
    void isAuthorized_WhenProjectLevelRuleMatches_Allows() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(List.of(rule("test-user", "test-project", null, Set.of("secrets.list"))));

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        Authentication auth = mock();
        when(auth.getName()).thenReturn("test-user");

        AuthorizationContext ctx = AuthorizationContext.forProject(auth, Project.builder().id("test-project").build());

        assertTrue(provider.isAuthorized(ctx, "secrets.list"));
    }

    @Test
    void isAuthorized_WhenSecretRuleDoesNotMatch_Denies() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(List.of(rule("test-user", "test-project", "allowed-secret", Set.of("secret.read.value"))));

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        Authentication auth = mock();
        when(auth.getName()).thenReturn("test-user");

        Project project = Project.builder().id("test-project").build();
        Secret secret = Secret.builder().name("other-secret").projectId("test-project").build();
        AuthorizationContext ctx = AuthorizationContext.forSecret(auth, project, secret);

        assertFalse(provider.isAuthorized(ctx, "secret.read.value"));
    }

    @Test
    void isAuthorized_WhenWildcardsMatch_Allows() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(List.of(rule("*", "*", "*", Set.of("*"))));

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        Authentication auth = mock();
        when(auth.getName()).thenReturn("any-user");

        Project project = Project.builder().id("any-project").build();
        Secret secret = Secret.builder().name("any-secret").projectId("any-project").build();
        AuthorizationContext ctx = AuthorizationContext.forSecret(auth, project, secret);

        assertTrue(provider.isAuthorized(ctx, "any.action"));
    }

    @Test
    void isAuthorized_WhenActionNotInRule_Denies() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(List.of(rule("test-user", "test-project", null, Set.of("secrets.list"))));

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        Authentication auth = mock();
        when(auth.getName()).thenReturn("test-user");

        AuthorizationContext ctx = AuthorizationContext.forProject(auth, Project.builder().id("test-project").build());

        assertFalse(provider.isAuthorized(ctx, "secrets.create"));
    }

    @Test
    void isAuthorized_GlobalAuth_WhenUserMatchesAndActionWildcard_Allows() {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setRules(List.of(rule("test-user", "*", null, Set.of("*"))));

        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        GrayskullAuthentication auth = new GrayskullAuthentication("test-user", null);

        assertTrue(provider.isAuthorized(auth, "providers.list"));
    }


    @Test
    void isAuthorized_GlobalAuth_WhenRulesNull() {

        AuthorizationProperties props = new AuthorizationProperties();
        SimpleAuthorizationProvider provider = new SimpleAuthorizationProvider(props);

        GrayskullAuthentication auth = new GrayskullAuthentication("test-user", null);

        assertFalse(provider.isAuthorized(auth, "providers.list"));
    }
}
