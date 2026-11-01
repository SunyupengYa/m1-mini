package m1;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M1 的三个实验：差异率（D5）、结构化输出（D3）、重试与熔断（D6）。
 *
 * <p>和 {@link Chat} 分开，是因为 Chat 要保持在「一次调用」的尺度上，
 * 这些实验都属于「很多次调用之后要观察什么」，混在一起会看不清主线。
 *
 * <p>运行（参数选一个）：
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="temp"      # 需要 key
 * mvn -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="json"      # 需要 key
 * mvn -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="retry"     # 用假服务端，不需要 key
 * mvn -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="breaker"   # 用假服务端，不需要 key
 * </pre>
 */
public class Exp {

    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "temp";
        switch (what) {
            case "temp" -> tempDiff();
            case "json" -> jsonDemo();
            case "retry" -> retryDemo();
            case "breaker" -> breakerDemo();
            default -> System.out.println("用法: Exp [temp|json|retry|breaker]");
        }
    }

    // ================================================================
    // D5：同一个 prompt，不同 temperature 各跑 N 次，看输出差多少
    // ================================================================

    /**
     * 这个实验要打掉一个直觉：「同样的输入 → 同样的输出」。
     *
     * <p>实测下来 temperature=0 也不是 100% 一致（浮点累加顺序、批处理、后端版本
     * 都会引入微小差异）。第一次亲眼看到这个数字，你对「概率系统」的理解才算开始。
     *
     * <p>三个指标一起看：完全相同率太脆弱（差一个标点就算不一致），
     * 平均相似度能看出「意思没变但措辞变了」，长度标准差能看出发散程度。
     */
    static void tempDiff() throws Exception {
        requireKey();

        String prompt = "用一句话解释什么是幂等。";
        double[] temps = {0.0, 0.7, 1.2};
        int rounds = 10;

        System.out.println("prompt: " + prompt + "   每个温度跑 " + rounds + " 次\n");
        System.out.printf("%-8s %-6s %-12s %-12s%n", "温度", "样本", "完全相同率", "平均相似度");

        for (double t : temps) {
            List<String> outs = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                try {
                    String s = Chat.quick(prompt, t);
                    if (s != null && !s.isBlank()) {
                        outs.add(s);
                    }
                } catch (Exception e) {
                    // 单次失败不能中断整个实验，否则前面跑的数据全白花
                    System.out.println("  第 " + (i + 1) + " 次失败：" + e.getMessage());
                }
            }
            System.out.printf("%-8.1f %-6d %-12s %-12.3f%n",
                    t, outs.size(),
                    String.format("%.0f%%", matchRate(outs) * 100),
                    avgSim(outs));
        }
        System.out.println("\n把「temperature=0 的完全相同率」记进 numbers.md。");
    }

    /** 两两比较，完全一样的对数 / 总对数。 */
    static double matchRate(List<String> outs) {
        int total = 0, same = 0;
        for (int i = 0; i < outs.size(); i++) {
            for (int j = i + 1; j < outs.size(); j++) {
                total++;
                if (outs.get(i).equals(outs.get(j))) {
                    same++;
                }
            }
        }
        return total == 0 ? 1.0 : (double) same / total;
    }

    /** 字符二元组 Jaccard。比编辑距离好解释：天然落在 0~1，跨样本可比。 */
    static double avgSim(List<String> outs) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < outs.size(); i++) {
            for (int j = i + 1; j < outs.size(); j++) {
                sum += similarity(outs.get(i), outs.get(j));
                n++;
            }
        }
        return n == 0 ? 1.0 : sum / n;
    }

    static double similarity(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 1.0 : (double) inter.size() / union.size();
    }

    static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        String t = s == null ? "" : s.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }

    // ================================================================
    // D3：结构化输出 —— Schema 约束 + 解析失败兜底
    // ================================================================

    /**
     * 三层防线：提示词写清 Schema → response_format=json_object → 解析失败兜底。
     *
     * <p>两个必须知道的坑：
     * <ul>
     *   <li>开 {@code json_object} 时，提示词里<b>必须</b>出现 "json" 字样并给出示例，
     *       否则服务端直接 400。</li>
     *   <li>{@code finish_reason == "length"} 说明 JSON 被截断。这时重试毫无意义
     *       —— 同样的 max_tokens 会再截一次。要先调大上限或拆分抽取。</li>
     * </ul>
     */
    static void jsonDemo() throws Exception {
        requireKey();

        String prompt = "从这句话里抽取信息：张三上周买了 3 张月卡，一共花了 90 元。";
        String schema = """
                {"type":"object","properties":{
                   "name":{"type":"string","description":"客户姓名"},
                   "count":{"type":"integer","description":"购买张数"},
                   "amount":{"type":"number","description":"总金额，单位元"}
                 },"required":["name","count","amount"]}""";

        String system = """
                你是一个数据抽取器，只输出 json 对象，不要 Markdown 围栏，不要任何解释。
                严格按下面的 Schema 输出：
                %s
                无法确定的字段填 null，不要编造。数字用 JSON number，不带引号、不带单位。
                """.formatted(schema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", Chat.MODEL);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt)));
        // 抽取任务要可复现，temperature 固定 0
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));

        HttpResponse<String> resp = Chat.post(JSON.toJSONString(body));
        if (resp.statusCode() / 100 != 2) {
            System.out.println("请求失败 HTTP " + resp.statusCode() + " " + Chat.brief(resp.body()));
            return;
        }

        JSONObject root = JSON.parseObject(resp.body());
        JSONObject choice = Chat.firstChoice(root);
        JSONObject msg = choice == null ? null : choice.getJSONObject("message");
        String raw = msg == null ? "" : msg.getString("content");
        String finish = choice == null ? null : choice.getString("finish_reason");
        if (finish == null) {
            finish = "";
        }

        System.out.println("原始输出：\n" + raw + "\n");
        System.out.println("finish_reason = " + finish);

        // 先判截断，再判格式 —— 顺序搞反了会白重试
        if ("length".equals(finish)) {
            System.out.println("被截断，重试没用，调大 max_tokens");
            return;
        }

        String cleaned = pickJson(raw);
        if (cleaned == null) {
            System.out.println("抠不出 JSON。这里该走兜底：重试 / 降级 / 抛错，三选一。");
            return;
        }
        try {
            JSONObject obj = JSON.parseObject(cleaned);
            System.out.println("解析成功：name=" + obj.getString("name")
                    + " count=" + obj.getIntValue("count")
                    + " amount=" + obj.getDoubleValue("amount"));
        } catch (Exception e) {
            // 注意：格式噪声可以修，语义错误不能修。字段名写错、类型错，修了只会更糟
            System.out.println("解析失败：" + e.getMessage());
            System.out.println("原始内容：" + Chat.brief(raw));
        }
    }

    /** 从「模型自由发挥的输出」里抠出 JSON：剥围栏、掐前后废话。 */
    static String pickJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    // ================================================================
    // D6：退避与熔断
    // ================================================================

    /**
     * 指数退避 + 抖动。为什么必须抖动：200 个实例同时被 429，
     * 退避时间完全相同的话它们会在同一毫秒继续打过来 —— 重试风暴比原问题更严重。
     */
    static long backoff(int attempt) {
        long base = Math.min(500L << (attempt - 1), 8_000);   // 500 / 1000 / 2000 / 4000 / 8000 封顶
        double jitter = 0.8 + Math.random() * 0.4;             // ±20%
        return Math.max(1, (long) (base * jitter));
    }

    /**
     * 场景一：假服务端一直回 429，看退避是不是真的在变长、总请求数是不是被限住。
     *
     * <p>要观察：每次等待时长递增、mock 收到的请求数 == 尝试次数。
     */
    static void retryDemo() throws Exception {
        String oldBase = Chat.BASE;
        try (Mock mock = Mock.start("429")) {
            Chat.BASE = mock.baseUrl();
            int maxAttempts = 4;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long t0 = System.currentTimeMillis();
                String result = Chat.quick("ping", 0);
                long ms = System.currentTimeMillis() - t0;

                if (result != null) {
                    System.out.printf("第 %d 次成功（%dms）%n", attempt, ms);
                    break;
                }
                if (attempt == maxAttempts) {
                    System.out.printf("第 %d 次仍失败，放弃%n", attempt);
                    break;
                }
                long wait = backoff(attempt);
                System.out.printf("第 %d 次失败（%dms），等 %dms 再试%n", attempt, ms, wait);
                Thread.sleep(wait);
            }
            System.out.println("假服务端共收到 " + mock.hits() + " 次请求（应等于尝试次数）");
            System.out.println("真实实现还要读 429 响应头里的 Retry-After，服务端说了算。");
        } finally {
            Chat.BASE = oldBase;
        }
    }

    /**
     * 场景二：连续故障后熔断，之后连网络都不碰。
     *
     * <p>三态：CLOSED 正常放行 → 连续失败到阈值转 OPEN 全拒 → 冷却期后 HALF_OPEN
     * 只放一个探测 → 成功回 CLOSED，失败回 OPEN。
     */
    static void breakerDemo() throws Exception {
        String oldBase = Chat.BASE;
        Breaker breaker = new Breaker(3, 1_500);

        try (Mock mock = Mock.start("500")) {
            Chat.BASE = mock.baseUrl();

            for (int i = 1; i <= 8; i++) {
                long t0 = System.currentTimeMillis();
                String outcome;

                if (!breaker.allow()) {
                    outcome = "被熔断拦截（本地拒绝）";
                } else {
                    String r = Chat.quick("ping", 0);
                    if (r == null) {
                        breaker.fail();
                        outcome = "失败";
                    } else {
                        breaker.ok();
                        outcome = "成功";
                    }
                }
                System.out.printf("第 %d 次: %-24s %4dms  状态=%s  假服务端累计=%d%n",
                        i, outcome, System.currentTimeMillis() - t0, breaker.state(), mock.hits());
            }
        } finally {
            Chat.BASE = oldBase;
        }
    }

    /** 精简三态熔断器。真实实现要加锁，这里单线程跑实验够用。 */
    static class Breaker {
        final int threshold;
        final long openMs;
        int fails;
        long openedAt;
        boolean halfOpenProbe;

        Breaker(int threshold, long openMs) {
            this.threshold = threshold;
            this.openMs = openMs;
        }

        boolean allow() {
            if (fails < threshold) {
                return true;                       // CLOSED
            }
            if (System.currentTimeMillis() - openedAt < openMs) {
                return false;                      // OPEN，冷却中
            }
            if (halfOpenProbe) {
                return false;                      // HALF_OPEN 且已有探测在跑，只放一个
            }
            halfOpenProbe = true;
            return true;
        }

        void ok() {
            fails = 0;
            halfOpenProbe = false;
        }

        void fail() {
            fails++;
            halfOpenProbe = false;
            if (fails >= threshold) {
                openedAt = System.currentTimeMillis();
            }
        }

        String state() {
            if (fails < threshold) {
                return "CLOSED";
            }
            return System.currentTimeMillis() - openedAt < openMs ? "OPEN" : "HALF_OPEN";
        }
    }

    static void requireKey() {
        if (Chat.KEY.isBlank()) {
            System.out.println("这个实验要打真端点，先 export M1_API_KEY=sk-xxxx");
            System.exit(1);
        }
    }
}
