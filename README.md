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

---

## 1.1 批量访问（Batch）

业务方一次拉取多条客户记录时，使用批量接口，由网关在**一个批次**内统一识别、
判定与转换，避免外层循环导致的版本对不齐与失败难定位。单条接口
`POST /api/v1/data/access` 完全保持兼容，行为不变。

- **端点**：`POST /api/v1/data/batch`
- **入参**：整批共用同一个 `callerId`、`purpose`、可选 `policyVersion`；
  `records` 为数组，每个元素是一条业务记录（JSON 对象，可含多层数组/对象）。

```bash
curl -s -X POST http://localhost:8080/api/v1/data/batch \
  -H 'Content-Type: application/json' \
  -d '{
    "callerId": "caller-analytics",
    "purpose": "analytics",
    "records": [
      { "name": "Alice", "email": "dup@example.com",
        "contacts": [ { "email": "dup@example.com" } ] },
      { "name": "Bob", "email": "bob@example.com",
        "contacts": [ { "email": "dup@example.com" } ] }
    ]
  }'
```

返回（节选）：`data` 是与 `records` 一一对应的数组；`fieldResults` 每条都带
`recordIndex`（记录序号，从 0 开始）与记录内字段路径 `field.path`。

```json
{
  "auditId": "....",
  "policyVersion": "policy-v1",
  "classificationVersion": "classification-v1",
  "data": [
    { "name": "A***e", "email": "tok_a1...", "contacts": [ { "email": "tok_c6..." } ] },
    { "name": "B*b",   "email": "tok_b2...", "contacts": [ { "email": "tok_c6..." } ] }
  ],
  "fieldResults": [
    { "recordIndex": 0, "field": { "path": "name", "level": "L1", "transform": "MASK", "reversible": false, "transformed": true } },
    { "recordIndex": 0, "field": { "path": "contacts[].email", "level": "L2", "transform": "TOKENIZE", "reversible": false, "transformed": true } }
  ]
}
```

### 整批失败语义（全有或全无，all-or-nothing）

- 任一记录不通过，**整批拒绝**：HTTP 错误响应，`data` 不存在，绝不返回部分结果，
  也不会悄悄跳过失败记录继续处理其余记录。
- 失败被**定位到具体记录序号与字段位置**：错误体 `details.recordIndex` 与
  `details.fieldPath`，错误 `message` 也带 `batch record[i] ... (field=...)`，
  并回填拒绝审计的 `auditId`。
- 拒绝原因彼此可区分（沿用同一套错误码）：
  - 数据本身：`DATA_MALFORMED`（分级字段为 null/对象/非字符串、记录不是对象、数组元素非标量）、
    `FIELD_MISSING`、`CLASSIFICATION_UNDEFINED`；
  - 权限/用途：`UNAUTHORIZED_CALLER`、`PURPOSE_MISMATCH`、`LEVEL_NOT_GRANTED`；
  - 批次/规模限制：`BATCH_SIZE_LIMIT_EXCEEDED`（记录数或整批节点数超限）、
    `BATCH_RECORD_DEPTH_EXCEEDED`（某条记录过深）、`TIMEOUT_EXCEEDED`。

```json
{
  "error": "sensitive_data_gateway_error",
  "code": "DATA_MALFORMED",
  "message": "batch record[1]: classified field has null value: email (field=email)",
  "auditId": "....",
  "details": { "recordIndex": 1, "fieldPath": "email" }
}
```

### 顺序、层级与令牌稳定性

- 输出 `data` 与输入 `records` **同序**；每条记录的对象层级、数组元素个数与原有
  JSON 类型族保持不变（字符串标量→字符串；命分级的键若承载标量数组，则逐元素
  转换并保持数组长度与顺序）。
- `fieldResults` 按 `recordIndex` 归组、按遍历顺序排列，`field.path` 是**记录内
  规范路径**（不含记录序号；数组元素统一记为 `[]`，如 `contacts[].email`），可
  直接定位到该记录内字段。
- **令牌跨记录、跨数组位置稳定**：令牌密钥由“固定根盐 + 策略版本 + 记录内规范
  字段路径”派生，与记录序号、数组下标、排序位置无关。因此同一原始值在**同一
  字段、同一策略下**，无论出现在哪条记录或数组哪个位置，令牌都相同（如上例两条
  记录的 `contacts[0].email` 同为 `dup@example.com` → 同一 `tok_c6...`）；不同
  字段同值、或不同策略版本，令牌不同。
- 所有不可逆转换（`MASK`/`REDACT`/`TOKENIZE`）在 `fieldResults[].field.reversible=false`
  明确标注；`NONE` 为可逆原值透传。

### 版本一致性与容量限制

