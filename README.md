# 敏感数据访问网关（Sensitive Data Access Gateway）

一个**本地可运行**、不依赖任何外部服务或真实凭据的敏感数据访问网关。同一批数据在
不同**调用方**、不同**访问用途**、不同**策略版本**下的可见结果可预期、可审计、可复现。

- 技术栈：Java 21（在 JDK 21/25 上均可构建）、Spring Boot 3.4.1、Jackson、JUnit 5。
- 运行与测试均为纯内存，无数据库 / 无网络 / 无真实密钥。

---

## 1. 快速开始

```bash
# 构建 + 全量测试（48 个用例，无需外部依赖）
mvn test

# 本地启动（默认端口 8080，启动时从 classpath:gateway/*.json 原子加载策略）
mvn spring-boot:run
```

放行请求示例：

```bash
curl -s -X POST localhost:8080/api/v1/gateway/access \
  -H 'Content-Type: application/json' \
  -d '{
    "callerId":"svc-analytics","purpose":"analytics",
    "payload":{"user":{"name":"Alice","email":"alice@example.com","phone":"13800000000"}}
  }'
```

返回（`NONE` 原样 / `MASK` 掩码 / `TOKENIZE` 令牌化，且不可逆转换显式标注）：

```json
{
  "auditId": "aud-1-...",
  "policyVersion": "2026-09-01-v2",
  "data": {
    "user": { "name": "Alice",
              "email": "tok_d851ef52...",
              "phone": "1*********0" }
  },
  "fieldMarkers": [
    {"fieldPath":"user.email","level":"L2","transform":"TOKENIZE","irreversible":true},
    {"fieldPath":"user.name","level":"L1","transform":"NONE","irreversible":false},
    {"fieldPath":"user.phone","level":"L2","transform":"MASK","irreversible":false}
  ]
}
```

管理接口（仅本地验证用）：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET  | `/api/v1/admin/policies` | 查看在线策略快照（版本清单 + 最新版本） |
| POST | `/api/v1/admin/policies/publish` | 发布单个新版本并原子生效 |
| POST | `/api/v1/admin/policies/reload` | 从磁盘原子重载全部策略（失败保留现状） |
| GET  | `/api/v1/admin/audits` | 读取全部审计记录 |

---

## 2. 分级模型（Sensitivity & Classification）

敏感等级 `SensitivityLevel`：`L1 < L2 < L3 < L4`，rank 越大越敏感。

字段分级在**策略版本**中以路径声明（`resources/gateway/policy-v*.json` 的 `classifications`）：

- 路径语法：点号分隔 Map 键，方括号表示 List 下标，`[*]` 为数组通配。
  例如 `user.contacts[*].phone` 可命中 `user.contacts[0].phone`。
- 每条定义含：`path`、`level`、`required`（是否必填）、`valueTypes`（允许的 Java 值类型简单名，
  如 `String`、`Integer`）。

**字段识别绝不静默跳过**，以下情况分别给出**可区分**的拒绝原因：

| 情况 | 判定码 | HTTP |
| ---- | ------ | ---- |
| 必填敏感字段在数据中缺失 | `FIELD_MISSING` | 422 |
| 必填字段显式为 `null`，或值类型与 `valueTypes` 不符，或标量位置出现嵌套结构 | `MALFORMED_DATA` | 422 |
| 字段名命中内置敏感目录（name/email/phone/idCard/bankCard/password/token/salary…）但当前策略**未定义分级** | `CLASSIFICATION_UNDEFINED` | 422 |
| 当前策略完全没有分级定义 | `POLICY_MISSING` | 403 |
| 嵌套深度超限 | `LIMIT_DEPTH_EXCEEDED` | 422 |

内置敏感目录见 `SensitiveKeyCatalog`，作用是兜底识别“疑似敏感但策略漏配”的字段，
强制显式拒绝而不是默认放行。

---

## 3. 策略与授权模型

一个 `PolicyVersion` 包含：版本号、创建时间、分级定义、调用方策略列表。
`CallerPolicy` 含 `callerId`、`revoked`（授权是否撤销）、若干 `PurposeRule`；
`PurposeRule` 含用途、允许的最高等级集合 `maxLevels`、若干 `FieldGrant`；
`FieldGrant` 含字段路径、`allowedPurposes`、允许的处理方式 `transform`。

