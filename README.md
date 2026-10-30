# GTNH MCP 0.1.0

安装在 **GTNH 2.9.0-RC-2 的 Java 17–26 客户端**中的本地、只读 MCP 服务。配方统一通过 NEI 查询，GT 特殊字段从 NEI 查询结果持有的原始配方引用补充。

这是首个可运行原型。提供真实游戏数据；mod 本身不接入模型、不生成回答。

## 安装和连接

1. 将发布文件 `gtnhmcp-0.1.0.jar` 放入 RC2 实例的 `.minecraft/mods/`。不要安装 `-dev.jar` 或 `-sources.jar`。
2. 启动游戏。加载完成后，默认在 `http://127.0.0.1:29710/mcp` 监听。
3. 读取自动生成的 `.minecraft/config/gtnhmcp.properties`。其中 `token` 是本实例的随机连接令牌；请勿公开。若端口冲突，修改 `port` 后重启游戏。
4. 在支持 Streamable HTTP 的 MCP 客户端中配置该地址，并添加 HTTP 请求头 `Authorization: Bearer <token>`。
5. 进入存档，等待 NEI 物品列表加载完毕后即可查询。全包第一次查询可能较慢。

连接参数（具体配置文件格式以你的 MCP 客户端为准）：

```text
Transport: Streamable HTTP
URL: http://127.0.0.1:29710/mcp
Authorization: Bearer <config/gtnhmcp.properties 中的 token>
Protocol baseline: 2025-11-25
```

安装仅需一个 jar，无需 Python/Node 服务。随附的 Python 脚本只是可选的连接诊断工具：

```sh
python3 scripts/mcp_probe.py --config '/你的实例/.minecraft/config/gtnhmcp.properties' --list
python3 scripts/mcp_probe.py --config '/你的实例/.minecraft/config/gtnhmcp.properties' --tool search_items --arguments '{"query":"iron ingot","limit":10}'
python3 scripts/mcp_probe.py --config '/你的实例/.minecraft/config/gtnhmcp.properties' --tool find_recipes --arguments '{"registryName":"minecraft:iron_ingot","limit":5}'
```

默认英文实例按英文名称搜索；中文名称搜索取决于当前游戏语言。无论语言如何，registry ID 都可用于搜索和查询。

## 已实现的工具

| 工具 | 用途 |
|---|---|
| `get_context` | 当前世界会话、玩家、维度、已安装版本、能力边界 |
| `search_items` | NEI 物品按显示名或 registry ID 搜索，返回 `nextOffset` |
| `find_recipes` | 以物品为入口查询 NEI 制作配方，返回配方摘要、`recipeId` 和续页 `cursor` |
| `find_usages` | 以物品为入口查询 NEI 原料用途，结果不受键盘 Ctrl 状态影响 |
| `get_recipe` | 读取已查询配方的详细输入、输出、替代材料及可提取的 GT 条件 |
| `get_inventory` | 玩家背包，或指定坐标的单个容器 |
| `inspect_target` | 查看准星指向的方块；单人模式可含机器、容器与流体状态 |
| `inspect_machine` | 读取当前维度 64 格内指定坐标的已加载 TileEntity |
| `find_machines` | 限定半径内查找已加载 TileEntity，包含箱子等非机器对象 |

配方查询首个请求提供 `registryName`、可选 `meta`/`nbt`；续页提供返回的 `cursor` 和 `limit`。物品搜索续页传 `nextOffset` 作为 `offset`，并保留原 `query`。

查询保留 NEI handler 的原生匹配语义，响应中的 `matchingSemantics` 会说明这一点。GT 会扩展矿物辞典等价物和“熟悉前缀”：查询铁锭也可能返回同材质铁粒的配方；流体及其容器也可能关联。因此不能认为每条结果都直接生产或消耗请求的物品，应读取 `get_recipe` 的实际输入、输出、数量和流体名称后再选择生产路径。

机器和容器查询使用当前玩家的维度。`get_inventory` 默认 `scope=player`；指定容器需要 `scope=container` 及 `x,y,z`。不会自动扫描整个基地或加载区块。

## 数据和运行边界

- 单人模式的库存、方块和机器状态从内置服务端线程读取。准星、NEI 和显示名从客户端线程读取。HTTP 线程不直接访问游戏对象。
- 连接远程服务器时仍可查询客户端 NEI 和玩家已同步的信息；需要内置服务端的工具返回 `singleplayer_required`。
- 世界结果带 `worldSessionId`、来源与采样时间。暂停时服务端查询拒绝或超时；退出/切换世界会使旧请求和配方缓存失效。
- 配方 ID 和游标有容量限制及 5 分钟 TTL；过期后重新查询。NEI/GT 重载会触发缓存失效。
- 通用 NEI 的其他槽位保留 `unknown` 角色；仅当 GT handler 提供明确语义时将其转换为输出。展示用配方、未知条件不等同于可执行生产计划。
- 替代材料、槽位、返回数量均有上限；截断和局部 handler 错误会显式报告。
- GT 能源字段 `controllerStoredEu` / `controllerEuCapacity` 仅表示所查询控制器方块，`energyScope=controller_tile`；不是多方块所有能源仓的总量。
- 流体查询仅请求 `UNKNOWN` 侧视图，最多返回 64 个槽位。`reportedTankCount` 与 `complete` 标明该次报告是否完整；handler 返回 `null` 时为 `available=false`、`complete=false`，总数未知。
- 每页 NEI 查询有 **250 ms 合作式预算**。第三方 handler 的单次同步调用不能安全抢占，首次初始化仍可能造成短暂卡顿。这不是硬实时保证。
- 尚未实现材料树计算、AE2 网络、任务书或写操作。机器诊断仅使用已有只读 getter，不主动触发结构检查。

本地服务仅绑定 IPv4 回环地址，所有请求需要令牌；显式拒绝不受信任的 Origin/Host。设置 `enabled=false` 并重启可关闭服务。

## 开发

工程来自 GTNH 官方 project starter，固定 RC2 的依赖：

- NotEnoughItems `2.8.155-GTNH`
- CodeChickenCore `1.4.22`
- GTNHLib `0.11.52`
- GregTech `5.09.54.205`（可选的反射增强，无平行配方索引）

构建使用 Gradle wrapper 和 GTNHGradle；Gradle daemon 使用 JDK 25，项目编译目标为 Java 17。Gradle 的 toolchain resolver 可下载所需 JDK。

```sh
./gradlew --no-daemon test build
```

正式发布包位于 `build/libs/gtnhmcp-0.1.0.jar`。修改源码后可运行 `./gradlew spotlessApply` 应用项目格式。

MCP 使用官方 Java SDK 2.1.0 的协议层和自定义 JDK HTTP transport；SDK、Jackson、Reactor 等依赖打包重定位，避免与旧模组共用依赖版本。HTTP 使用 JSON 回复，不提供订阅流。

测试覆盖协议互通、访问控制、输入/返回大小与超时、游戏线程队列取消、缓存和参数边界。协议单元测试的游戏 callback 是 fixture，不代表完整整合包验收；实际游戏验证单独记录在 `VALIDATION.md`。

## 版本依据

官方 RC2 manifest：
https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/3dce613855c59dfa98dd30d90d5cfdd21c566bf7/releases/manifests/2.9.0-RC-2.json

manifest SHA-256: `27fc9217a1e8bc8ed3902f163115269038ffc788e51cabf30d27ac5ef09e3831`

本项目采用 MIT；随包第三方组件许可证见 `THIRD-PARTY-NOTICES.md` 和 jar 内 `META-INF/licenses/`。
