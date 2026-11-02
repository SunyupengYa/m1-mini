package m1;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import m1.utils.DeepSeekPricingUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

/**
 * M1 最小版：一个文件说清「一次模型调用到底发生了什么」。
 *
 * <p>覆盖路线图 M1 的四项：D1 手写 POST、D2 SSE 半包解析、D4 记账、D7 多轮流式 CLI。
 * D3 结构化输出和 D5/D6 的实验在 {@link Exp}，假服务端在 {@link Mock}。
 *
 * <h2>刻意不做的事</h2>
 * 不建 DTO、不写 Builder、不分层、不用 record —— 全部用 {@code Map} 拼请求、
 * 用 {@link JSONObject} 取响应。代价是丢掉类型安全和编译期检查，换来的是
 * 你能一眼看到「发出去的报文长什么样、收回来的是什么」。
 *
 * <h2>怎么读这个文件</h2>
 * 从上往下：配置 → main → ask → sync / stream → post / readSse → 记账。
 * 真正需要动脑子的只有 {@link #readSse} 一个方法，其余都是搬运。
 *
 * <p>运行：
 * <pre>
 * mvn -q compile exec:java                        # 真端点，需 M1_API_KEY
 * mvn -q compile exec:java -Dexec.args="--mock"   # 假服务端，不需要 key
 * </pre>
 */
public class Chat {

    // ================================================================
    // 配置：只认环境变量。没有配置文件、没有默认值层级 —— M1 不需要那些
    // ================================================================

    static String BASE = env("M1_BASE_URL", "https://api.deepseek.com");
    static String KEY = env("M1_API_KEY", env("DEEPSEEK_API_KEY", ""));
    static String MODEL = env("M1_MODEL", "deepseek-v4-flash");
    static String THINKING_TYPE = env("M1_THINKING_TYPE", "disabled");

    /**
     * 单价：<b>元 / 百万 token</b>，取自 DeepSeek 官方<b>中文价目表</b>（2026-11-01 核对）。
     *
     * <p>deepseek-flash 官方人民币牌价：输入（未命中）谷值 ¥1 / 高峰 ¥2，
     * 输出谷值 ¥4 / 高峰 ¥8，缓存命中谷值 ¥0.02 / 高峰 ¥0.04（每百万 token）。
     * 默认值取<b>高峰价</b>；空闲时段官方按半价计，这里自动折半，见 {@link #peakNow()}。
     *
     * <p>注意：中文价目表的缓存命中价（¥0.04 峰）与美元牌价折算（$0.014×6.67≈¥0.093）
     * 不一致，人民币计费以中文页为准 —— 别用汇率折算覆盖官方人民币标价。
     *
     * <p><b>价格变动很频繁，跑之前去官网核对一遍</b>：
     * api-docs.deepseek.com/zh-cn/quick_start/pricing
     */
    static final BigDecimal PRICE_IN = dec("M1_PRICE_IN", "2.0");      // 输入，缓存未命中

    static final BigDecimal PRICE_HIT = dec("M1_PRICE_HIT", "0.04");   // 输入，缓存命中

    static final BigDecimal PRICE_OUT = dec("M1_PRICE_OUT", "8.0");    // 输出

    static final String CUR = "¥";

    /** 金额保留 10 位小数：单 token 最低成本约 1e-7 元，再多位没有意义。 */
    static final int MONEY_SCALE = 10;
    static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * 当前是否高峰时段（每次调用实时判定，不再类初始化时焊死）。
     *
     * <p>DeepSeek 峰谷定价：北京时间工作日 9:00-12:00、14:00-18:00 为高峰（全价），
     * 其余时段（含周末与法定节假日）半价。时段判定统一收敛在
     * {@link DeepSeekPricingUtils}，本方法只负责 M1_PEAK 强制开关：
     * {@code peak} / {@code off} / {@code auto}（默认，走工具类实时判断）。
     *
     * <p>为什么不再用 {@code static final} 缓存：CLI 跨时段只是几分钱偏差，
     * 但这个写法一旦搬进长驻进程（Agent 服务挂一整天），峰谷口径就会和服务端
     * 实际计费漂移 —— 计费判定必须跟着每次请求的实际时刻走。
     */
    static boolean peakNow() {
        String mode = env("M1_PEAK", "auto");
        if ("peak".equals(mode)) {
            return true;
        }
        if ("off".equals(mode)) {
            return false;
        }
        return DeepSeekPricingUtils.isPeakNow(null);
    }

