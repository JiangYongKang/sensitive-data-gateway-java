# 敏感数据访问网关（Sensitive Data Gateway）

一个**本地可运行**的 Spring Boot 服务：同一批结构化/嵌套数据，在不同**调用方**、
不同**访问用途**与不同**策略版本**下，谁能看到什么字段、以什么形态看到，必须
**可预期、可审计、可复现**。

- 不依赖任何外部服务或真实凭据（分级/策略为 classpath 种子文件，令牌化使用本地 HMAC）。
- 默认失败闭合（fail-closed）：未授权、用途不符、策略缺失、审计失败等一律拒绝，
  绝不默认放行，也绝不以空结果掩盖。

---

## 1. 快速开始

```bash
# 编译 + 全量测试（无需数据库/网络/凭据）
mvn test

# 本地启动（端口 8080）
mvn spring-boot:run
```

发起一次访问：

```bash
curl -s -X POST http://localhost:8080/api/v1/data/access \
  -H 'Content-Type: application/json' \
  -d '{
    "callerId": "caller-analytics",
    "purpose": "analytics",
    "data": { "name": "Alice", "email": "alice@example.com", "note": "hi" }
  }'
```

返回（`name` 掩码、`email` 令牌化、普通字段 `note` 原样透传）：

```json
{
  "auditId": "....",
  "policyVersion": "policy-v1",
  "classificationVersion": "classification-v1",
  "data": { "name": "A***e", "email": "tok_....", "note": "hi" },
  "fieldResults": [
    { "path": "name",  "level": "L1", "transform": "MASK",     "reversible": false, "transformed": true },
    { "path": "email", "level": "L2", "transform": "TOKENIZE", "reversible": false, "transformed": true }
  ]
}
```

查询审计：`GET http://localhost:8080/api/v1/audit`

### 1.1 批量访问

业务方一次拉取多条客户记录时，使用批量入口；原单条入口 `/api/v1/data/access`
保持完全兼容，语义不变。

```bash
curl -s -X POST http://localhost:8080/api/v1/data/access/batch \
  -H 'Content-Type: application/json' \
  -d '{
    "callerId": "caller-analytics",
    "purpose": "analytics",
    "records": [
      { "name": "Alice", "email": "alice@example.com", "note": "one" },
      { "name": "Bob",   "email": "bob@example.com",   "note": "two",
        "contacts": { "email": ["alice@example.com", "alice@example.com"] } }
    ]
  }'
```

- 整批共用同一个 `callerId`、`purpose`，以及可选的 `policyVersion` /
  `classificationVersion`（省略即固定为当前最新版本）。
- 每条 `records[i]` 是一个 JSON 对象，可含多层数组与对象。

---

## 2. 分级模型（Classification）

定义见 `src/main/resources/seed/classification-v1.json`，对应
`ClassificationDefinition(version, fieldLevels, unclassified, required)`。

- **等级**：`L1 < L2 < L3 < L4`，数字越大越敏感（如 name=L1, email/phone=L2,
  address=L3, ssn/bankAccount/passportNo=L4）。
- **字段键**：支持精确规范路径（`person.passportNo`）与裸字段名（`ssn`，任意层级匹配）。
  匹配顺序：精确路径 → 裸字段名。未列入的字段视为**普通字段，原样透传**。
- **分级未定义不得静默跳过**：`unclassified` 列出“已知敏感但尚未定等级”的字段
  （如 `taxId`）。数据中一旦出现，整个请求以 `CLASSIFICATION_UNDEFINED` 拒绝。
- **字段缺失不得静默跳过**：`required` 中的字段必须出现（支持精确路径或裸字段名），
  缺失以 `FIELD_MISSING` 拒绝。
- **数据格式异常**：命中分级的键必须是字符串标量（`NONE` 放行时可为任意标量）。
  值为 `null`、对象/数组、或需要转换却不是字符串，均以 `DATA_MALFORMED` 拒绝，
  不会递归穿透成“普通子树”，也不做强转。

## 3. 策略模型（Policy）

