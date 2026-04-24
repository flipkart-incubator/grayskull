package com.flipkart.grayskull.constants;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class GrayskullHeadersTest {

    @Test
    void testPrivateConstructor_throwsAssertionError() throws Exception {
        Constructor<GrayskullHeaders> ctor = GrayskullHeaders.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, ctor::newInstance);
        assertInstanceOf(AssertionError.class, ex.getCause());
        assertEquals("Cannot instantiate constants class", ex.getCause().getMessage());
    }
}
