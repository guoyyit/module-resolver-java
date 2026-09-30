# module-resolver-java

模块清单解析与依赖关系校验服务。解析模块声明文件中的依赖、导出包、服务提供与服务消费声明，构建带导出可见性的模块依赖图，校验循环依赖、导出包冲突与服务绑定合法性；支持确定性增量解析、崩溃恢复与并发一致读写。

- Java 17 / Spring Boot 3.4，无外部中间件，本地直接可复现
- 纯函数式解析与校验内核（`ResolutionEngine`），I/O 与并发隔离在外层
- 快照（不可变版本）+ 追加事件日志（WAL）双持久化，原子写入、无半写状态

## 构建与运行

```bash
mvn test          # 全量自动化测试（本地可复现，无外部依赖）
mvn spring-boot:run
```

默认状态目录：`${java.io.tmpdir}/module-resolver-state`，可用属性覆盖：

```bash
java -jar target/module-resolver-1.0-SNAPSHOT.jar --resolver.state-dir=./var/state
```

## 声明文件语法

UTF-8、行导向；`#` 起始为注释；每条指令独占一行。示例见 `examples/modules/`。

```
module   <模块名>
requires [static] <模块名>                    # static 表示可选依赖
exports  <包名>
provides <接口全限定名> with <实现全限定名>[, <实现全限定名> ...]
consumes [optional] <接口全限定名>            # optional 表示可选消费
```

- 名称必须是合法点分标识符（`[A-Za-z_$][A-Za-z0-9_$]*(.…)*`）。
- 同一文件内重复声明（重复 requires/exports/provides/consumes）产生 `WARNING` 并去重；重复 `module`、缺 `module` 头、未知指令、参数数量错误、非法名称产生带文件/行/列的 `ERROR`。
- 解析失败是**文件级**结果：该文件不产生模块声明，错误以精确定位进入全局诊断，但不阻断其它文件解析。

## 判定规则（确定且可解释）

所有判定只依赖声明内容本身，内部集合统一按字典序归一化，因此：

- 相同输入始终得到相同输出，与文件提交顺序、线程调度无关。

### 1. 直接依赖与传递依赖

- 直接依赖 = 本模块 `requires` 中目标模块存在的边。
- 传递依赖 = 从直接依赖出发 BFS 可达、且不是直接依赖的模块（自环除外），按层字典序输出。
- 依赖缺失：
  - 目标模块不存在且为必需依赖 → `REQUIRED_DEPENDENCY_MISSING`（`ERROR`，悬空边保留以便传播影响）。
  - 目标模块不存在且为 `static` 可选依赖 → `OPTIONAL_DEPENDENCY_MISSING`（`WARNING`），**不阻断**整体解析。
  - `requires` 自身 → `SELF_REQUIRES`（`WARNING`），仍参与环检测（自环）。

### 2. 循环依赖

使用 Tarjan 强连通分量（确定性迭代实现）：

- 每个含环 SCC（含自环）产生一条规范环路径：以分量中字典序最小节点为起点、回到该节点结束，取从起点回到自身的最短环；多条结果按起点排序。
- 诊断码 `CYCLE`（`ERROR`），同时在每个参与模块的 `ModuleResolution.onCycle` / `cycles` 中标注。

### 3. 导出包冲突

对每个被导出的包，按顺序套用规则：

| 规则 | 条件 | 结论 |
| --- | --- | --- |
| `UNIQUE_EXPORTER` | 仅一个模块导出 | 归属该模块，无冲突 |
| `PARENT_WINS` | 多个模块导出，但恰有一个被其余所有竞争者（直接/传递）依赖 | 归属于该被依赖的“底层”模块；依赖方同名导出记 `SHADOWED_EXPORT`（`WARNING`） |
| `AMBIGUOUS_EXPORT` | 以上均不满足（互不相关模块、或环内多个导出者） | `ERROR`；为保持下游确定性，临时归属字典序最小竞争者，但任何接口包处于该状态的服务均不可见 |

### 4. 服务绑定校验

对每条 `consumes`，收集**可见**提供者（同一模块，或被直接/传递依赖的模块），且提供者必须导出接口所在包、并且该包归属无歧义：

- 无提供者：必需消费 → `MISSING_PROVIDER`（`ERROR`，状态 `MISSING_REQUIRED`）；`optional` 消费 → 同码 `WARNING`（状态 `MISSING_OPTIONAL`），不阻断解析。
- 多个可见提供者，确定性选择：
  1. `DIRECT_PROVIDER`：直接依赖的提供者优先于传递依赖提供者（附 `INFO` 说明）。
  2. `LEXICOGRAPHIC`：同优先级内按 `(提供者模块, 实现类)` 字典序取最小。
  3. 若最高优先级仍有多于一个候选，绑定照常使用确定性胜者，但状态置为 `BOUND_AMBIGUOUS` 并产生 `AMBIGUOUS_PROVIDER`（`WARNING`），列出全部并列候选与最终选择。

### 5. 其它全局诊断

- `DUPLICATE_MODULE_NAME`（`ERROR`）：同一模块名在多个文件声明；以文件名字典序最小者为准，其余报错并附位置。

## 增量解析