定义见 `src/main/resources/seed/policy-v1.json`，对应
`AccessPolicy(version, grants)`，每个 `Grant(purposes, maxLevel, transforms)`。

判定按字段逐字段进行，顺序如下，原因彼此可区分：

| 顺序 | 情况 | 错误码 | HTTP |
|---|---|---|---|
| 1 | 没有任何已发布策略 | `POLICY_NOT_FOUND` | 404 |
| 2 | 调用方在策略中无授权 | `UNAUTHORIZED_CALLER` | 403 |
| 3 | 授权用途不含请求用途 | `PURPOSE_MISMATCH` | 403 |
| 4 | 字段等级高于授权 `maxLevel` | `LEVEL_NOT_GRANTED` | 403 |
| 5 | 其余 | 放行，按等级取转换方式 | 200 |

策略未列出的调用方就是未授权；没有“默认允许”。判定依据（策略版本、调用方、
用途、字段等级、授权上限、最终转换）会写入日志和审计记录。

## 4. 脱敏 / 掩码 / 令牌化规则

由 `DefaultTransformerFactory` 产生，全部为本地实现：

| 类型 | 规则 | 可逆 |
|---|---|---|
| `NONE` | 原样返回 | 是（`reversible=true`） |
| `MASK` | 保留首末字符，中间替换为 `*`；长度 ≤ 2 全掩码 | 否 |
| `REDACT` | 常量 `***REDACTED***` | 否 |
| `TOKENIZE` | `tok_` + HMAC-SHA256 摘要前 32 个十六进制字符 | 否 |

**稳定性**：令牌化密钥由“固定本地根盐 + 策略版本 + 字段身份（裸字段名）”派生。
因此**同一原始值在同一策略版本、同一字段下输出恒定**，与记录序号、嵌套层级、
数组位置无关；策略版本变更后令牌随之改变（不可跨版本关联）；不同字段同值也不同令牌。
敏感字段也可以是“标量数组”（如 `"email": ["a@x.com","a@x.com"]`），逐元素转换、
数组元素个数不变，各元素同值同令牌。所有不可逆转换在
`fieldResults[].reversible=false` 明确标注。
转换不改变 JSON 层级，输出与原字段保持同一类型族（字符串→字符串、数组→数组）。

## 5. 版本兼容语义

- 注册表（`InMemoryPolicyRegistry` / `InMemoryClassificationRegistry`）保存所有已发布
  版本的**不可变快照**；`latest` 为 `volatile` 引用，发布时“先存完整快照，再切换引用”，
  读取方不会观察到半更新状态。
- 请求不指定 `policyVersion` / `classificationVersion` 时，始终按**最新版本**处理。
- **显式回退到已发布旧版本被明确拒绝**：请求指定的版本已发布但不等于最新版本时返回
  `POLICY_VERSION_FALLBACK_REJECTED` / `CLASSIFICATION_VERSION_FALLBACK_REJECTED`（403），
  不会静默混用旧规则，也不会拿当前版本代替。
- **从未发布的版本可区分**：请求指定的版本在注册表中不存在时返回
  `POLICY_NOT_FOUND` / `CLASSIFICATION_VERSION_NOT_FOUND`（404），与上一种 403 区分。
- **撤销立即生效**：发布“移除某调用方授权”的新版本后，后续请求立即被拒，
  不存在陈旧缓存放行窗口（引擎基于请求开始时取得的不可变快照判定）。
- **历史可解释**：审计记录固化“发生时所用的策略版本与分级版本”，可随时按该版本复盘；
  试图用旧版本重放的请求也会留下审计痕迹。

## 6. 批量访问语义（all-or-nothing）

批量入口 `POST /api/v1/data/access/batch`，与单条入口共用同一套识别、判定、转换逻辑
（见 `RecordProcessor`），因此两条入口的结果永远一致。

### 6.1 输入

```json
{
  "callerId": "caller-analytics",
  "purpose": "analytics",
  "policyVersion": "policy-v1",            // 可空：省略=当前最新
  "classificationVersion": "classification-v1", // 可空：省略=当前最新
  "records": [ { ...记录1... }, { ...记录2... } ]
}
```

