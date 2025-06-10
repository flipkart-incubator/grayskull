package com.flipkart.grayskull.spi.annotations;

import java.lang.annotation.*;

/**
 * Annotation to mark rest controller methods as read-only.
 * This can be used to indicate that the API does not modify any state
 * and is safe to call in a read-only context.
 *
 * Example usage:
 * <pre>
 * {@code
 * @RestController
 * public class MyController {
 *     @PostMapping("/data")
 *     @ReadOnly
 *     public String createData() {
 *         // method implementation
 *     }
 * }
 * }
 * </pre>
 *
 * Note: This annotation is not needed for GET methods, as they are inherently read-only.
 */
@Inherited
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnly {
}
