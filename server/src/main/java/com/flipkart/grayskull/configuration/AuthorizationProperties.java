package com.flipkart.grayskull.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Configuration properties for defining authorization rules in Grayskull.
 * This class is bound to the {@code grayskull.authorization} prefix in the application's configuration file.
 * It allows for specifying a list of rules, each granting a set of actions to a user for a specific project.
 */
@Component
@ConfigurationProperties(prefix = "grayskull.authorization")
@Data
public class AuthorizationProperties {

    /**
     * A list of authorization rules.
     */
    private List<Rule> rules;

    /**
     * Represents a single authorization rule, linking a user to a project and a set of permissible actions.
     */
    @Data
    public static class Rule {
        /**
         * The username to which the rule applies. Can be a specific username or "*" to match any user.
         */
        private String user;
        /**
         * The project ID to which the rule applies. Can be a specific project ID or "*" to match any project.
         */
        private String project;
        /**
         * A set of actions that the user is permitted to perform on the project.
         * The actions are defined in {@link com.flipkart.grayskull.models.authz.GrayskullActions}.
         * Can contain specific actions or "*" to grant all actions.
         */
        private Set<String> actions;
    }
} 