### 6.2 成功输出（整批全放行才返回 200）

```json
{
  "auditId": "....",
  "policyVersion": "policy-v1",
  "classificationVersion": "classification-v1",
  "recordCount": 2,
  "records": [
    { "index": 0, "data": { ...保持原层级/数组个数/类型... },
      "fieldResults": [ { "path": "email", "transform": "TOKENIZE", ... } ] },
    { "index": 1, "data": { ... }, "fieldResults": [ ... ] }
  ]
}
```

- **按记录还原**：输出记录顺序、数量与输入一致；每条 `data` 保持原层级、数组元素个数与
  类型族；`fieldResults[].path` 是**该记录内的相对路径**（如 `contacts.email`）。
- **令牌跨记录稳定**：同一原始值在同一字段、同一策略版本下，无论出现在哪个记录、
  哪个层级或数组位置，令牌都相同；不同字段、不同策略仍隔离；不可逆转换 `reversible=false`。

### 6.3 整批失败（任一记录不通过 → 不返回任何已处理数据）

整批按**全有或全无**处理：任何一条记录不通过，整批返回 HTTP 错误，**不返回任何
已处理的敏感数据**，也不会悄悄跳过坏记录。错误体带定位与分类：

```json
{
  "error": "sensitive_data_gateway_error",
  "code": "LEVEL_NOT_GRANTED",
  "message": "access denied for field address ... (at batch record index 1, field address)",
  "recordIndex": 1,
  "fieldPath": "address",
  "category": "AUTHORIZATION"
}
```

| category | 含义 | 典型 code |
|---|---|---|
| `DATA` | 数据本身有问题 | `DATA_MALFORMED, FIELD_MISSING, CLASSIFICATION_UNDEFINED, BAD_REQUEST` |
| `AUTHORIZATION` | 权限或用途不通过 | `UNAUTHORIZED_CALLER, PURPOSE_MISMATCH, LEVEL_NOT_GRANTED` |
| `LIMIT` | 批次/深度/规模/耗时限制 | `BATCH_SIZE_LIMIT_EXCEEDED, RECORD_SIZE_LIMIT_EXCEEDED, DEPTH_LIMIT_EXCEEDED, BATCH_TIMEOUT_EXCEEDED` |
| `VERSION` | 版本要求问题 | `POLICY_NOT_FOUND, POLICY_VERSION_FALLBACK_REJECTED, CLASSIFICATION_VERSION_NOT_FOUND, CLASSIFICATION_VERSION_FALLBACK_REJECTED` |
| `INTERNAL` | 下游/内部 | `AUDIT_WRITE_FAILED, DOWNSTREAM_FAILURE, INTERNAL_ERROR` |

- `recordIndex` 从 0 开始；批次级原因（如批大小超限）`recordIndex` 为空。
- `fieldPath` 定位到该失败记录内的字段位置。

### 6.4 版本一致性

- 整批在开始时**一次性解析并固定**策略与分级两个不可变快照（`VersionResolver`），
  处理期间即使发生策略发布、分级发布或授权变更，本批也只按开始时的版本处理；
  响应 `policyVersion` / `classificationVersion` 与审计记录、批内每条数据完全自洽，
  不会出现一批混用新旧规则。
- 显式要求**已发布但不是当前**的版本：`*_FALLBACK_REJECTED`（403），绝不拿当前版本代替。
- 显式要求**从未发布过**的版本：`*_NOT_FOUND`（404），与上一种可区分。
- 两类版本拒绝都会写一条批次审计。授权撤销通过发布新版本生效，批内不存在陈旧缓存放行。

### 6.5 容量与耗时限制（处理前拒绝，无部分结果）

`application.properties`：

```properties
gateway.max-batch-records=100          # 整批最大记录条数
gateway.max-batch-record-nodes=1000    # 单条记录最大节点数
gateway.max-depth=10                   # 单条记录最大嵌套深度（与单条共用）
gateway.batch-timeout-ms=10000         # 整批处理耗时上限（毫秒，全批共享一个起始时刻）
```

