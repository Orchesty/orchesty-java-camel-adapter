package io.orchesty.sdk.camel;

import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.process.ProcessDto;
import io.orchesty.sdk.process.ResultCode;
import io.orchesty.sdk.transport.StatusRanges;
import org.apache.camel.NoSuchEndpointException;
import org.apache.camel.ResolveEndpointFailedException;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.util.URISupport;

/**
 * Translates a Camel producer outcome into an Orchesty result, driven by a {@link RetryPolicy}.
 * This is where Camel exceptions and status codes become Orchesty result codes; Camel never
 * retries on its own. A stop-and-fail is set on the {@link ProcessDto}; a repeat is thrown as
 * {@link OnRepeatException}, so the worker can apply the node's repeater settings.
 */
public final class CamelErrorMapper {

    /** Longest exception message copied into a result message. */
    static final int MAX_MESSAGE_LENGTH = 500;

    private CamelErrorMapper() {}

    /**
     * Classifies an HTTP-style status code the same way as the SDK's {@code HttpSender}: success,
     * stop-and-fail and repeat ranges in this order (repeat before stop-and-fail when only the
     * repeat ranges were configured, see {@link RetryPolicy#isRepeatFirst()}); a status in none of
     * them is a success below 300 and a repeat otherwise.
     *
     * <p>A repeat is thrown as {@link OnRepeatException}, as the SDK does, so the worker applies the
     * node's repeater settings when they are enabled and the policy's interval and hops otherwise.
     *
     * @param dto message to set the result on
     * @param status status code of the response
     * @param policy retry policy
     * @return {@code true} if a stop-and-fail result was set; {@code false} if the status is a
     *         success and the caller should map the body
     * @throws OnRepeatException for a status that should be repeated
     */
    public static boolean classifyStatus(ProcessDto dto, int status, RetryPolicy policy) {
        if (StatusRanges.inRange(status, policy.getSuccessRanges())) {
            return false;
        }
        boolean repeat = StatusRanges.inRange(status, policy.getRepeatRanges());
        boolean stop = StatusRanges.inRange(status, policy.getStopAndFailRanges());
        if (stop && !(repeat && policy.isRepeatFirst())) {
            dto.setStopProcess(ResultCode.STOP_AND_FAILED, "Non-retryable status " + status + " from Camel connector.");
            return true;
        }
        if (repeat) {
            throw repeat(policy, "Retryable status " + status + " from Camel connector.", null);
        }
        if (status < 300) {
            return false;
        }
        throw repeat(policy, "Unclassified status " + status + " from Camel connector.", null);
    }

    /**
     * Classifies a thrown exception, for example a timeout, refused connection or protocol error.
     * Invalid input ({@link IllegalArgumentException}, also as the cause of a
     * {@link RuntimeCamelException}), an endpoint Camel cannot resolve
     * ({@link ResolveEndpointFailedException}) and a component missing from the worker
     * ({@link NoSuchEndpointException}) never succeed on a retry, so they always stop-and-fail;
     * anything else follows {@link RetryPolicy#isRepeatOnException()}. A repeat is thrown as
     * {@link OnRepeatException} (see {@link #classifyStatus}).
     *
     * @param dto message to set the result on
     * @param e the exception
     * @param policy retry policy
     * @throws OnRepeatException when the exception should be repeated
     */
    public static void classifyException(ProcessDto dto, Throwable e, RetryPolicy policy) {
        String message = describe(e);
        if (isInvalidInput(e)) {
            dto.setStopProcess(ResultCode.STOP_AND_FAILED, "Camel connector rejected the request: " + message);
        } else if (policy.isRepeatOnException()) {
            throw repeat(policy, "Camel connector error (will retry): " + message, e);
        } else {
            dto.setStopProcess(ResultCode.STOP_AND_FAILED, "Camel connector error: " + message);
        }
    }

    private static OnRepeatException repeat(RetryPolicy policy, String message, Throwable cause) {
        return new OnRepeatException(policy.getRepeatIntervalSeconds(), policy.getRepeatMaxHops(), message, cause);
    }

    private static boolean isInvalidInput(Throwable e) {
        if (e instanceof IllegalArgumentException
                || e instanceof ResolveEndpointFailedException
                || e instanceof NoSuchEndpointException) {
            return true;
        }
        return e instanceof RuntimeCamelException && e.getCause() instanceof IllegalArgumentException;
    }

    /**
     * Returns the exception message with credentials masked by Camel's URI sanitizer (Camel puts
     * endpoint URIs into many messages) and cut to {@value #MAX_MESSAGE_LENGTH} characters.
     */
    static String describe(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String sanitized = URISupport.sanitizeUri(message);
        if (sanitized == null) {
            sanitized = message;
        }
        return sanitized.length() > MAX_MESSAGE_LENGTH ? sanitized.substring(0, MAX_MESSAGE_LENGTH) + "…" : sanitized;
    }
}
