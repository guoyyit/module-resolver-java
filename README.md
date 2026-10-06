# module-resolver-java

模块清单解析与依赖关系校验服务。解析模块声明文件中的依赖、导出包、服务提供与服务消费声明，构建带导出可见性的模块依赖图，校验循环依赖、导出包冲突与服务绑定合法性；支持 `requires transitive` 导出包传播语义、确定性增量解析、崩溃恢复与并发一致读写。

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
requires [static] [transitive] <模块名>       # static 表示可选依赖；transitive 表示传播导出包可见性
exports  <包名>
provides <接口全限定名> with <实现全限定名>[, <实现全限定名> ...]
consumes [optional] <接口全限定名>            # optional 表示可选消费
```

- 名称必须是合法点分标识符（`[A-Za-z_$][A-Za-z0-9_$]*(.…)*`）。
- `requires` 的两个修饰位可任意顺序书写（`requires static transitive x` 与 `requires transitive static x` 等价），可组合使用。
- 同一文件内重复声明（重复 requires/exports/provides/consumes）产生 `WARNING` 并去重；重复 `module`、缺 `module` 头、未知指令、参数数量错误、非法名称产生带文件/行/列的 `ERROR`。
- `requires` 修饰位写法非法有专属可区分错误码：未知修饰位 `UNKNOWN_MODIFIER`（定位到该修饰位 token）、同一修饰位重复 `DUPLICATE_MODIFIER`、只有修饰位没有模块名 `MALFORMED_DIRECTIVE`。
- 解析失败是**文件级**结果：该文件不产生模块声明，错误以精确定位进入全局诊断，但不阻断其它文件解析。

## 传播语义与可读模块

`requires transitive <模块>` 声明：任何**读到本模块**的模块，同时也能读到被 transitive 依赖的模块所导出的一切——即被依赖模块的导出包可见性沿依赖链向上游（依赖方）传播。未加 `transitive` 的普通 `requires` 只让直接依赖对**自己**可读，不再向上传播。

形式化判定（单调方程，最小不动点唯一，与求值顺序无关）：

```
propagated(M) = ⋃ { {T} ∪ propagated(T) : M requires transitive T }
readable(M)   = directDeps(M) ∪ ⋃ { propagated(D) : D ∈ directDeps(M) }
```

- `readable(M)`（结论中的 `readableModules`）：M 自身可读的模块集合 = 全部直接依赖 + 经由 transitive 边传播而来的模块。
- `propagated(M)`（结论中的 `propagatedModules`）：M 通过自身的 transitive 边继续传播给"读到 M 的模块"的模块集合。
- 直接依赖（无论是否 transitive）对声明者永远可读；链路上某一跳是普通 `requires` 时，传播在该跳之后终止（隔离）。
- 例：`app requires mid`，`mid requires transitive core` ⇒ `app` 可读 `core`；若 `mid` 写的是普通 `requires`，则 `app` 只能读到 `mid`。
- 依赖结论在 `ModuleResolution` 中明确区分三类：直接依赖 `directDependencies`、普通传递依赖 `transitiveDependencies`（全部可达，无论是否可读）、因传播语义而可读的模块（`readableModules` 去掉直接依赖后的部分）。
- 计算方式：`propagated` 即 transitive 边子图中的可达集，按模块做有界 DFS；千级模块长链下为 O(模块数 × 边数)。增量解析时只对受影响区域（见下）重算，未受影响模块沿用上一版本的同一个列表对象。

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
| `PARENT_WINS` | 多个模块导出，但恰有一个被其余所有竞争者（直接/传递）依赖 | 归属于该被依赖的"底层"模块；依赖方同名导出记 `SHADOWED_EXPORT`（`WARNING`） |
| `AMBIGUOUS_EXPORT` | 以上均不满足（互不相关模块、或环内多个导出者） | `ERROR`；为保持下游确定性，临时归属字典序最小竞争者，但任何接口包处于该状态的服务均不可见 |

### 4. 服务绑定校验

对每条 `consumes`，收集**可见**提供者：同一模块，或在本模块 `readableModules` 内（直接依赖，或仅通过 `requires transitive` 传播才可达）的模块；仅通过传播语义才可达的提供者照常参与绑定，不可达的提供者不得参与。提供者还必须导出接口所在包、并且该包归属无歧义：

- 无提供者：必需消费 → `MISSING_PROVIDER`（`ERROR`，状态 `MISSING_REQUIRED`）；`optional` 消费 → 同码 `WARNING`（状态 `MISSING_OPTIONAL`），不阻断解析。
- 多个可见提供者，确定性选择：
  1. `DIRECT_PROVIDER`：直接依赖的提供者优先于仅传播可见的提供者（附 `INFO` 说明）。
  2. `LEXICOGRAPHIC`：同优先级内按 `(提供者模块, 实现类)` 字典序取最小。
  3. 若最高优先级仍有多于一个候选，绑定照常使用确定性胜者，但状态置为 `BOUND_AMBIGUOUS` 并产生 `AMBIGUOUS_PROVIDER`（`WARNING`），列出全部并列候选与最终选择。

### 5. 其它全局诊断

- `DUPLICATE_MODULE_NAME`（`ERROR`）：同一模块名在多个文件声明；以文件名字典序最小者为准，其余报错并附位置。

## 增量解析

`POST /api/resolution/files`（或删除）只重算结论真正可能变化的模块：

1. 先求**重算集**：变更文件的旧/新模块名（种子）+ 在当前依赖图中能到达种子的全部下游模块（反向闭包）。依赖边只随声明文件变化，因此重算集之外的模块其前向依赖锥完全未变，任何结论都不可能改变。
2. 可读模块表、导出包归属表、服务绑定表均按重算集做**增量重算**：
   - 可读性：重算集内按当前声明重算 DFS 可达集，重算集外模块的传播集合作为常量直接复用上一版本对象；
   - 包归属：竞争者集合未变且没有竞争者落在重算集内的包，直接复用上一版本 `PackageOwnership` 对象；
   - 服务绑定：不在重算集内、且所消费接口包的归属未变的消费方，直接复用上一版本绑定列表对象。
3. 模块结论（`ModuleResolution`）只对重算集内、或其导出/消费的包归属发生变化的模块重新构造；新结论与上一版本**内容相等**时同样沿用旧对象。
4. 因此未受影响模块的结论是上一版本中的**同一个对象**（测试用 `assertSame` 断言，内容相等但重新构造的对象不满足要求）。受影响集合 = 结论对象或绑定列表对象真正发生变化的模块，覆盖传播语义变化导致可读范围变化的下游模块、冲突归属变化与服务绑定变化波及的模块。

每次提交生成单调递增的新版本号；解析中读到的永远是某个完整快照（不可变对象 + 原子引用替换）。全局诊断列表按新输入整体重排（内容确定），未变化部分的诊断对象同样来自复用。

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

快照包含全部增量状态：每个模块的可读模块集合与传播集合（`readableModules` / `propagatedModules`）、包归属与服务绑定表，恢复后可直接继续增量解析而无需全量重算。

恢复：加载最近快照 → 重放最后一个 checkpoint 之后的事件（幂等地再跑引擎）→ 重新落盘并 checkpoint。崩溃可能落在任一步：要么沿用上一版本、要么得到新版本，不会出现半写入。无快照但有日志时，由日志重建全量。恢复后继续做增量解析，其结论与从未崩溃、按相同操作顺序执行的运行完全一致（`PersistenceRecoveryTest.recoveryThenIncrementalMatchesNoCrashRun` 逐字段比对）。

## 并发与超时

- 读：无锁，`current()` 返回不可变快照；读绝不会看到中间态（读写交织下同一版本内可见性结论必然自洽，见 `ConcurrencyConsistencyTest.readersSeeConsistentVisibilityDuringConcurrentUpdates`）。
- 写：单写线程 + 有界队列（容量 1024，`AbortPolicy`），按提交顺序串行化；同一模块并发更新因此具有确定顺序，全过程版本号、事件日志可追溯。队列满时异步请求以 failed future 返回 `resolution write queue is full`，不影响后续写入。
- 取消/超时：`upsertFileAsync` 的 future 可 `cancel(true)`；引擎在各阶段检查中断，取消的计算不落盘、不推进版本。同步重载 `upsertFile(file, content, timeout, unit)` 支持超时包装。

## HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/resolution` | 当前完整快照（版本、文件解析结果、声明、模块结论、诊断、包归属、服务绑定） |
| GET | `/api/resolution/version` | 当前版本号 |
| GET | `/api/resolution/modules/{name}` | 单个模块结论（含 `readableModules` / `propagatedModules`），不存在返回 404 |
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