    /** 置 1 会把每次 read 到的原始片段打出来 —— 半包实验的关键开关。 */
    static boolean DEBUG_SSE = "1".equals(env("M1_DEBUG_SSE", "0"));

    /** 请求超时（秒）。注意这是「整个响应」的超时，不是首字节超时。 */
    static int TIMEOUT_SEC = (int) dbl("M1_TIMEOUT", 120);

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    static final Path LOG = Path.of("logs", "calls.log");

    // ================================================================
    // 会话状态
    // ================================================================

    static final List<Map<String, Object>> HISTORY = new ArrayList<>();
    static String SYSTEM = "你是一个简洁的中文助手，回答控制在 200 字以内。";
    static boolean STREAM = true;
    static double TEMP = 0.7;

    // 累计账
    static int nCalls, nIn, nOut, nHit;

    /** 累计金额。用 BigDecimal 累加，避免几百次调用后误差肉眼可见。 */
    static BigDecimal nCost = BigDecimal.ZERO;

    static final List<Long> LATENCIES = new ArrayList<>();

    // ================================================================
    // 入口
    // ================================================================

    public static void main(String[] args) throws Exception {
        Mock mock = null;
        if (List.of(args).contains("--mock")) {
            String mockMode = env("M1_MOCK_MODE", "stream");
            mock = Mock.start(mockMode);
            BASE = mock.baseUrl();
            KEY = "test";
            // sync 模式下假服务端只回完整 JSON，客户端这边也切到非流式，不然解析必然空
            if ("sync".equals(mockMode)) {
                STREAM = false;
            }
            System.out.println("[mock] " + BASE + "  模式=" + mockMode);
        } else if (KEY.isBlank()) {
            System.out.println("没有 API key。二选一：\n"
                    + "  export M1_API_KEY=sk-xxxx\n"
                    + "  或加 --mock 用本地假服务端");
            return;
        }

        banner();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\n你> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                break;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            switch (line) {
                case "/exit" -> {
                    if (mock != null) {
                        mock.close();
                    }
                    return;
                }
                case "/clear" -> {
                    HISTORY.clear();
                    System.out.println("历史已清空");
                }
                case "/stats" -> stats();
                case "/stream" -> {
                    STREAM = true;
                    System.out.println("流式：开");
                }
                case "/sync" -> {
                    STREAM = false;
                    System.out.println("流式：关（非流式）");
                }
                default -> ask(line);
            }
        }
        if (mock != null) {
            mock.close();
        }
    }

    // ================================================================
    // 一轮问答
    // ================================================================

    /** 追加用户消息 → 发请求 → 把回复写回历史。失败时撤回刚加的用户消息。 */
    static void ask(String input) {
        HISTORY.add(Map.of("role", "user", "content", input));

        // system 每轮现拼，不塞进 HISTORY，这样改 SYSTEM 立刻生效
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.addAll(HISTORY);

        try {
            if (STREAM) {
                stream(messages);
            } else {
                sync(messages);
            }
        } catch (Exception e) {
            HISTORY.remove(HISTORY.size() - 1);
            System.out.println("\n[失败] " + e.getMessage());
        }
    }

    // ================================================================
    // D1：非流式。一次 POST，拿完整 JSON 回来
    // ================================================================

    static void sync(List<Map<String, Object>> messages) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp = post(buildBody(messages, false));
        long ms = System.currentTimeMillis() - t0;

        // 非 2xx 也要把 body 读出来：真正的原因（余额不足、参数错）全在里面
        if (resp.statusCode() / 100 != 2) {
            bill("sync", false, 0, 0, 0, -1, ms, "HTTP " + resp.statusCode() + " " + brief(resp.body()));
            throw new RuntimeException("HTTP " + resp.statusCode() + " " + brief(resp.body()));
        }

        JSONObject root = JSON.parseObject(resp.body());

        // fastjson 没有 Jackson 的 MissingNode 兜底：任何一层都可能是 null，
        // 所以必须逐层判空，不能一路 .getString() 链下去
        JSONObject choice = firstChoice(root);
        JSONObject msg = choice == null ? null : choice.getJSONObject("message");
        String content = msg == null ? "" : msg.getString("content");
        System.out.println("\n助手> " + content);
        HISTORY.add(Map.of("role", "assistant", "content", content));

        JSONObject u = root.getJSONObject("usage");
        bill("sync", true,
                u == null ? 0 : u.getIntValue("prompt_tokens"),
                u == null ? 0 : u.getIntValue("completion_tokens"),
                u == null ? 0 : u.getIntValue("prompt_cache_hit_tokens"),
                -1, ms, null);
    }

    // ================================================================
    // D2：流式。边收边打印，同时把片段拼成完整回复
    // ================================================================

    static void stream(List<Map<String, Object>> messages) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpResponse<InputStream> resp = postStream(buildBody(messages, true));

        if (resp.statusCode() / 100 != 2) {
            String err = new String(resp.body().readNBytes(8192), StandardCharsets.UTF_8);
            long ms = System.currentTimeMillis() - t0;
            bill("stream", false, 0, 0, 0, -1, ms, "HTTP " + resp.statusCode() + " " + brief(err));
            throw new RuntimeException("HTTP " + resp.statusCode() + " " + brief(err));
        }

        // 这些状态要在回调里跨多次调用存活。Java 的 lambda 只能读不能写外部局部变量，
        // 所以用单元素数组/可变容器当「盒子」—— 土，但这是 Java 的标准做法。
        StringBuilder text = new StringBuilder();
        int[] usage = new int[3];          // [0]=in [1]=out [2]=cacheHit
        long[] ttft = {-1};                // 首 token 耗时
        String[] finish = {null};

        System.out.print("\n助手> ");
        System.out.flush();

        readSse(resp.body(), data -> {
            JSONObject chunk;
            try {
                chunk = JSON.parseObject(data);
            } catch (Exception e) {
                // 单片坏了不要炸整条流：记一笔，跳过
                System.err.println("[跳过坏片] " + brief(data));
                return;
            }

            // 最后一片的 choices 是空数组，firstChoice 返回 null —— 这里就是最容易 NPE 的地方
            JSONObject choice = firstChoice(chunk);
            JSONObject d = choice == null ? null : choice.getJSONObject("delta");

            // delta.content 是增量片段，不是完整文本 —— 直接 append
            String delta = d == null ? null : d.getString("content");
            if (delta != null && !delta.isEmpty()) {
                if (ttft[0] < 0) {
                    ttft[0] = System.currentTimeMillis() - t0;
                }
                text.append(delta);
                System.out.print(delta);
                System.out.flush();     // 不 flush 的话终端会攒着，看起来像「没有流式」
            }

            String fr = choice == null ? null : choice.getString("finish_reason");
            if (fr != null && !fr.isEmpty()) {
                finish[0] = fr;
            }

            // usage 只出现在最后一片，那片没有 choice，只有 usage
            JSONObject u = chunk.getJSONObject("usage");
            if (u != null) {
                usage[0] = u.getIntValue("prompt_tokens");
                usage[1] = u.getIntValue("completion_tokens");
                usage[2] = u.getIntValue("prompt_cache_hit_tokens");
            }
        });

        System.out.println();
        if (text.length() > 0) {
            HISTORY.add(Map.of("role", "assistant", "content", text.toString()));
        }
        bill("stream", true, usage[0], usage[1], usage[2], ttft[0],
                System.currentTimeMillis() - t0, null);

        if ("length".equals(finish[0])) {
            System.out.println("[注意] 被 max_tokens 截断，不是正常结束");
        }
    }

    // ================================================================
    // D2 的核心：SSE 解析。全项目只有这一处需要动脑子
    // ================================================================

    /**
     * 从流里读 SSE，每解析出一个完整事件就回调一次 data 内容。
     *
     * <h2>为什么必须有缓冲区</h2>
     * 网络读到的片段和事件边界没有任何关系。一次 read 可能读到：
     * <pre>
     * 整个事件：  data: {"a":1}\n\n
     * 半个事件：  data: {"a":
     * 一个半：    data: {"a":1}\n\ndata: {"b":
     * </pre>
     * 所以没凑成完整事件的部分必须留在 {@code buf} 里等下次拼。
     * 任何「读一次解析一次」的写法都必崩 —— 这是 SSE 唯一的真难点。
     *
     * <h2>协议细节</h2>
     * <ul>
     *   <li>事件之间用<b>空行</b>分隔，不是单个换行。只按 {@code \n} 切会把 JSON 切碎。</li>
     *   <li>分隔符可能是 {@code \n\n}，也可能是 {@code \r\n\r\n}，两种都得认。</li>
     *   <li>{@code data:} 冒号后可能有一个空格，要吃掉。</li>
     *   <li>以 {@code :} 开头的行是心跳注释，丢掉。</li>
     *   <li>一个事件可以有多行 {@code data:}，按规范用 {@code \n} 拼接。</li>
     *   <li>{@code [DONE]} 是结束哨兵，<b>不是 JSON</b>，别塞给 Jackson。</li>
     * </ul>
     */
    static void readSse(InputStream in, java.util.function.Consumer<String> onData) throws IOException {
        // ============================================================
        // TODO ①【核心】手写 SSE 解析 —— M1 最值得花时间的一处
        //
        // 要说清的三件事（面试必问）：
        //   1. 缓冲区为什么必须存在（半包）
        //   2. 分隔符有两种形态
        //   3. [DONE] 之后的数据要丢掉
        //
        // 具体步骤：
        //   a. 用 BufferedReader + InputStreamReader(UTF_8) 包住 in
        //   b. char[] 缓冲大小取 (DEBUG_SSE ? 16 : 256) —— 开大了半包永远不出现
        //   c. 主循环 reader.read(chunk)，返回 -1 结束；每次读到就 append 到 StringBuilder buf
        //   d. 内层 while：调 boundary(buf) 找分隔符，找不到就退出内层（半包，等下批数据）
        //      找到了就：cut 出事件块 → delete 掉「块+分隔符」→ extractData 取 data
        //      → 空串跳过 → "[DONE]" 直接 return → 否则 onData.accept
        //   e. 循环结束后再 flush 一次残留（服务端可能不发结尾空行）
        // ============================================================
        throw new UnsupportedOperationException("TODO ①: Chat.readSse 未实现");
    }

    /** 找事件分隔符的位置和长度；找不到返回 null（说明还是半包，正常）。 */
    static int[] boundary(StringBuilder buf) {
        int lf = buf.indexOf("\n\n");
        int crlf = buf.indexOf("\r\n\r\n");
        if (lf < 0 && crlf < 0) {
            return null;
        }
        if (crlf < 0) {
            return new int[]{lf, 2};
        }
        if (lf < 0) {
            return new int[]{crlf, 4};
        }
        return lf < crlf ? new int[]{lf, 2} : new int[]{crlf, 4};
    }

    /** 从一个事件块里取出 data 内容（剥掉前缀、跳过注释行、多行拼接）。 */
    static String extractData(String block) {
        if (block.isBlank()){
            return "";
        }
        StringBuffer sb = new StringBuffer();
        for (String line : block.split("\r?\n")) {
            if (line.isBlank() || line.startsWith(":")){
                continue;
            }
            if (line.startsWith("data:")){
                String newLine = line.substring(5);
                if (newLine.startsWith(" ")){
                    newLine = newLine.substring(1);
                }
                if (!sb.isEmpty()){
                    sb.append("\n");
                }
                sb.append(newLine);
            }

        }
        return sb.toString();
        // TODO ①-b：按行处理一个事件块
        //   空行                → 跳过
        //   以 ":" 开头          → 心跳注释，跳过
        //   以 "data:" 开头      → 取冒号后的内容；规范允许一个前导空格，吃掉它
        //                         多行 data 要用 \n 拼接（少见，但规范如此）
        //   其余行               → 忽略
//        throw new UnsupportedOperationException("TODO ①-b: Chat.extractData 未实现");
    }

    // ================================================================
    // 发请求
    // ================================================================

    static String buildBody(List<Map<String, Object>> messages, boolean stream) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", messages);
        body.put("stream", stream);
        body.put("temperature", TEMP);
