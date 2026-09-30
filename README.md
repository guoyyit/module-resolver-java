# module-resolver-java

模块清单解析与依赖关系校验库（Spring Boot 3 / Java 17，零外部中间件，本地可复现）。

解析模块声明文件中的依赖、导出包、服务提供/消费，构建带导出可见性的模块依赖图，
校验**循环依赖、导出包冲突、服务绑定合法性**，并支持**增量更新、持久化、崩溃恢复
与并发一致查询/更新**。

## 1. 声明文件语法

声明文件以 `.mod` 为后缀，语法是 Java 模块声明的一个稳定子集（UTF-8，支持
`// 行注释` 与 `/* 块注释 */`）：

```text
module com.example.app {
    requires          com.example.core;      // 直接依赖
    requires transitive com.example.api;     // 传递依赖（导出可见性向外传播）
    requires static     com.example.maybe;   // 可选依赖，缺失不阻断解析
    exports com.example.app.ui;              // 导出包
    provides com.example.api.Greeter         // 服务提供
        with com.example.app.HelloGreeter;
    uses com.example.api.Greeter;            // 服务消费
}
```

- 名称为点分标识符（`[A-Za-z_$][A-Za-z0-9_$$]*` 段，以 `.` 分隔）。
- 每条指令以 `;` 结束；解析失败会报告 `文件:行:列`（行列从 1 开始）与原因。

## 2. 判定规则（确定、可解释）

所有判定都**与解析顺序、并发执行无关**：模块/包/服务集合按字典序处理，
诊断按 `(severity, file, line, column, code, message)` 稳定排序。

### 2.1 依赖与导出可见性

- `requires` 构成有向边；缺失的非可选依赖报 `REQUIRES_MISSING`（ERROR），
  缺失的可选依赖报 `REQUIRES_OPTIONAL_MISSING`（INFO），不阻断解析。
- 可见性规则（JPMS 语义）：模块 M 可读它直接 `requires` 的每个模块；
  当 M 可读 P，而 P `requires transitive N` 时，M 也可读 N。普通（非传递）
  依赖的内部依赖不向 M 暴露。
- 直接依赖、传递依赖、可读模块分别在结果中给出；传递/可读闭包在 SCC 缩点 DAG
  上以 O(V+E) 的拓扑动态规划计算。

### 2.2 循环依赖

使用 Tarjan 强连通分量算法；多于一个节点的 SCC（或自环）即一个环，按环中最小
模块名给出一条 `CIRCULAR_DEPENDENCY`（ERROR），环路径形如 `a -> b -> c -> a`，
每个环只报一次。

### 2.3 导出包冲突

同一包被多个模块导出时：

- **归属规则**：模块名**字典序最小**者确定性胜出，成为该包 owner；
- 其余每个导出者产生一条 `EXPORT_CONFLICT`（ERROR，定位到其 `exports` 行列），
  消息中明确列出全部导出者与判定的 owner。
- 恰好一个导出者时静默成为 owner，无诊断。

同名模块（不同文件声明同一 module 名）同理：文件路径字典序最小者保留，
其余声明被忽略并报 `DUPLICATE_MODULE`（ERROR）。

### 2.4 服务绑定

- 消费方（`uses`）声明的接口必须存在提供者（`provides`）。
  - 无提供者且存在缺失的非可选依赖：`SERVICE_NO_PROVIDER`（ERROR）；
  - 无提供者且所有缺失依赖都是 `static`（可选）：
    `SERVICE_OPTIONAL_MISSING`（INFO），不阻断整体解析。
- 多个提供者：**模块名字典序最小者确定性胜出**，并产生
  `SERVICE_MULTIPLE_PROVIDERS`（WARNING）列出所有候选与被选提供者。
- 绑定结果（消费者、全部提供者、被选提供者）在快照 `serviceBindings` 中返回。

## 3. 诊断码一览

| code | 级别 | 含义 |
| --- | --- | --- |
| `PARSE_ERROR` | ERROR | 语法错误（记录在 `parseFailures`，含 `file:line:column`） |
| `DUPLICATE_MODULE` | ERROR | 多个文件声明同一模块名 |
| `REQUIRES_MISSING` | ERROR | 非可选依赖缺失 |
| `REQUIRES_OPTIONAL_MISSING` | INFO | 可选依赖缺失，不阻断 |
| `CIRCULAR_DEPENDENCY` | ERROR | 依赖成环 |
| `EXPORT_CONFLICT` | ERROR | 同包被多模块导出，最小模块名获胜 |
| `SERVICE_NO_PROVIDER` | ERROR | 消费的服务无提供者 |
| `SERVICE_OPTIONAL_MISSING` | INFO | 服务无提供者但依赖全部可选 |
| `SERVICE_MULTIPLE_PROVIDERS` | WARNING | 多提供者，最小模块名被选 |

## 4. 增量解析

- 文件内容以 SHA-256 判变；内容未变的更新直接返回当前快照（不产生新版本）。
- 单文件变更时，仅重算**受影响模块**：变更模块自身、与其同环的模块、
  其反向依赖闭包、同导出包的其他导出者、同一服务接口的其它提供/消费者。
  未受影响模块直接复用旧的 `ResolvedModule`（对象级稳定）。
- 图级结论（环、归属、绑定、诊断）每次做全局**确定性**重算，因此结论不会随
  解析顺序或并发漂移。

## 5. 持久化与崩溃恢复

状态目录（默认 `./target/resolver-state`，可用 `moduleresolver.state-dir` 配置）：

