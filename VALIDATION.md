# 0.2.0 context-sharing acceptance

Tested against the isolated official **GTNH 2.9.0-RC-2** Java 17–26 instance, on Apple Silicon with Java 25. No local beta2 instance or personal save was used.

Production jar SHA-256: `fd4cabe4a500257c76b68b149d796e2af85ff2a5419a7575bf1c3189cd1c98d5`.

- **45 automated tests passed**, plus Spotless/Checkstyle and a complete build. Coverage includes existing tools, persisted messages, corruption/write failures, world identities, optimistic revisions, official SDK resource subscriptions, reconnection, connection limits, session deletion/expiry races and deadline cancellation.
- **35 live protocol checks passed** against the production shaded jar. All 15 tools were exercised, including real project/message SSE notifications, receipt retry idempotency, local reply deduplication, rejection with no active world, and retained project/recipe data after leaving and re-entering the save.
- **22 game-internal GUI checks passed**: vanilla chat `/gtnhai` dispatch, deferred local composer, editing and Enter submission, cancellation, real NEI RecipeWidget AI button, exact recipe snapshot, GT metadata, additional requirements and return to the original recipe screen. The chosen soul-sand recipe was **Multiblock Centrifuge** (`gt.recipe.multicentrifuge`), preserving 12 EU/t, 200 ticks, probabilities and 200 mB oil.

These GUI checks invoke the production GUI handlers inside the running game. **They are not OS input, screenshot/visual or manual acceptance. The hovered-item hotkey has compiled API coverage but has not been exercised live.** Native computer automation could not target the Java window. No host-specific conversation adapter is included or claimed tested; this release supplies the generic interface.

Evidence: [summary](validation/context-0.2.0-summary.json), [live checks](validation/context-0.2.0-live.json), [GUI checks](validation/context-0.2.0-ui.json).

To reproduce after enabling the separate acceptance fixture, run:

```sh
python3 scripts/rc2_context_acceptance.py --config /path/to/isolated/.minecraft/config/gtnhmcp.properties --control-dir /path/to/acceptance-control --output-dir /path/to/results
```

The script requires the disposable `gtnhmcp-acceptance-` world, opens the local test GUI, updates test planning memory, displays a local test reply, and unloads/rejoins that disposable save. It does not connect to an AI provider. The acceptance fixture is excluded from the production jar.

---

The following is the retained validation record for **0.1.0**, not a claim that its artifact hash identifies 0.2.0.

# RC2 验证记录

验证日期：2026-10-30。发布 jar SHA-256：`e5e37a7093765b6980a1f48e97fc04c93ab8b6dcc4bede1263d7edf9975705a8`。

## 自动化构建

`./gradlew --no-daemon test build reobfAcceptanceJar` 成功，20 项 Java 测试、Spotless、Checkstyle 全部通过。覆盖官方 MCP Java 客户端互通、完整文本/结构化输出、认证与 Origin/Host、并发和大小限制、绝对截止时间取消、游戏线程队列、NEI 参数/缓存及 handler identity 去重。

正式 reobf/shaded jar 在独立 Java 进程中通过 initialize → tools/list → tools/call，确认依赖重定位和 ServiceLoader 可用。此探针使用 fake 游戏回调；下述验证使用真实整合包。

## 真实 RC2 存档

使用官方 **GT New Horizons 2.9.0-RC-2 Java 17–26** 分发包建立独立实例，在 macOS Apple Silicon、Java 25 上启动。运行时确认 NEI `2.8.155-GTNH`、GregTech `5.09.54.205`、GTNHLib `0.11.52`。未修改本机 beta2 实例。

通过单独测试 jar 创建临时单人超平坦世界；发布 jar 不包含测试夹具、存档控制或写操作。测试夹具仅准备固定库存、位置及暂停/重进场景，所有断言通过真实 HTTP MCP 读取生产 mod。实际安装 jar 与本页发布 SHA-256 一致。

完整脚本通过 **50 项断言**，包括：

| 场景 | 观测结果 |
|---|---|
| MCP 工具发现 | 全部 9 个工具可调用 |
| 玩家背包 | 铁锭 32、红石 16、钻石 3；来源 integrated_server |
| 箱子、准星和附近查找 | 箱子 (2,65,0)，库存铁锭 64、红石 32、原木 16；可找到箱子与熔炉 |
| NEI 铁锭固化配方 | 实际输入 molten.iron 144 mB，模具数量 0/nonConsumed=true；输出 1 铁锭；32 ticks |
| NEI 灵魂沙用途 | 离心配方输出概率 1000/700/9000，比例基数 10000；流体输出 200 mB；200 ticks、12 EU/t |
| 暂停/恢复 | 暂停库存读取返回 world_paused；恢复后正常且保留世界会话 |
| 退出/重进 | 退出显示 menu/no_world；重进生成新 worldSessionId，库存读取正常 |
| 缓存失效 | 5 分钟 TTL 到期前，重进使旧 recipeId 无效 |

GT 原生 NEI 会扩展熟悉的矿辞前缀：查询铁锭也会返回铁粒等形态。验收按每条实际槽位选择配方，接口的 `matchingSemantics` 明确此语义。

查看 [断言与精选响应](validation/rc2-live-summary.json)；[完整 HTTP 调用记录（gzip JSON）](validation/rc2-live-full.json.gz) 保存全部分页和生命周期响应，不含连接 token。

## 复现

在另一个独立 RC2 测试实例中使用 [测试夹具说明](src/acceptance/README.md) 构建、安装独立 acceptance jar，并添加说明中的 JVM 参数。随后运行：

```sh
python3 -B scripts/live_acceptance.py \
  --config '/测试实例/.minecraft/config/gtnhmcp.properties' \
  --output '/临时目录/rc2-live.json' \
  --control-dir '/测试夹具控制目录'
```

只在临时实例安装 acceptance jar；常规使用只安装 `gtnhmcp-0.1.0.jar`。

## 验证范围

这是客户端只读原型的功能验收，不是完整整合包 playtest。尚未在 Windows/Linux、Java 17/21/26、远程多人服务器或大型基地长期运行。GT 配方元数据已实测；大型多方块机器状态、能源仓聚合、各模组全部 NEI handler、NEI 重载事件及极端长耗时第三方回调没有逐项游戏验收。机器能源字段只表示控制器 tile，不表示整座多方块的总量。

NEI handler 的单次同步调用不可安全抢占，250 ms 是合作式分页预算，首查可能超过该值。所有截断、查询上限、未知条件和局部失败需由调用方保留，不能据此推导完整生产计划。