//        body.put("thinking", Map.of("type",THINKING_TYPE));
        if (stream) {
            // 少了这一行，最后一片不会带 usage，账单永远算不准
            body.put("stream_options", Map.of("include_usage", true));
        }
        return JSON.toJSONString(body);
    }

    /** 非流式：等整个响应结束，BodyHandlers.ofString 一次性返回。 */
    static HttpResponse<String> post(String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + KEY)
                .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                // 中文必须显式 UTF-8，默认编码会变问号
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 流式：ofInputStream 立刻返回，边到边读；ofString/ofLines 会等整个流结束。 */
    static HttpResponse<InputStream> postStream(String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + KEY)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
    }

    // ================================================================
    // D4：记账
    // ================================================================

    /**
     * 折算单次金额（元）。
     *
     * <p>为什么用 BigDecimal 而不是 double：不是因为现在有精度问题（单次误差在 1e-12 量级，
     * 对任何决策都没影响），而是两个更实在的理由 ——
     * 一是展示要稳定，double 格式化出来会出现 0.0000039999999 这种；
     * 二是这类代码迟早会被复制到真要对账的地方，那时误差就不能容忍了。
     *
     * <p><b>关键坑</b>：{@code divide} 必须显式给 scale 和 RoundingMode。不给的话，
     * 一旦除不尽（比如除以 3）就直接抛 ArithmeticException —— 这是 BigDecimal 最常见的线上事故。
     */
    static BigDecimal cost(int pin, int pout, int phit) {
        if (phit > pin){
            pin = phit;
        }
        boolean peak = peakNow();
        BigDecimal pHitPrice = PRICE_HIT.multiply(new BigDecimal(phit));
        BigDecimal pMissPrice = PRICE_IN.multiply(new BigDecimal(pin - phit));
        BigDecimal pOutPrice = PRICE_OUT.multiply(new BigDecimal(pout));
        BigDecimal totalPrice = pHitPrice.add(pMissPrice).add(pOutPrice);
        if (!peak){
            totalPrice = totalPrice.multiply(HALF);
        }
        return totalPrice.divide(MILLION,MONEY_SCALE, RoundingMode.HALF_UP);

        // TODO ②-a：三段相加，再按峰谷折价（逻辑约 6 行）
        //   缓存命中   hit         × PRICE_HIT
        //   未命中     (pin - hit) × PRICE_IN
        //   输出       pout        × PRICE_OUT
        //   相加后，非高峰时段再 multiply(HALF)
        //   最后 divide(MILLION, MONEY_SCALE, RoundingMode.HALF_UP)
        //
        // 坑一：divide 不写 scale + RoundingMode，遇到除不尽直接抛 ArithmeticException。
        //      这是 BigDecimal 最常见的线上事故 —— 本地跑一百次没事，上线就崩。
        // 坑二：厂商偶尔返回 hit > pin 的脏数据，先 Math.min 夹一下，否则算出负金额。
//        throw new UnsupportedOperationException("TODO ②-a: Chat.cost 未实现");
    }

    /**
     * 金额 → 字符串。必须用 {@code toPlainString()}：BigDecimal 的 toString
     * 在值很小时会输出「4E-7」这种科学计数法，日志里既难读，也会把解析日志的脚本坑死。
     */
    static String money(BigDecimal v) {
        return CUR + v.stripTrailingZeros().toPlainString();
    }

    /** 一次调用记一行：屏幕一行 + 追加到 logs/calls.log。 */
    static void bill(String scene, boolean ok, int pin, int pout, int phit,
                     long ttft, long total, String err) {
        BigDecimal cost = cost(pin, pout, phit);
        nCalls++;
        nIn += pin;
        nHit += phit;
        nOut += pout;
        nCost = nCost.add(cost);
        if (ok){
            LATENCIES.add(total);
        }
        Date now = new Date();
        String logStr = """
                时间(time)     %tF %tT
                场景(scene)    %s
                成败(success)  %s
                输入token(pin)  %d
                输出token(pout) %d
                缓存命中(phit)  %d
                首token耗时(ttft) %dms
                总耗时(total)  %dms
                金额（cost)   %s
                错误(err)      %s
                """.formatted(now,now,scene,ok ? "成功" : "失败", pin,pout, phit,ttft, total,cost.toPlainString(),err);
        writeLog(logStr);
        System.out.println(logStr);
        // TODO ②-b：四步，约 12 行
        //   1. BigDecimal c = cost(pin, pout, phit);
        //   2. 累加：nCalls++ / nIn += pin / nOut += pout / nHit += hit
        //      nCost = nCost.add(c)    ← 必须用 add，写成 += 会退化成 double
        //      ok 时 LATENCIES.add(total)，给 P95 用
        //   3. writeLog(拼好的一行)    ← 文件 IO 已经给你了，你只管拼串，别自己写 Files
        //      拼串内容：时间 场景 成败 in out hit cost ttft total [err]
        //      金额用 c.toPlainString()，toString 会变成 5.85E-5
        //   4. System.out.printf 打一行给人看，失败时带上原因
//        throw new UnsupportedOperationException("TODO ②-b: Chat.bill 未实现");
    }

    /** 把一行日志追加到 logs/calls.log。这是 IO 样板，不是学习点，直接给你。 */
    static void writeLog(String line) {
        try {
            Files.createDirectories(LOG.getParent());
            Files.writeString(LOG, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[记账失败] " + e.getMessage());
        }
    }

    static void stats() {
        if (nCalls == 0) {
            System.out.println("还没有调用。");
            return;
        }
        List<Long> sorted = new ArrayList<>(LATENCIES);
        sorted.sort(Long::compareTo);
        long p95 = sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(0.95 * sorted.size()) - 1));
        long avg = sorted.isEmpty() ? 0 : sorted.stream().mapToLong(Long::longValue).sum() / sorted.size();

        System.out.printf("""
                调用     %d 次
                输入     %d token（缓存命中 %d）
                输出     %d token
                累计     %s
                计价     %s  单价 in=%s/1M out=%s/1M
                平均延迟 %dms   P95 %dms
                日志     %s
                """, nCalls, nIn, nHit, nOut, money(nCost),
                peakNow() ? "高峰时段（全价）" : "空闲时段（半价）",
                priceText(PRICE_IN), priceText(PRICE_OUT),
                avg, p95, LOG.toAbsolutePath());
    }

    /** 当前生效单价（已按峰谷折算），只用于展示。 */
    static String priceText(BigDecimal peakPrice) {
        return money(peakNow() ? peakPrice : peakPrice.multiply(HALF));
    }

    // ================================================================
    // 小工具
    // ================================================================

    /**
     * 取 {@code choices[0]}。
     *
     * <p>fastjson 的 {@code getJSONObject(0)} 对空数组会抛越界，而
     * 「开了 include_usage 之后的最后一片，choices 是空数组」是<b>常态</b>。
     * 所有取 choice 的地方统一过这个方法，就不会漏判。
     */
    static JSONObject firstChoice(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONArray choices = root.getJSONArray("choices");
        return choices == null || choices.isEmpty() ? null : choices.getJSONObject(0);
    }

    /** 供 Exp 复用：非流式问一次，返回文本，不打印。 */
    static String quick(String prompt, double temperature) throws Exception {
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));
        double old = TEMP;
        TEMP = temperature;
        try {
            HttpResponse<String> resp = post(buildBody(messages, false));
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            JSONObject root = JSON.parseObject(resp.body());
            JSONObject u = root.getJSONObject("usage");
            bill("quick", true,
                    u == null ? 0 : u.getIntValue("prompt_tokens"),
                    u == null ? 0 : u.getIntValue("completion_tokens"),
                    u == null ? 0 : u.getIntValue("prompt_cache_hit_tokens"),
                    -1, 0, null);

            JSONObject choice = firstChoice(root);
            JSONObject msg = choice == null ? null : choice.getJSONObject("message");
            return msg == null ? "" : msg.getString("content");
        } finally {
            TEMP = old;
        }
    }

    static void banner() {
        System.out.println("M1 最小版 · 模型 " + MODEL);
        System.out.println("端点 " + BASE + "/chat/completions");
        System.out.println("单价 in=" + priceText(PRICE_IN) + "/1M  out=" + priceText(PRICE_OUT)
                + "/1M  cacheHit=" + priceText(PRICE_HIT) + "/1M  → "
                + (peakNow() ? "高峰时段，全价" : "空闲时段，半价"));
        System.out.println("命令 /sync  /stream  /clear  /stats  /exit     (M1_DEBUG_SSE=1 看原始分片)");
    }

    static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ');
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    static double dbl(String key, double def) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 读金额类配置。用 String 构造而不是 new BigDecimal(double) —— 后者会把 0.1 变成 0.1000000000000000055… */
    static BigDecimal dec(String key, String def) {
        String v = System.getenv(key);
        try {
            return new BigDecimal(v == null || v.isBlank() ? def : v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[配置] " + key + " 不是合法数字，回退到默认值 " + def);
            return new BigDecimal(def);
        }
    }
}
