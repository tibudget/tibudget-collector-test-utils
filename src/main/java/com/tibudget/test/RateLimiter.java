package com.tibudget.test;

public final class RateLimiter {

    private final long intervalNanos;
    private long nextAllowedTime = 0L;
    private final Object lock = new Object();

    /**
     * @param requestsPerSecond example: 0.5 = one request every 2 seconds
     */
    public RateLimiter(double requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be > 0");
        }
        this.intervalNanos = (long) (1_000_000_000d / requestsPerSecond);
    }

    public void acquire() {
        synchronized (lock) {
            long now = System.nanoTime();

            if (now < nextAllowedTime) {
                long sleepNanos = nextAllowedTime - now;
                try {
                    Thread.sleep(
                            sleepNanos / 1_000_000,
                            (int) (sleepNanos % 1_000_000)
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            nextAllowedTime = System.nanoTime() + intervalNanos;
        }
    }
}