`POST /api/resolution/files`（或删除）只重算受影响集合：

1. 变更文件涉及的旧模块名与新模块名；
2. （直接/传递）依赖这些模块的全部下游模块；
3. 处于冲突态的导出包的所有竞争者；
4. 服务绑定非 `BOUND`、或其选中提供者属于变更模块的消费方。

未变化模块的 `ModuleResolution` 结论逐对象保持稳定（测试中用对象相等断言）。注意：全局诊断、包归属与服务绑定表始终按新输入整体重算以保证一致版本，但内容对相同输入保持确定。

每次提交生成单调递增的新版本号；解析中读到的永远是某个完整快照（不可变对象 + 原子引用替换）。

## 持久化与崩溃恢复

状态目录布局：

```
<state-dir>/
  snapshot.json     # 最近一次已提交快照（临时文件 + 原子 move 写入）
  journal.logl      # 追加事件日志（JSON Lines，append+fsync）
  *.tmp             # 崩溃残留临时文件，启动时清理，永不参与恢复
```

写入顺序（WAL）：

1. 先在内存中纯计算出新快照；计算失败/取消则什么都不写。
2. `journal.logl` 追加 `upsert`/`delete` 事件并 fsync。
3. 原子写入 `snapshot.json`。
4. 追加 `checkpoint(version)` 并截断此前日志（临时文件 + 原子替换）。
5. 原子引用切换到新快照。

恢复：加载最近快照 → 重放最后一个 checkpoint 之后的事件（幂等地再跑引擎）→ 重新落盘并 checkpoint。崩溃可能落在任一步：要么沿用上一版本、要么得到新版本，不会出现半写入。无快照但有日志时，由日志重建全量。

## 并发与超时

- 读：无锁，`current()` 返回不可变快照；读绝不会看到中间态。
- 写：单写线程 + 有界队列（容量 1024，`AbortPolicy`），按提交顺序串行化；同一模块并发更新因此具有确定顺序，全过程版本号、事件日志可追溯。队列满时异步请求以 failed future 返回 `resolution write queue is full`，不影响后续写入。
- 取消/超时：`upsertFileAsync` 的 future 可 `cancel(true)`；引擎在各阶段检查中断，取消的计算不落盘、不推进版本。同步重载 `upsertFile(file, content, timeout, unit)` 支持超时包装。

## HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/resolution` | 当前完整快照（版本、文件解析结果、声明、模块结论、诊断、包归属、服务绑定） |
| GET | `/api/resolution/version` | 当前版本号 |
| GET | `/api/resolution/modules/{name}` | 单个模块结论，不存在返回 404 |
| GET | `/api/resolution/diagnostics` | 全局诊断列表 |
| GET | `/api/resolution/journal` | 当前事件日志（可追溯） |
| POST | `/api/resolution/files` | 增量 upsert：`{"file":"a.module","content":"module ..."}` |
| DELETE | `/api/resolution/files/{name}` | 删除声明文件并增量重算 |
| POST | `/api/resolution/rebuild` | 以 `{文件名: 内容}` 整体替换并重算 |

快速体验：

```bash
curl -s -XPOST localhost:8080/api/resolution/files -H 'Content-Type: application/json' \
  -d '{"file":"core.module","content":"module com.core\nexports com.example.api\n"}'
curl -s localhost:8080/api/resolution/modules/com.core
```

## 规模与内存

- 1000 模块链全量解析在普通开发机约 1–2 秒（见 `ScalePerformanceTest`，阈值 10s）。
- 数据结构按模块/边规模线性增长；写队列有界；快照为不可变对象，旧版本读完成后即可回收（仅保留当前版本）。

## 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `ManifestParserTest` | 正常解析、精确定位、缺 module 头、重复指令告警 |
| `DependencyValidationTest` | 直接/传递依赖、确定性环、自环、三种导出冲突规则、服务多提供者歧义、必需/可选服务缺失、可选依赖缺失不阻断 |
| `IncrementalResolutionTest` | 未变结论稳定、受影响集、失败恢复、顺序无关、删除传播 |
| `PersistenceRecoveryTest` | 重启恢复、checkpoint 后日志重放、临时文件清理、删除事件恢复 |
| `ConcurrencyConsistencyTest` | 并发读一致、同模块并发更新确定、版本单调、取消不破坏索引、有界队列拒绝 |
| `ResolutionApiTest` | REST upsert/query/delete 与错误定位 |
| `ScalePerformanceTest` | 千级模块耗时、增量耗时、诊断数量有界 |

## 兼容性边界

- 声明语法为本项目自定义行格式（不是 JPMS `module-info.java`）；不支持多行续写指令、通配符导出、`requires transitive` 修饰（`static` 即“可选”语义）。
- 只依据声明做静态校验：不加载字节码、不校验实现类真实存在、不校验实现是否真的实现接口；接口“包”由接口全限定名去掉最后一段推导。
- 持久化 JSON 结构随模型版本演进；当前不提供跨不兼容版本的快照迁移，升级前请清空状态目录并通过事件源/rebuild 重建。
- 单写线程意味着写吞吐串行化（读完全并发）；面向“大规模模块集、低频增量提交”的场景设计，不是高 QPS 写入服务。