- `blobs/<sha256>`：内容寻址的原始声明文件（CAS，相同内容只存一份）；
- `snapshot-v<N>.json`：不可变完整快照；
- `CURRENT`：指向最近一次已提交快照。

提交流程是崩溃安全的：先写所有 blob → 将快照写到临时文件并 `fsync` →
原子 rename 为 `snapshot-vN.json` → 再原子切换 `CURRENT`。崩溃只可能留下：

- 未被引用的 blob（无害垃圾）；
- `*.tmp-*` 临时文件（下次提交自动清理，恢复时忽略）。

恢复时读取 `CURRENT` 指向的快照（无指针则取版本号最大者），并按 blob 哈希
校验内容（哈希不符判定为崩溃残留并在下一提交重写）。**不会丢失已提交结论，
也不会读到半写入快照**。默认保留最近 10 个快照文件。

## 6. 并发模型

- 更新通过单一 `updateLock` 串行化；读取（`current()`）无锁，基于
  `volatile` 不可变快照，始终观察到某个**完整一致的版本**。
- 每个快照带单调递增 `version` 与有界（50 条）的提交历史 `history`
  （版本、时间、变更文件、触发器），全过程可追溯。
- 取消：更新可传 `CancellationToken`；在模块批次之间协作检查，取消后该次
  更新被丢弃，**上一已提交快照仍是唯一可见且已持久化的状态**。
- 并发更新同一文件：
  - 独立调用由锁串行化，每次写入产生一个完整新版本并记入历史；
  - 使用原子批量接口 `upsertFiles(List<FileUpdate>)` 时，若同一批次含同一文件的
    多个写入，按**内容字典序最小**确定性胜出，其余写入在历史中以
    `rejected:concurrent-write-lost-deterministic-tiebreak` 留痕。

## 7. 大规模与内存

- 有界内存：仅保留当前文件内容、当前图/快照、50 条历史；旧快照落盘后即可回收。
- 图分析（SCC、可读性闭包）为 O(V+E)；增量更新只重算受影响模块。
- 原始内容存 CAS blob，快照仅引用哈希，单快照提交为 O(变更)。
- 参考性能（1000 个模块的线性依赖链，本地测试机）：
  批量装载约 0.6 s；单文件增量约 0.2 s。
  见 `LargeScaleTest`（阈值分别为 20 s / 5 s，避免 CI 抖动误报）。

## 8. 使用方式

### 8.1 Java API

```java
IncrementalModuleSystem system =
        new IncrementalModuleSystem(Path.of("./target/resolver-state"));
system.recover();                         // 从最近提交状态恢复

system.upsertFile("/modules/app.mod", """
        module com.example.app {
            requires com.example.core;
            uses com.example.api.Greeter;
        }
        """);

ResolutionSnapshot snapshot = system.current();
snapshot.hasErrors();                     // 是否存在 ERROR
snapshot.diagnostics();                   // 稳定排序的全部诊断
snapshot.packageOwners();                 // 包 -> owner 模块
snapshot.serviceBindings();               // 接口 -> 绑定结果
snapshot.modules().get("com.example.app") // 直接/传递/可读依赖
        .directDependencies();

system.syncDirectory(Path.of("/modules"), null); // 目录增量扫描
system.removeFile("/modules/app.mod");
```

### 8.2 HTTP（随 Spring Boot 启动）

- `GET  /api/resolver/snapshot`：读取当前完整快照（无锁一致读）；
- `POST /api/resolver/files`：`{"file": "...", "content": "..."}` 增量更新。

启动时 `ResolverController` 自动调用 `recover()`。

### 8.3 命令行（本地可复现）

```bash
mvn -q -DskipTests package
java -cp target/module-resolver-java-1.0-SNAPSHOT.jar \
     -Dloader.main=com.github.guoyyit.moduleresolverjava.ResolverCli \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     ./modules ./target/resolver-state
```

退出码：`0` 无 ERROR；`1` 存在 ERROR；`2` 参数错误。

## 9. 构建与测试

```bash
mvn test
```

测试覆盖：解析与位置报错（`ModuleDeclarationParserTest`）、可读性与循环检测
（`ModuleGraphTest`）、导出冲突（`ExportOwnershipTest`）、服务绑定
（`ServiceBindingResolverTest`）、端到端/增量/取消/可选依赖/恢复
（`IncrementalModuleSystemTest`）、目录同步（`SyncDirectoryTest`）、
并发一致与确定性（`ConcurrencyTest`）、半写入崩溃恢复与历史有界
（`SnapshotStoreTest`）、大规模（`LargeScaleTest`）、HTTP（`ResolverControllerTest`）。

## 10. 兼容性边界

- 需要 JDK 17+；仅依赖 Spring Boot Web 与 Jackson（随 starter 提供），无数据库/
  消息中间件，状态完全落本地文件系统。
- 解析语法是 JPMS 的**子集**：不支持 `open module`、`opens`、`requires` 的
  原生模块名形式、`to ... exports` 限定导出、版本子句等；遇到未知关键字或非法
  名称直接给出带位置的解析错误。
- 导出冲突/多提供者的“字典序最小胜出”是本库的**既定确定性规则**，不代表 Java
  运行时的类加载选择；它用于在无外部配置时给出可解释、可复现的归属。
- 并发隔离粒度是“单进程单状态目录”；多进程共享同一状态目录不在支持范围内
  （文件级原子提交保证不会读到半截状态，但不提供多进程写串行化）。
