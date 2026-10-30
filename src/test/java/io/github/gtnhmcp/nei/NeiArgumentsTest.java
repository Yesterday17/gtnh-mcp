package io.github.gtnhmcp.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;

import org.junit.jupiter.api.Test;

public class NeiArgumentsTest {

    @Test
    public void acceptsIntegerJsonNumbersAndDefaults() {
        assertEquals(20, NeiArguments.integer(Collections.emptyMap(), "limit", 20, 1, 50));
        assertEquals(30, NeiArguments.integer(Collections.singletonMap("limit", 30.0), "limit", 20, 1, 50));
    }

    @Test
    public void refusesFractionalOrUnboundedRequests() {
        for (Object value : new Object[] { 0, 51, -1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, "20" }) {
            try {
                NeiArguments.integer(Collections.singletonMap("limit", value), "limit", 20, 1, 50);
                fail("Invalid limit accepted: " + value);
            } catch (IllegalArgumentException expected) {
                // Rejected before invoking game code.
            }
        }
    }

    @Test
    public void boundsSnbtRecursionWithoutCountingQuotedBraces() {
        NeiArguments.validateNbtDepth("{name:\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"}");
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 33; i++) nested.append("{value:");
        try {
            NeiArguments.validateNbtDepth(nested.toString());
            fail("Deep input must be rejected before calling the recursive game parser");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                true,
                expected.getMessage()
                    .contains("32 levels"));
        }
    }
}