见下文 [第 5 节](#5-版本兼容语义)、[第 7 节](#7-配额深度耗时与异常归一化)：
整批在请求开始时**一次性固定**策略与分级版本快照，期间发布/授权变更不影响在途
批次；容量上限在**任何转换之前**预检，超限直接拒绝，不产生半成品或残留资源。

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

**稳定性**：令牌化密钥由“固定本地根盐 + 策略版本 + 字段路径”派生。
因此**同一原始值在同一策略版本、同一字段下输出恒定**；
策略版本变更后令牌随之改变（不可跨版本关联）；不同字段同值也不同令牌。
所有不可逆转换在 `fieldResults[].reversible=false` 明确标注。
转换不改变 JSON 层级，输出与原字段保持同一类型族（字符串→字符串）。

## 5. 版本兼容语义

- 注册表（`InMemoryPolicyRegistry` / `InMemoryClassificationRegistry`）保存所有已发布
  版本的**不可变快照**；`latest` 为 `volatile` 引用，发布时“先存完整快照，再切换引用”，
  读取方不会观察到半更新状态。
- 请求不指定 `policyVersion` 时，始终按**最新版本**处理。
- **“已发布但不是当前版本”与“从未发布的版本”给出可区分的拒绝**：
  - 指定版本曾发布但已不是当前版本：`POLICY_VERSION_FALLBACK_REJECTED`（403），
    不会静默回退，也**不会拿当前版本代替**；
  - 指定版本在注册表中从未发布过：`POLICY_NOT_FOUND`（404）。
  两种拒绝都写审计（记录请求指定的版本），单条与批量语义一致。
- **批量版本一致性**：批次在开始时一次性解析并固定策略 + 分级版本快照，随后整批
  所有记录都基于这同一个不可变快照判定。即使批次进行中有新策略发布或授权变更，
  也不会出现一批内混用新旧规则，响应中的版本字段与审计固化的版本始终一致。
- **撤销立即生效（不依赖缓存）**：发布“移除某调用方授权”的新版本后，后续单条或
  批量请求立即被拒；判定直接基于请求开始时取得的不可变快照，不存在陈旧缓存或
  “已撤销权限因缓存延续而放行”的窗口。
- **历史可解释**：审计记录固化“发生时所用的策略版本与分级版本”，可随时按该版本复盘；
  试图用旧版本重放的请求也会留下审计痕迹。

## 6. 审计与数据保留语义

- 每个请求（放行或拒绝）都写一条 `AuditRecord`：调用方、用途、策略/分级版本、
  是否允许、拒绝原因、逐字段判定依据、原始负载的 SHA-256 指纹（不保存原始负载本身）。
- **批量请求写一条整批审计**：`batch=true`、`recordCount` 为整批记录数；成功时
  `batchFields` 携带每条记录每个敏感字段的处理说明（带 `recordIndex` 与记录内
  路径），可按记录还原；失败时记录拒绝原因，并在错误体回填该审计 `auditId`。
  整批只写一条审计，保证响应版本与审计版本一一对应。
- 日志同时打印**原始输入**与**判定依据**（见 `DefaultSensitiveDataGateway` 的
  `ACCESS request raw ...` 与引擎的 `FIELD decision ...`）。
- **审计写失败 → fail-closed**：归一化为 `AUDIT_WRITE_FAILED`（500）。
  已判定放行的数据不会在审计缺失时返回；已判定拒绝的请求也不会因审计异常而被放行。
- **保留策略**：`gateway.audit-retention-max-records`（默认 10000）。达到上限后
  拒绝新写入而不是覆盖/丢弃最旧记录，强制人工处理，保证痕迹连续。
- 对外错误体不含堆栈与底层异常消息（如磁盘故障细节不外泄）。

## 7. 配额、深度、耗时与异常归一化

`application.properties`：

```properties
gateway.max-payload-nodes=1000        # 单条负载节点总数上限
gateway.max-depth=10                  # 单条嵌套深度上限
gateway.processing-timeout-ms=2000    # 单次/整批处理耗时上限（毫秒，预检与处理共享）
gateway.max-batch-records=200         # 单批最大记录数
gateway.max-batch-total-nodes=50000   # 整批（所有记录合计）最大节点数
gateway.max-batch-record-depth=10     # 批量中单条记录允许的最大嵌套深度
gateway.audit-retention-max-records=10000
```

- 遍历**前**先做深度/规模静态检查：单条超限分别返回 `DEPTH_LIMIT_EXCEEDED`、
  `SIZE_LIMIT_EXCEEDED`（400），因此不会产生“处理了一半”的结果。
- **批量预检在任何转换之前完成**：先查记录数（`BATCH_SIZE_LIMIT_EXCEEDED`），再
  逐记录做对象契约、单条深度（`BATCH_RECORD_DEPTH_EXCEEDED`）、整批合计节点数
  （`BATCH_SIZE_LIMIT_EXCEEDED`）与必备字段检查；随后才按序处理。预检或处理中
  超时返回 `TIMEOUT_EXCEEDED`（400）。整批失败时不返回任何已处理记录，全部处理
  均为内存内构建，失败即丢弃，不残留部分结果或资源。
- 非法 JSON：`BAD_REQUEST`（400）；空批次或缺少调用方/用途/records 同样为 `BAD_REQUEST`。
- 任何未预期的底层异常统一归一化为 `INTERNAL_ERROR`（500），不暴露内部细节；
  审计存储等下游故障为 `AUDIT_WRITE_FAILED` / `DOWNSTREAM_FAILURE`。
- 全内存处理，拒绝路径直接抛异常，不返回部分结果，无资源泄漏。

### 对外错误码一览

`BAD_REQUEST, DATA_MALFORMED, FIELD_MISSING, CLASSIFICATION_UNDEFINED,
UNAUTHORIZED_CALLER, PURPOSE_MISMATCH, LEVEL_NOT_GRANTED, POLICY_NOT_FOUND,
POLICY_VERSION_FALLBACK_REJECTED, DEPTH_LIMIT_EXCEEDED, SIZE_LIMIT_EXCEEDED,
BATCH_SIZE_LIMIT_EXCEEDED, BATCH_RECORD_DEPTH_EXCEEDED, TIMEOUT_EXCEEDED,
AUDIT_WRITE_FAILED, DOWNSTREAM_FAILURE, INTERNAL_ERROR`

> 版本类拒绝的区分：请求一个**从未发布**的版本返回 `POLICY_NOT_FOUND`（404）；
> 请求一个**已发布但不是当前**的版本返回 `POLICY_VERSION_FALLBACK_REJECTED`（403）。

## 8. 并发一致性

- 版本快照不可变 + 引用原子切换：并发“读 + 发布”时，每个单条请求或每个批次要么
  完整使用旧版本、要么完整使用新版本，响应中的版本字段与数据/审计始终自洽。
  批量在请求开始时取一次快照并贯穿预检与全部记录，杜绝一批内混用。
- 审计追加串行化（加锁），顺序可见。
- `ConcurrencyTests` 在 8 读线程 × 50 轮与 1 个交替发布线程下验证：
  不出现半更新异常、不会在已撤销版本下陈旧放行。
- `BatchVersionConsistencyTests` 在 8 批处理线程 × 40 轮与交替发布线程下验证：
  每个批次整体落在同一个版本（v1 整批放行或 v3 整批拒绝），无部分结果，审计
  固化的版本/记录数与响应一致。

## 9. 测试与本地验证

```bash
mvn test
```

覆盖场景（40+ 用例，全部不依赖外部服务/真实凭据）：

- `BasicAccessTests`：放行+掩码/令牌化稳定性、未授权、用途不符、等级越权（原因可区分）。
- `ClassificationAndMalformedTests`：分级未定义、必备字段缺失、嵌套层级保持、
  嵌套结构异常、标量为 null、负载非对象。
- `PolicyVersionTests`：已发布非当前版本被拒、从未发布版本为 POLICY_NOT_FOUND、
  新版本生效、授权撤销生效、历史审计按原版本解释。
- `BatchAccessTests`：批量正常放行、顺序/层级/数组长度/类型保持、跨记录与数组内
  同值令牌稳定、记录内路径定位、坏数据/越权/等级越权/缺字段/非对象/记录数超限/
  单条深度超限/整批节点超限/空批次的全有或全无拒绝。
- `BatchVersionConsistencyTests`：并发发布时整批版本一致、已发布非当前与从未发布
  版本的区分（含审计）、撤销授权对批量立即生效。
- `BatchAuditFailureTests`：批量放行与拒绝两条路径审计写失败均 fail-closed。
- `ConcurrencyTests`：并发读取/发布一致性与撤销即时生效。
- `AuditFailureTests`：单条放行与拒绝路径下审计写失败均 fail-closed，且不泄漏内部细节。
- `LimitTests`：深度、规模上限与非法 JSON。
- `TransformerTests`：掩码/脱敏/令牌化规则、稳定性、版本与字段绑定、可逆标注。

### 本地启动与手动验证

```bash
mvn spring-boot:run                      # http://localhost:8080

# 单条（兼容）
curl -s -X POST http://localhost:8080/api/v1/data/access \
  -H 'Content-Type: application/json' \
  -d '{"callerId":"caller-analytics","purpose":"analytics",
       "data":{"name":"Alice","email":"alice@example.com"}}'

# 批量
curl -s -X POST http://localhost:8080/api/v1/data/batch \
  -H 'Content-Type: application/json' \
  -d '{"callerId":"caller-analytics","purpose":"analytics",
       "records":[{"name":"Alice","email":"a@x.com"},{"name":"Bob","email":"b@x.com"}]}'

# 查看审计（单条/批量）
curl -s http://localhost:8080/api/v1/audit
```

## 10. 主要代码结构

```
domain/            错误码、异常（含记录/字段定位）、等级、转换类型、单条/批量请求结果审计模型
classification/     分级定义、版本注册表（原子发布）、识别服务
policy/             策略/Grant 定义、版本注册表、判定服务、版本快照解析器（区分未发布/非当前）
transform/          掩码/脱敏/令牌化转换器与工厂（令牌按记录内规范字段路径派生）
audit/              审计存储（含保留上限）与审计服务（单条/批量，fail-closed）
engine/             嵌套遍历、识别+判定+转换、批量预检、深度/规模/耗时强制
service/            单条与批量网关门面：版本解析 → 处理 → 审计 编排（批量全有或全无）
web/                REST 入口（/data/access 单条、/data/batch 批量）与归一化异常处理
seed/               启动时加载 classpath 种子版本
config/             GatewayProperties 单条与批量可配置上限
```
