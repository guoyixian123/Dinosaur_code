package dino.provider;

import dino.core.ChatEvent;
import dino.core.ErrorKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误映射文案依据：checklist §G（401 文案是精确断言）。
 */
class ApiErrorsTest {

    @Test
    void unauthorizedMapsToAuthWithExactText() {
        ChatEvent.Failure failure = ApiErrors.fromHttp(401, "{\"error\":{\"message\":\"invalid key\"}}");
        assertEquals(ErrorKind.AUTH, failure.kind());
        assertEquals("认证失败: 请检查 api_key", failure.message());
    }

    @Test
    void tooManyRequestsMapsToRateLimit() {
        ChatEvent.Failure failure = ApiErrors.fromHttp(429, "{}");
        assertEquals(ErrorKind.RATE_LIMIT, failure.kind());
    }

    @Test
    void contextOverflowDetectedFromMessage() {
        ChatEvent.Failure failure = ApiErrors.fromHttp(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens\"}}");
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, failure.kind());
        assertTrue(failure.message().contains("/new"), failure.message());
    }

    @Test
    void anthropicPromptTooLongDetected() {
        ChatEvent.Failure failure = ApiErrors.fromHttp(400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 999999 tokens > 200000 maximum\"}}");
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, failure.kind());
    }

    @Test
    void otherStatusFallsBackWithMessage() {
        ChatEvent.Failure failure = ApiErrors.fromHttp(500, "{\"error\":{\"message\":\"boom\"}}");
        assertEquals(ErrorKind.OTHER, failure.kind());
        assertTrue(failure.message().contains("boom"), failure.message());
    }
}
