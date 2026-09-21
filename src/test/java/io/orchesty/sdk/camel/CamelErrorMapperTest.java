package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orchesty.sdk.exception.OnRepeatException;
import io.orchesty.sdk.process.Headers;
import io.orchesty.sdk.process.ProcessDto;
import io.orchesty.sdk.process.ResultCode;
import java.util.List;
import org.apache.camel.ResolveEndpointFailedException;
import org.apache.camel.RuntimeCamelException;
import org.junit.jupiter.api.Test;

class CamelErrorMapperTest {

    private final RetryPolicy policy = RetryPolicy.httpDefault();

    @Test
    void successStatusesAreNotFlagged() {
        assertFalse(CamelErrorMapper.classifyStatus(new ProcessDto(), 200, policy));
        assertFalse(CamelErrorMapper.classifyStatus(new ProcessDto(), 201, policy));
        assertFalse(CamelErrorMapper.classifyStatus(new ProcessDto(), 299, policy));
    }

    @Test
    void retryableStatusesRequestRepeat() {
        for (int status : new int[] {408, 429, 500, 502, 503}) {
            OnRepeatException repeat = assertThrows(
                    OnRepeatException.class,
                    () -> CamelErrorMapper.classifyStatus(new ProcessDto(), status, policy),
                    "status " + status);
            assertEquals(60, repeat.getInterval());
            assertEquals(10, repeat.getMaxHops());
        }
    }

    @Test
    void clientErrorsStopAndFail() {
        for (int status : new int[] {400, 401, 404, 409, 422}) {
            ProcessDto dto = new ProcessDto();
            assertTrue(CamelErrorMapper.classifyStatus(dto, status, policy), "status " + status);
            assertEquals(
                    Integer.toString(ResultCode.STOP_AND_FAILED.code()),
                    dto.getHeader(Headers.RESULT_CODE),
                    "status " + status);
        }
    }

    @Test
    void stopAndFailRangesAreCheckedBeforeRepeat() {
        RetryPolicy custom = RetryPolicy.builder()
                .stopAndFailRanges(List.of(404))
                .repeatRanges(List.of(">=400"))
                .build();

        ProcessDto notFound = new ProcessDto();
        assertTrue(CamelErrorMapper.classifyStatus(notFound, 404, custom));
        assertEquals(Integer.toString(ResultCode.STOP_AND_FAILED.code()), notFound.getHeader(Headers.RESULT_CODE));

        assertThrows(OnRepeatException.class, () -> CamelErrorMapper.classifyStatus(new ProcessDto(), 418, custom));
    }

    @Test
    void configuredRepeatWinsOverDefaultStopRanges() {
        RetryPolicy repeat404 = RetryPolicy.builder().repeatRanges(List.of(404)).build();

        assertThrows(OnRepeatException.class, () -> CamelErrorMapper.classifyStatus(new ProcessDto(), 404, repeat404));

        ProcessDto badRequest = new ProcessDto();
        assertTrue(CamelErrorMapper.classifyStatus(badRequest, 400, repeat404));
        assertEquals(
                Integer.toString(ResultCode.STOP_AND_FAILED.code()),
                badRequest.getHeader(Headers.RESULT_CODE),
                "the default stop ranges still apply to other statuses");
    }

    @Test
    void unclassifiedStatusesFollowTheSdk() {
        RetryPolicy narrow = RetryPolicy.builder()
                .successRanges(List.of(200))
                .repeatRanges(List.of(503))
                .stopAndFailRanges(List.of(400))
                .build();

        assertFalse(CamelErrorMapper.classifyStatus(new ProcessDto(), 204, narrow), "below 300 is a success");
        assertThrows(OnRepeatException.class, () -> CamelErrorMapper.classifyStatus(new ProcessDto(), 418, narrow));
    }

    @Test
    void invalidInputAlwaysStops() {
        for (Exception e : new Exception[] {
            new IllegalArgumentException("bad option"),
            new ResolveEndpointFailedException("x:y", "unknown parameter"),
            new RuntimeCamelException(new IllegalArgumentException("wrapped"))
        }) {
            ProcessDto dto = new ProcessDto();
            CamelErrorMapper.classifyException(dto, e, policy);
            assertEquals(
                    Integer.toString(ResultCode.STOP_AND_FAILED.code()),
                    dto.getHeader(Headers.RESULT_CODE),
                    e.toString());
        }
    }

    @Test
    void messagesAreSanitizedAndTruncated() {
        String described = CamelErrorMapper.describe(
                new RuntimeException("Failed sftp://host?password=hunter2&username=u " + "x".repeat(1000)));

        assertFalse(described.contains("hunter2"), described);
        assertTrue(described.length() <= CamelErrorMapper.MAX_MESSAGE_LENGTH + 1, described);
    }

    @Test
    void retryPolicyRejectsNonPositiveIntervalAndHops() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RetryPolicy.builder().repeatIntervalSeconds(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> RetryPolicy.builder().repeatMaxHops(0).build());
    }

    @Test
    void exceptionsRepeatByDefaultButCanStop() {
        RuntimeException timeout = new RuntimeException("timeout");
        OnRepeatException repeat = assertThrows(
                OnRepeatException.class, () -> CamelErrorMapper.classifyException(new ProcessDto(), timeout, policy));
        assertEquals(timeout, repeat.getCause());

        RetryPolicy noRepeat = RetryPolicy.builder().repeatOnException(false).build();
        ProcessDto stopDto = new ProcessDto();
        CamelErrorMapper.classifyException(stopDto, new RuntimeException("bad"), noRepeat);
        assertEquals(Integer.toString(ResultCode.STOP_AND_FAILED.code()), stopDto.getHeader(Headers.RESULT_CODE));
    }
}