- 超限都在**转换任何字段之前**拒绝：批大小 `BATCH_SIZE_LIMIT_EXCEEDED`、
  单条规模 `RECORD_SIZE_LIMIT_EXCEEDED`、深度 `DEPTH_LIMIT_EXCEEDED`（后两者带记录序号）；
  处理中超时 `BATCH_TIMEOUT_EXCEEDED`。
- 拒绝路径直接抛出异常，不构造部分响应；仅在成功/失败落定后写**一条**批次审计，
  不留部分结果或资源。

### 6.6 批量审计

每批（放行或拒绝）只写一条 `AuditRecord`：`batch=true`、`recordCount` 为批内记录数；
成功时字段说明路径带 `records[i].` 前缀（如 `records[1].contacts.email`），可据此把
每个字段还原到具体记录；失败时携带 `recordIndex` 与 `fieldPath`。审计同样只保存原始
负载的 SHA-256 指纹，不保存原始负载。

## 7. 审计与数据保留语义

- 每个请求（放行或拒绝）都写一条 `AuditRecord`：调用方、用途、策略/分级版本、
  是否允许、拒绝原因、逐字段判定依据、原始负载的 SHA-256 指纹（不保存原始负载本身）。
- 日志同时打印**原始输入**与**判定依据**（见 `DefaultSensitiveDataGateway` 的
  `ACCESS request raw ...` 与引擎的 `FIELD decision ...`）。
- **审计写失败 → fail-closed**：归一化为 `AUDIT_WRITE_FAILED`（500）。
  已判定放行的数据不会在审计缺失时返回；已判定拒绝的请求也不会因审计异常而被放行。
- **保留策略**：`gateway.audit-retention-max-records`（默认 10000）。达到上限后
  拒绝新写入而不是覆盖/丢弃最旧记录，强制人工处理，保证痕迹连续。
- 对外错误体不含堆栈与底层异常消息（如磁盘故障细节不外泄）。

## 8. 配额、深度、耗时与异常归一化

`application.properties`：

```properties
gateway.max-payload-nodes=1000        # 单条负载节点总数上限
gateway.max-depth=10                  # 嵌套深度上限（单条/批量共用）
gateway.processing-timeout-ms=2000    # 单条处理耗时上限（毫秒）
gateway.max-batch-records=100         # 批量最大记录条数
gateway.max-batch-record-nodes=1000   # 批量单条记录节点数上限
gateway.batch-timeout-ms=10000        # 整批处理耗时上限（毫秒）
gateway.audit-retention-max-records=10000
```

- 遍历**前**先做深度/规模静态检查：单条超限返回 `DEPTH_LIMIT_EXCEEDED`、
  `SIZE_LIMIT_EXCEEDED`（400）；批量批次级超限返回 `BATCH_SIZE_LIMIT_EXCEEDED`，
  单条深度/规模超限返回 `DEPTH_LIMIT_EXCEEDED` / `RECORD_SIZE_LIMIT_EXCEEDED`
  （均带记录序号），因此不会产生“处理了一半”的结果。
- 处理过程中检查耗时：单条 `TIMEOUT_EXCEEDED`，整批 `BATCH_TIMEOUT_EXCEEDED`（400）。
- 非法 JSON：`BAD_REQUEST`（400）。
- 任何未预期的底层异常统一归一化为 `INTERNAL_ERROR`（500），不暴露内部细节；
  审计存储等下游故障为 `AUDIT_WRITE_FAILED` / `DOWNSTREAM_FAILURE`。
- 全内存处理，拒绝路径直接抛异常，不返回部分结果，无资源泄漏。

### 对外错误码一览

