package dino.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dino.core.ChatEvent;
import dino.core.ErrorKind;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 两个适配器的公共 HTTP 管道：发请求 → 非 2xx 映射错误事件 → 2xx 交给子类解析流。
 * 同步阻塞发送，运行在虚拟线程上（见 spec 设计骨架）。
 */
abstract class AbstractHttpProvider implements ChatProvider {

    protected static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    protected final String baseUrl;
    protected final String apiKey;
    private final String model;

    protected AbstractHttpProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public EventStream chat(ChatRequest request) {
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(request);
        } catch (JsonProcessingException e) {
            return SingleEventStream.of(new ChatEvent.Failure(ErrorKind.OTHER, "请求构造失败: " + brief(e)));
        }
        try {
            HttpResponse<InputStream> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                return SingleEventStream.of(ApiErrors.fromHttp(status, errorBody));
            }
            return streamFrom(response.body());
        } catch (IOException e) {
            return SingleEventStream.of(new ChatEvent.Failure(ErrorKind.NETWORK, "网络异常: " + brief(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SingleEventStream.of(new ChatEvent.Failure(ErrorKind.NETWORK, "网络异常: 请求被中断"));
        }
    }

    /** 拼装协议请求（URL、头、请求体）。 */
    protected abstract HttpRequest buildRequest(ChatRequest request) throws JsonProcessingException;

    /** 从 2xx 响应体构造事件流。 */
    protected abstract EventStream streamFrom(InputStream body);

    @Override
    public String model() {
        return model;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    protected URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
