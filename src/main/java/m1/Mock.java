package m1;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假模型服务端。零依赖（JDK 自带 {@code com.sun.net.httpserver}）。
 *
 * <h2>为什么必须有它</h2>
 * 真端点你没法让它「第 3 次返回 429」，也没法让它「把一行 JSON 从中间切成两半」。
 * 而这两件事恰好是 M1 最需要验证的。有了它，异常路径可以反复跑、结果可复现，
 * 不用烧钱，也不用等网络出故障。
 *
 * <h2>模式（用环境变量 M1_MOCK_MODE 选，默认 stream）</h2>
 * <table border="1">
 *   <tr><th>值</th><th>行为</th><th>用来验证什么</th></tr>
 *   <tr><td>stream</td><td>正常流式，但每个事件被切成 3 段 flush</td><td><b>半包解析</b></td></tr>
 *   <tr><td>nodone</td><td>流式但最后不发 [DONE]、也不发结尾空行</td><td>finish 兜底</td></tr>
 *   <tr><td>sync</td><td>普通非流式响应</td><td>D1</td></tr>
 *   <tr><td>429</td><td>带 Retry-After: 1 的限流</td><td>退避是否尊重服务端</td></tr>
 *   <tr><td>500</td><td>服务端故障</td><td>熔断</td></tr>
 *   <tr><td>slow</td><td>睡 3 秒再回</td><td>超时设置</td></tr>
 *   <tr><td>badjson</td><td>返回非法 JSON</td><td>解析兜底</td></tr>
 * </table>
 */
public class Mock implements AutoCloseable {

    private static final long SLOW_MS = 3_000;

    private final HttpServer server;
    private final String mode;
    private final AtomicInteger hits = new AtomicInteger();

    private Mock(HttpServer server, String mode) {
        this.server = server;
        this.mode = mode;
    }

    /** 起在随机端口上，避免和本机别的服务打架。 */
    public static Mock start(String mode) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Mock mock = new Mock(s, mode);
        s.createContext("/chat/completions", mock::handle);
        // slow 模式会阻塞线程，用虚拟线程池免得被占满
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        return mock;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 累计收到多少请求。验证重试次数就靠它，比看日志可靠。 */
    public int hits() {
        return hits.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        hits.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean wantStream = body.contains("\"stream\":true");

        try {
            switch (mode) {
                case "429" -> error(ex, 429, "rate limited", 1);
                case "500" -> error(ex, 500, "server exploded", null);
                case "badjson" -> json(ex, 200, "{ this is definitely not json ");
                case "slow" -> {
                    Thread.sleep(SLOW_MS);
                    json(ex, 200, syncBody());
                }
                case "nodone" -> sse(ex, false);
                case "sync" -> json(ex, 200, syncBody());
                default -> {
                    if (wantStream) {
                        sse(ex, true);
                    } else {
                        json(ex, 200, syncBody());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.close();
        }
    }

    /**
     * 流式响应。关键在 {@link #writeSplit}：它把每个事件拆成 3 段分别 flush，
     * 客户端一定会收到「半个 JSON」—— 不做缓冲的实现到这里必崩。
     */
    private void sse(HttpExchange ex, boolean sendDone) throws IOException, InterruptedException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);     // 0 = chunked，长度未知

        String[] pieces = {"你好", "，我是", "本地", "假服务端。"};
        try (OutputStream out = ex.getResponseBody()) {
            for (int i = 0; i < pieces.length; i++) {
                boolean last = i == pieces.length - 1;
                String event = "data: {\"id\":\"mock-1\",\"model\":\"mock\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"" + pieces[i] + "\"},"
                        + "\"finish_reason\":" + (last ? "\"stop\"" : "null") + "}]}";
                // nodone 模式下，最后一片故意不带结尾空行 —— 客户端只能靠 finish 兜底把它捞出来
                writeSplit(out, last && !sendDone ? event : event + "\n\n");
                Thread.sleep(20);
            }
            if (sendDone) {
                // usage 单独一片，choices 是空数组 —— 不判空就 NPE 的那种 chunk
                writeSplit(out, "data: {\"id\":\"mock-1\",\"model\":\"mock\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":9,"
                        + "\"prompt_cache_hit_tokens\":0}}\n\n");
                writeSplit(out, "data: [DONE]\n\n");
            }
            out.flush();
        }
    }

    /** 把一个事件切三段写出，每段后 flush —— 人为制造半包。 */
    private void writeSplit(OutputStream out, String event) throws IOException {
        int step = Math.max(1, event.length() / 3);
        for (int i = 0; i < event.length(); i += step) {
            int end = Math.min(event.length(), i + step);
            out.write(event.substring(i, end).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** 回固定长度的 JSON。Content-Length 必须给，否则客户端不知道何时读完。 */
    private void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 回错误。Retry-After 是测「退避是否听服务端的话」的关键，光有状态码测不出来。 */
    private void error(HttpExchange ex, int status, String message, Integer retryAfterSec) throws IOException {
        if (retryAfterSec != null) {
            ex.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSec));
        }
        json(ex, status, "{\"error\":{\"message\":\"" + message + "\"}}");
    }

    /** 字段要覆盖我们用到的全部，否则记账链路测不出东西。 */
    private String syncBody() {
        return """
                {"id":"mock-sync","object":"chat.completion","model":"mock",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"这是假服务端的同步响应。"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":9,"total_tokens":21,
                          "prompt_cache_hit_tokens":0}}""";
    }
}