判定顺序（每一级都有彼此可区分的原因，绝不默认放行）：

1. **版本回退**：显式请求非最新版本 → `POLICY_FALLBACK_DENIED`（409）。
2. **调用方**：未登记或 `revoked=true` → `UNAUTHORIZED_CALLER`（403）。
3. **访问用途**：调用方没有该用途，或字段级用途不匹配 → `PURPOSE_MISMATCH`（403）。
4. **敏感等级**：字段等级不在该用途 `maxLevels` 内 → `FIELD_LEVEL_EXCEEDED`（403）。
5. **字段授权**：敏感字段没有显式 `FieldGrant` → `POLICY_MISSING`（403）。
6. 全部通过 → `ALLOW`（200），并按授权的 `transform` 处理。

所有拒绝都返回**结构化原因对象**（含 `code`、`reason`、`auditId`、`audited`），
不用空结果掩盖拒绝。

---

## 4. 脱敏、掩码与令牌化规则

| 转换 | 语义 | 可逆性 | 类型/层级 |
| ---- | ---- | ------ | --------- |
| `NONE` | 原样返回 | — | 保持原值 |
| `MASK` | 保留首尾各 1 位，中间以 `*` 替换并保持长度（长度≤2 全掩码） | 可逆推（非密码学不可逆） | 仅 String；非 String 报 `MALFORMED_DATA` |
| `REDACT` | 整体替换为固定占位符 `[REDACTED]`（不保留长度，防长度侧信道） | **不可逆** | 仅 String |
| `TOKENIZE` | `HMAC-SHA256` 派生令牌，输出 `tok_<hex>` | **不可逆** | 仅 String |

令牌化特性（`HmacTokenizer`）：

- **同原始值 + 同策略版本 → 稳定一致**（确定性），便于跨记录关联；
- 密钥由“本地主密钥 + 策略版本号”SHA-256 派生，**跨版本令牌不复用**（版本隔离）；
- 无反查途径，输出在 `fieldMarkers` 中以 `irreversible=true` 显式标注。

转换在输入的**深拷贝**上原地进行，保持原有嵌套层级与类型约束；任何转换失败都不会
修改原始入参，也不会产生“部分脱敏”的残留结果。

---

## 5. 版本管理与兼容性

- 策略以**不可变版本**存放，多个版本可同时存在；每个版本有唯一版本号，不可重复发布。
- `InMemoryPolicyStore` 用 `AtomicReference<PolicySnapshot>` 发布**不可变快照**：
  读线程要么看到完整旧快照，要么看到完整新快照，**不存在半更新状态**。
- **新请求**：不指定版本时始终使用最新版本；策略发布/重载后下一次读取即生效，
  无缓存陈旧窗口，**已撤销授权立即失效**。
- **显式回退**：请求显式指定旧版本会被 `POLICY_FALLBACK_DENIED` 明确拒绝
  （旧版本仅保留用于解释历史审计，不得用于新请求）；指定不存在的版本 → `POLICY_MISSING`。
- **历史可解释**：旧版本数据随快照保留；审计记录同时保存 `requestedVersion`、
  `resolvedVersion`（发生时实际使用的版本）与 `latestVersion`，因此任何历史记录都能
  按其发生时的版本解释。
- **原子重载**：`POST /reload` 先完整读取并解析所有文件，全部成功才整体替换；
  任何格式异常都拒绝并**保留当前在线快照**，绝不新旧混用。启动时若无任何策略则快速失败。

---

## 6. 审计语义与数据保留

`AuditService` 对**放行和所有拒绝**都写审计（包括扫描阶段的数据质量拒绝、回退拒绝）。

审计记录 `AuditRecord` 包含：`auditId`、时间戳、`callerId`、`purpose`、
`requestedVersion`、`policyVersion`（实际使用版本）、`latestVersion`、
`decisionCode`、`reason`、`inputJson`（**原始输入**）、`basis`（**逐字段判定依据**）。
同时以 SLF4J 打印 `AUDIT ... input=<原始JSON> basis=<判定依据>` 日志。