`BAD_REQUEST, DATA_MALFORMED, FIELD_MISSING, CLASSIFICATION_UNDEFINED,
UNAUTHORIZED_CALLER, PURPOSE_MISMATCH, LEVEL_NOT_GRANTED,
POLICY_NOT_FOUND, POLICY_VERSION_FALLBACK_REJECTED,
CLASSIFICATION_VERSION_NOT_FOUND, CLASSIFICATION_VERSION_FALLBACK_REJECTED,
BATCH_SIZE_LIMIT_EXCEEDED, RECORD_SIZE_LIMIT_EXCEEDED,
DEPTH_LIMIT_EXCEEDED, SIZE_LIMIT_EXCEEDED,
TIMEOUT_EXCEEDED, BATCH_TIMEOUT_EXCEEDED,
AUDIT_WRITE_FAILED, DOWNSTREAM_FAILURE, INTERNAL_ERROR`

## 9. 并发一致性

- **并发一致性**：版本快照不可变 + 引用原子切换：并发“读 + 发布”时，每个请求/每批
  要么完整使用旧版本、要么完整使用新版本，响应中的版本字段与数据始终自洽。
- 审计追加串行化（加锁），顺序可见。
- `ConcurrencyTests` 在 8 读线程 × 50 轮与 1 个交替发布线程下验证：
  不出现半更新异常、不会在已撤销版本下陈旧放行；`BatchConcurrencyTests`
  在同样的交替发布下验证整批版本一致、不出现部分批次结果。

## 10. 测试与本地验证

```bash
mvn test
```

覆盖场景（共 40+ 用例，全部不依赖外部服务/真实凭据）：

- `BasicAccessTests`：放行+掩码/令牌化稳定性、未授权、用途不符、等级越权（原因可区分）。
- `ClassificationAndMalformedTests`：分级未定义、必备字段缺失、嵌套层级保持、
  嵌套结构异常、标量为 null、负载非对象。
- `PolicyVersionTests`：显式回退旧版本被拒、**从未发布版本 404 与已发布非当前 403 可区分**、
  新版本生效、授权撤销生效、历史审计按原版本解释。
- `BatchAccessTests`：批量整批放行、顺序/层级/数组个数/类型保持、
  **数组内同值跨记录跨位置令牌稳定**、不同字段令牌隔离、记录内路径定位、
  坏数据/越权/必备字段缺失/批大小/深度/单条规模/空批/非对象记录的整批拒绝与分类。
- `BatchVersionTests`：批量策略/分级版本的“从未发布 vs 已发布非当前”区分及审计。
- `BatchConcurrencyTests`：并发交替发布时整批版本一致、无部分结果、撤销即时生效。
- `BatchAuditTests`：批次审计按 `records[i].` 路径还原；整批失败带记录序号与字段。
- `BatchTimeoutTests`：整批共享耗时预算，超限 `BATCH_TIMEOUT_EXCEEDED`。
- `ConcurrencyTests`：并发读取/发布一致性与撤销即时生效。
- `AuditFailureTests`：放行与拒绝两条路径下审计写失败均 fail-closed，且不泄漏内部细节。
- `LimitTests`：深度、规模上限与非法 JSON。
- `TransformerTests`：掩码/脱敏/令牌化规则、稳定性、版本与字段绑定、可逆标注。

运行时可在控制台观察日志：原始请求输入、逐字段判定依据、审计记录（含版本与原因）。

## 11. 主要代码结构

```
domain/            错误码、异常、等级、转换类型、单条/批量请求/结果/审计模型
classification/     分级定义、版本注册表（原子发布）、识别服务
policy/             策略/Grant 定义、版本注册表、判定服务、版本快照解析器(VersionResolver)
transform/          掩码/脱敏/令牌化转换器与工厂（字段身份归一化，跨记录/位置稳定）
audit/              审计存储（含保留上限）与审计服务（fail-closed，单条/批次）
engine/             RecordProcessor（共用单条遍历/识别/判定/转换）、
                     单条引擎与 BatchDataProcessingEngine（全有或全无、限额/耗时强制）
service/            网关门面：版本解析 → 处理 → 审计 编排（单条 + 批量）
web/                REST 入口（/data/access、/data/access/batch）与归一化异常处理
seed/               启动时加载 classpath 种子版本
config/             GatewayProperties 可配置上限（含批量上限）
```
