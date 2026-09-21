package io.orchesty.sdk.camel;

import java.util.List;

/**
 * Declarative outcome policy for a Camel connector. Orchesty, not Camel, owns retries: this
 * policy only classifies an outcome as success, repeat or stop-and-fail; the Bridge performs the
 * actual re-queue and hop counting.
 *
 * <p>The defaults match the SDK's {@link io.orchesty.sdk.transport.ResultRanges#DEFAULT}:
 * success {@code <300}; repeat {@code 408}, {@code 429} and {@code >=500}; stop-and-fail for the
 * remaining {@code 300}–{@code 500} statuses. Ranges are checked in the order success,
 * stop-and-fail, repeat (see {@link CamelErrorMapper#classifyStatus}). Camel's own error handler
 * and redelivery must stay disabled so this policy stays authoritative.
 */
public class RetryPolicy {

    private final List<Object> successRanges;
    private final List<Object> repeatRanges;
    private final List<Object> stopAndFailRanges;
    private final int repeatIntervalSeconds;
    private final int repeatMaxHops;
    private final boolean repeatOnException;
    private final boolean repeatFirst;

    private RetryPolicy(Builder b) {
        this.successRanges = b.successRanges;
        this.repeatRanges = b.repeatRanges;
        this.stopAndFailRanges = b.stopAndFailRanges;
        this.repeatIntervalSeconds = b.repeatIntervalSeconds;
        this.repeatMaxHops = b.repeatMaxHops;
        this.repeatOnException = b.repeatOnException;
        this.repeatFirst = b.repeatSet && !b.stopAndFailSet;
    }

    public static RetryPolicy httpDefault() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Object> getSuccessRanges() {
        return successRanges;
    }

    public List<Object> getRepeatRanges() {
        return repeatRanges;
    }

    public List<Object> getStopAndFailRanges() {
        return stopAndFailRanges;
    }

    public int getRepeatIntervalSeconds() {
        return repeatIntervalSeconds;
    }

    public int getRepeatMaxHops() {
        return repeatMaxHops;
    }

    public boolean isRepeatOnException() {
        return repeatOnException;
    }

    /**
     * Tells whether repeat ranges are checked before stop-and-fail ranges: true when the repeat
     * ranges were configured but the stop-and-fail ranges were left at their defaults, so a
     * configured {@code repeat: [404]} is not overridden by the default {@code 300-408}.
     *
     * @return {@code true} to check repeat ranges first
     */
    public boolean isRepeatFirst() {
        return repeatFirst;
    }

    public static final class Builder {
        private List<Object> successRanges = List.of("<300");
        private List<Object> repeatRanges = List.of(408, 429, ">=500");
        private List<Object> stopAndFailRanges = List.of("300-408", "409-429", "430-500");
        private int repeatIntervalSeconds = 60;
        private int repeatMaxHops = 10;
        private boolean repeatOnException = true;
        private boolean repeatSet;
        private boolean stopAndFailSet;

        public Builder successRanges(List<Object> v) {
            this.successRanges = v;
            return this;
        }

        public Builder repeatRanges(List<Object> v) {
            this.repeatRanges = v;
            this.repeatSet = true;
            return this;
        }

        public Builder stopAndFailRanges(List<Object> v) {
            this.stopAndFailRanges = v;
            this.stopAndFailSet = true;
            return this;
        }

        public Builder repeatIntervalSeconds(int v) {
            this.repeatIntervalSeconds = v;
            return this;
        }

        public Builder repeatMaxHops(int v) {
            this.repeatMaxHops = v;
            return this;
        }

        public Builder repeatOnException(boolean v) {
            this.repeatOnException = v;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return the retry policy
         * @throws IllegalArgumentException if the repeat interval or the maximum hops is below 1
         */
        public RetryPolicy build() {
            if (repeatIntervalSeconds < 1) {
                throw new IllegalArgumentException(
                        "Retry intervalSeconds must be at least 1, got " + repeatIntervalSeconds + ".");
            }
            if (repeatMaxHops < 1) {
                throw new IllegalArgumentException("Retry maxHops must be at least 1, got " + repeatMaxHops + ".");
            }
            return new RetryPolicy(this);
        }
    }
}