- **审计写入失败 → 不返回数据**：编排顺序为“判定 → 先写审计 → 成功后才转换并返回”。
  审计失败抛 `AUDIT_WRITE_FAILED`（500），调用方拿不到任何数据，也不会出现无痕迹访问。
  `InMemoryAuditService.armNextWriteFailure()` 可注入一次失败用于验证。
- **不会因审计异常静默放行**：失败一律显式拒绝。
- **保留策略**：按 `gateway.limits.audit-retention-count`（默认 1000）滚动淘汰最旧记录。

---

## 7. 规模、深度、耗时与错误归一化

`application.properties` 可配置：

```properties
gateway.limits.max-payload-bytes=65536      # 请求体 UTF-8 字节上限
gateway.limits.max-field-count=500          # 字段/元素数量上限
gateway.limits.max-depth=8                  # 嵌套深度上限
gateway.limits.process-timeout-millis=2000  # 处理耗时上限
gateway.limits.audit-retention-count=1000   # 审计保留条数
```

- 字节超限 → `LIMIT_PAYLOAD_TOO_LARGE`（413）；字段数超限 → 同码；
  深度超限 → `LIMIT_DEPTH_EXCEEDED`（422）；超时 → `LIMIT_TIMEOUT`（408）。
- 转换在全部前置检查与审计成功后才执行，失败时工作副本为局部对象，随 GC 回收，
  **不残留部分结果、不泄漏资源**。
- `GlobalExceptionHandler` 将底层异常归一化为稳定的原因码 + 安全消息：
  内部堆栈、类名、下游细节不回传；未归一化异常统一映射为 `DOWNSTREAM_FAILURE`（502）。

---

## 8. 本地验证方法（测试覆盖）

全部测试纯本地、无外部服务、无真实凭据：

| 测试类 | 覆盖点 |
| ------ | ------ |
| `PathPatternsTest` | 路径归一化 / `[*]` 通配 / 非法路径 / 重复定义 |
| `DataScannerTest` | 嵌套定位、字段缺失、未分级敏感字段、类型异常、null、深度、空分级 |
| `NestedStructureAnomalyTest` | 数组元素类型错误、标量位置嵌套对象、超深、深层未分级键 |
| `PolicyEvaluatorTest` | 未授权、已撤销、用途不匹配、等级超限、策略缺失、放行（原因可区分） |
| `TransformerTest` | 令牌同版本稳定/跨版本不同/不可逆、掩码脱敏规则、类型约束、层级保持 |
| `PolicyStoreConcurrencyTest` | 并发读写无半更新、替换失败保留现状、重复发布拒绝、撤销随版本即时生效 |
| `RevocationConcurrencyIntegrationTest` | 并发下撤销发布后无一次成功访问（无陈旧缓存放行） |
| `GatewayIntegrationTest` | 端到端：放行稳定性、各拒绝原因、回退、缺失/未分级/类型、审计失败不出数据、超限 |
| `AuditRetentionTest` | 保留淘汰、注入一次失败后恢复 |
| `WebApiTest` | HTTP 状态码/原因码可区分、原始输入进入审计、坏 JSON 归一化 |

运行：

```bash
mvn test                      # 全量
mvn -Dtest=GatewayIntegrationTest test   # 单个测试类
```

---

## 9. 代码结构

```
model/          分级、转换、判定码、策略版本、审计记录等不可变模型
error/          GatewayException（携带可区分判定码）
classification/ 分级注册表、路径工具、内置敏感目录、嵌套数据扫描器
policy/         原子版本化策略存储、判定器
transform/      HMAC 令牌化、掩码/脱敏/令牌化执行器
audit/          内存审计服务（保留策略 + 失败注入）
limit/          规模/深度/耗时上限与守卫
service/        网关编排（版本→扫描→判定→审计→转换）
web/            REST 入口、管理接口、全局错误归一化
config/         配置属性、启动加载与原子重载
resources/gateway/policy-v1.json, policy-v2.json  种子策略（两版本）
```
