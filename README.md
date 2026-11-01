# m1-mini

M1「裸调模型 API」的最小实现。**3 个文件，1065 行，覆盖路线图 M1 的全部七个任务。**

不建 DTO、不写 Builder、不分层。请求体用 `Map` 拼，响应用 `JsonNode` 取字段。
代价是没有编译期类型检查，换来的是你能直接看到报文长什么样。

| 文件 | 行数 | 干什么 |
|---|---|---|
| `Chat.java` | 532 | D1 手写 POST + D2 SSE 半包 + D4 记账 + D7 多轮流式 CLI |
| `Mock.java` | 169 | 假模型服务端：429 / 500 / 慢响应 / 坏 JSON / 半包，全都能造 |
| `Exp.java` | 364 | D3 结构化输出 + D5 差异率 + D6 退避与熔断 |

> 另有一份完整分层版（24 文件 / 3675 行，带 DTO、Builder、单元测试），
> 未随本仓库发布。那份适合等 M2 要加 RAG、要类型安全时再回去参考，M1 阶段用这个仓库。

---

## 跑起来

```bash
cd m1-mini
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS；Linux 用 JAVA_HOME=/path/to/jdk21
MVN=mvn                                            # mvn 不在 PATH 就换成完整路径

# 1. 假服务端，不需要 key，不花钱
$MVN -q compile exec:java -Dexec.args="--mock"

# 2. 真端点
export M1_API_KEY=sk-xxxx
$MVN -q compile exec:java

# 3. 三个实验
$MVN -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="temp"      # 差异率（要 key）
$MVN -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="json"      # 结构化（要 key）
$MVN -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="retry"     # 退避（不用 key）
$MVN -q compile exec:java -Dexec.mainClass=m1.Exp -Dexec.args="breaker"   # 熔断（不用 key）
```

CLI 命令：`/stream` `/sync` `/clear` `/stats` `/exit`

---

## 三个必须亲手做的观察

### 1. 半包（D2 的核心）

```bash
M1_DEBUG_SSE=1 $MVN -q compile exec:java -Dexec.args="--mock"
```

输出会变成这样 —— 一个 JSON 被切成 7 段：

```
[recv 16] data: {"id":"moc
[recv 16] k-1","model":"mo
[recv 16] ck","choices":[{"
[recv 16] "index":0,"delta
```

`readSse` 靠那个 `StringBuilder buf` 把它们拼回来。**把缓冲去掉试试？立刻崩。**
这就是为什么「读一次解析一次」的写法一定是错的。

### 2. 记账（D4）

`/stats` 看本次会话，`logs/calls.log` 看历史。每行都记了 in / out / 缓存命中 / 金额 / 首字延迟 / 总耗时。

比较流式和非流式：`/stream` 跑一次，`/sync` 跑一次，看 `ttft` 那一列。

### 3. 退避与熔断（D6）

```
retry    → 第 1 次失败（70ms），等 425ms 再试
           第 2 次失败（4ms），等 946ms 再试
           第 3 次失败（3ms），等 2072ms 再试

breaker  → 第 3 次: 失败 2ms 状态=OPEN 假服务端累计=3
           第 4 次: 被熔断拦截 0ms 状态=OPEN 假服务端累计=3
```

熔断的价值就在那个 `0ms` 和「累计不再增长」：故障期间一个请求都没浪费。

---

## 破坏实验清单（改一行，看会发生什么）

| 改什么 | 预期现象 | 说明什么 |
|---|---|---|
| `char[] chunk = new char[256]` 改 8192 | 半包消失，跑得很顺 | 你的代码没修好半包，只是本地网络太快 |
| 删掉 `readSse` 末尾的 `rest` 处理 | 用 `nodone` 模式跑，最后一句少几个字 | 服务端可能不发结尾空行 |
| `boundary()` 里去掉 `\r\n\r\n` 判断 | 换某些服务端会卡住不输出 | 分隔符有两种形态 |
| 去掉 `stream_options` | `/stats` 里 token 全是 0 | 流式默认不返回 usage |
| `backoff()` 里去掉 jitter | 连续跑几次，等待时间一模一样 | 重试风暴的成因 |
| `Breaker` 阈值改成 1 | 第一次失败就全拒 | 阈值太敏感等于没有服务 |
| `pickJson` 直接用 `readTree(raw)` | 偶尔解析失败 | 模型会加 ```json 围栏和废话 |

---

## 验收

- [ ] 能不看代码讲清「一次流式调用发生了什么」，从 `post` 讲到 `bill`
- [ ] 能解释 `buf` 为什么必须存在（半包）
- [ ] `numbers.md` 里有三个数：差异率、单次成本、P95 延迟
- [ ] 破坏清单里的前三条亲手试过

## 配置（全部走环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `M1_API_KEY` | — | 也认 `DEEPSEEK_API_KEY` |
| `M1_BASE_URL` | `https://api.deepseek.com` | 换厂商只改这个 |
| `M1_MODEL` | `deepseek-v4-flash` | `deepseek-chat` 已于 2026-07-24 废弃 |
| `M1_PRICE_IN` / `M1_PRICE_OUT` / `M1_PRICE_HIT` | 0.14 / 0.28 / 0.0028 | 每百万 token，**跑前核对官网** |
| `M1_CURRENCY` | `$` | 只影响打印 |
| `M1_TIMEOUT` | 120 | 秒。是整个响应的超时，不是首字节 |
| `M1_DEBUG_SSE` | 0 | 1 = 打印每次 read 的原始片段 + 缓冲缩到 16 字符 |
| `M1_MOCK_MODE` | stream | stream / nodone / sync / 429 / 500 / slow / badjson |