- 1000 模块链全量解析在普通开发机约 1–2 秒；1000 模块全 transitive 链的可读性计算同量级有界（见 `ScalePerformanceTest`，阈值 10s）。
- 增量解析耗时与受影响区域（重算集）规模相关：链首变更只重算 1 个模块；链中变更只重算其上游一半。
- 数据结构按模块/边规模线性增长；写队列有界；快照为不可变对象，未变化模块的结论、可读性列表、归属与绑定对象跨版本共享（结构共享），旧版本读完成后即可回收——多次增量提交后内存占用不随提交次数增长（`PropagationVisibilityTest.repeatedCommitsDoNotDuplicateUnchangedConclusions` 验证 50 次提交后结论对象逐一相同）。

## 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `ManifestParserTest` | 正常解析、精确定位、缺 module 头、重复指令告警、transitive 修饰位解析与三类非法写法（`UNKNOWN_MODIFIER` / `DUPLICATE_MODIFIER` / `MALFORMED_DIRECTIVE`） |
| `DependencyValidationTest` | 直接/传递依赖、确定性环、自环、三种导出冲突规则、服务多提供者歧义、必需/可选服务缺失、可选依赖缺失不阻断 |
| `PropagationVisibilityTest` | 传播可见性正常与隔离场景、多跳传播链、传播语义变化引发的下游重算与对象复用、服务绑定随可见性变化、增量与全量结论一致、提交顺序无关、50 次提交内存不增长 |
| `IncrementalResolutionTest` | 未变结论对象复用（`assertSame`）、精确受影响集、失败恢复、顺序无关、删除传播 |
| `PersistenceRecoveryTest` | 重启恢复、checkpoint 后日志重放、临时文件清理、删除事件恢复、崩溃后继续增量解析与未崩溃运行逐字段一致 |
| `ConcurrencyConsistencyTest` | 并发读一致、可见性读写交织无半更新态、同模块并发更新确定、版本单调、取消不破坏索引、有界队列拒绝 |
| `ResolutionApiTest` | REST upsert/query/delete、错误定位、transitive 可见性经 API 暴露 |
| `ScalePerformanceTest` | 千级模块耗时、千级 transitive 链可读性有界、增量耗时与受影响区域相关且与全量结果一致、诊断数量有界 |

## 兼容性边界

- 声明语法为本项目自定义行格式（不是 JPMS `module-info.java`）；不支持多行续写指令、通配符导出。`static` 即"可选"语义，`transitive` 即"传播导出包可见性"语义，二者可组合。
- 传播语义完全可选、向后兼容：未使用 `transitive` 的既有清单文件无需任何修改即可解析，直接依赖的可见性、包归属与服务绑定判定规则保持不变；但注意可见性模型以"可读模块"为准——普通 `requires` 链上的**间接**模块导出包默认不可见，需要可见时应在中间模块上补 `transitive` 修饰。
- 只依据声明做静态校验：不加载字节码、不校验实现类真实存在、不校验实现是否真的实现接口；接口"包"由接口全限定名去掉最后一段推导。
- 持久化 JSON 结构随模型版本演进；当前不提供跨不兼容版本的快照迁移（本轮为 `ModuleDependency`/`ModuleResolution` 增加了字段，旧快照需清空状态目录后通过事件源/rebuild 重建）。
- 单写线程意味着写吞吐串行化（读完全并发）；面向"大规模模块集、低频增量提交"的场景设计，不是高 QPS 写入服务。
