package com.flipkart.grayskull.spi;

import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.models.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GrayskullAuthorizationProvider Unit Tests")
class GrayskullAuthorizationProviderTest {

    static class TestProvider implements GrayskullAuthorizationProvider {
        @Override
        public boolean isAuthorized(AuthorizationContext authorizationContext, String action) {
            return "allowed".equals(action);
        }

        @Override
        public boolean isAuthorized(GrayskullAuthentication authentication, String action) {
            return false;
        }
    }

    @Test
    @DisplayName("bulkAuthorize should return true if all contexts are authorized")
    void bulkAuthorize_AllAuthorized_ReturnsTrue() {
        TestProvider provider = new TestProvider();
        AuthorizationContext context1 = AuthorizationContext.forProject(null, Mockito.mock(Project.class));
        AuthorizationContext context2 = AuthorizationContext.forProject(null, Mockito.mock(Project.class));
        List<AuthorizationContext> contexts = Arrays.asList(context1, context2);

        assertTrue(provider.bulkAuthorize(contexts, "allowed"));
    }

    @Test
    @DisplayName("bulkAuthorize should return false if any context is not authorized")
    void bulkAuthorize_AnyNotAuthorized_ReturnsFalse() {
        TestProvider provider = new TestProvider();
        AuthorizationContext context1 = AuthorizationContext.forProject(null, Mockito.mock(Project.class));
        AuthorizationContext context2 = AuthorizationContext.forProject(null, Mockito.mock(Project.class));
        List<AuthorizationContext> contexts = Arrays.asList(context1, context2);

        assertFalse(provider.bulkAuthorize(contexts, "denied"));
    }
}
