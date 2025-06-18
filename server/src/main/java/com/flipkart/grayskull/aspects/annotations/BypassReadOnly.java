package com.flipkart.grayskull.aspects.annotations;

import java.lang.annotation.*;

/**
 * Annotation that allows controller methods to execute write operations even when the system is in read-only mode.
 *<br/>
 * Example usage:
 * <pre>
 * {@code
 * @RestController
 * public class MyController {
 *     @PostMapping("/data")
 *     @BypassReadOnly
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
public @interface BypassReadOnly {
}
