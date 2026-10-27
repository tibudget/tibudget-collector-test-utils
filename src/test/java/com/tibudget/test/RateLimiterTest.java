package com.tibudget.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimiterTest {
    @Test
    void rejectsInvalidRates() {
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new RateLimiter(rate));
        }
    }
}
