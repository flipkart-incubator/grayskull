package com.flipkart.grayskull.models.authz;

import lombok.experimental.UtilityClass;

/**
 * Defines all authorization actions available in the Grayskull system.
 * This is the central registry for permission-based access control operations.
 */
@UtilityClass
public class GrayskullActions {

    /**
     * Permission to list the secrets within a project.
     */
    public static final String LIST_SECRETS = "LIST_SECRETS";

    /**
     * Permission to create a new secret in a project.
     */
    public static final String CREATE_SECRET = "CREATE_SECRET";

    /**
     * Permission to read the metadata of a specific secret.
     */
    public static final String READ_SECRET_METADATA = "READ_SECRET_METADATA";

    /**
     * Permission to read the sensitive value of the latest version of a secret.
     */
    public static final String READ_SECRET_VALUE = "READ_SECRET_VALUE";

    /**
     * Permission to add a new version to an existing secret.
     */
    public static final String ADD_SECRET_VERSION = "ADD_SECRET_VERSION";

    /**
     * Permission to delete a secret and all its versions.
     */
    public static final String DELETE_SECRET = "DELETE_SECRET";

    /**
     * Permission to read the sensitive value of a specific version of a secret (Admin).
     */
    public static final String READ_SECRET_VERSION_VALUE = "READ_SECRET_VERSION_VALUE";

    /**
     * Permission to read the audit trail for secrets.
     */
    public static final String READ_AUDIT = "READ_AUDIT";

} 