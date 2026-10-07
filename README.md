# 设备固件灰度发布与回滚服务（Spring Boot）

设备通过 HTTP 心跳上报升级进度的固件灰度发布服务。固件包不涉及真实文件，
仅以语义化版本号标识；所有状态保存在进程内存中，不依赖数据库或外部服务。

## 1. 灰度分批规则

- 一次发布通过 `stagesPercent` 配置各批次的**累计覆盖百分比**，必须严格递增、
  取值 `[0,100]`、且以 `100` 收尾。例如 `[0, 10, 50, 100]`：
  - 第 0 批 `0%`：空批次，任何设备都不下发（用于验证边界）；
  - 第 1 批 `10%`：仅覆盖哈希分桶落在前 10% 的设备；
  - 第 2 批 `50%`：灰度扩大，覆盖前 50%（准入集合只增不减）；
  - 第 3 批 `100%`：全量批次，覆盖所有设备。
- 稳定分桶：`bucket = SHA-256(deviceId)` 前 4 字节转长整型后对 `1000` 取模，
  设备属于当前批次当且仅当 `bucket < 当前累计百分比 × 10`。
  分桶**只依赖 deviceId**，与批次、发布、时间、调用顺序无关，
  因此同一设备多次评估、跨发布评估结果一致、可复现。
- 批次推进：调用“推进批次”接口后当前批次扩大到下一个百分比；
  准入集合单调扩大，已在集合内的设备不受影响。
- 下发结果可区分：
  - 新下发：`idempotent=false`；
  - 同一设备同一版本重复下发：返回 `idempotent=true`，不创建新任务、计数不重复增加；
  - 设备不在当前批次目标集合：错误码 `NOT_IN_TARGET`；
  - 目标版本等于设备当前版本：`SAME_VERSION_REINSTALL`（禁止同版本重装）；
  - 目标版本低于设备当前版本：`DOWNGRADE_NOT_ALLOWED`（普通升级禁止降级）；
  - 发布处于暂停/回滚/终态：`DISPATCH_BLOCKED_BY_STATUS`；
  - 未知设备 / 未知发布：`UNKNOWN_DEVICE` / `UNKNOWN_RELEASE`。

## 2. 升级状态流转

设备升级任务（设备 + 发布维度）状态机：

```
ASSIGNED ──DOWNLOADING──> DOWNLOADING ──PENDING_INSTALL──> PENDING_INSTALL
                                                              │
                              INSTALLED（成功，版本前推到新版本）│
                                                              │
                              INSTALL_FAILED（失败，停留旧版本）│

INSTALLED / ROLLING_BACK ──ROLLBACK_DONE──> ROLLBACK_DONE（版本降回上一个稳定版本）
自动回滚触发时：所有 INSTALLED 设备由服务端置为 ROLLING_BACK
```

- 允许的正向流转：
  - `ASSIGNED -> DOWNLOADING -> PENDING_INSTALL -> INSTALLED`；
  - `ASSIGNED/DOWNLOADING/PENDING_INSTALL -> INSTALL_FAILED`（设备可能不上报全部中间事件）。
- 重复上报：事件映射出的状态与当前状态相同，返回 `idempotent=true`，
  状态与统计计数都不变化。
- 乱序上报：
  - 上报事件对应的状态**落后于**当前状态（迟到的旧状态）：
    错误码 `STALE_EVENT`，旧状态不得覆盖新状态；
  - 跳跃式非法流转（如 `ASSIGNED` 直接报 `PENDING_INSTALL`）：
    错误码 `ILLEGAL_TRANSITION`；
  - `INSTALL_FAILED` 是失败终态，之后再上报普通进度事件：`ILLEGAL_TRANSITION`。
- 心跳必须携带目标版本号：设备没有该发布的下发任务，或上报版本与发布目标版本不一致，
  均返回 `UPGRADE_NOT_FOUND`。
- 成功/失败均按设备去重计数（每个升级任务只计一次）。

## 3. 版本比较规则

- 使用语义化版本 `major.minor.patch`（支持 `1.0.0-rc.1` 预发布后缀，忽略 `+build` 元数据）。
- 比较顺序：major → minor → patch，均按数值比较（`2.0.0 > 1.9.9`）。
- 同号预发布版本低于正式版本（`1.0.0-rc1 < 1.0.0`）；预发布标识按 SemVer 规则比较。
- 普通升级只接受版本变新；同版本重装、降级均被拒绝。
- **回滚是唯一允许的降级路径**：只有新版本曾实际生效（`INSTALLED` 或已被服务端标记
  `ROLLING_BACK`）的设备可以上报 `ROLLBACK_DONE`，设备版本降回发布前的上一个稳定版本；
  未升级设备上报回滚返回 `NOT_IN_ROLLBACK`。

## 4. 自动暂停与回滚阈值

- 统计口径：仅统计**当前进行中批次**已出结果的设备
  （安装成功数 `installed`、安装失败数 `failed`）。
- 触发条件（两者同时满足）：
  1. `installed + failed >= minSamplesForThreshold`（默认 `2`，避免单台失败误伤）；
  2. 失败率 `failed / (installed + failed)` **严格大于** `failureRateThreshold`
     （默认 `0.5`；等于阈值不触发）。
- 触发后：
  1. 发布状态 `ACTIVE -> ROLLING_BACK`（自动暂停，此后新设备下发返回
     `DISPATCH_BLOCKED_BY_STATUS`）；
  2. 所有当前处于新版本（`INSTALLED`）的设备被标记为 `ROLLING_BACK`，
     指示设备降回上一个稳定版本；安装失败的设备本来就在旧版本，不参与回滚；
  3. 暂停前已经开始下载/安装、在回滚期间才上报安装成功的设备，同样立即并入回滚集合。
- 设备上报 `ROLLBACK_DONE` 后：设备运行版本降回旧版本，回滚完成数 +1（去重）。
  当不再存在 `ROLLING_BACK` 或停留在新版本的设备时，发布进入终态 `ROLLED_BACK`。
- 手动暂停/恢复：`PAUSED` 状态同样阻断新下发，但不触发回滚，可恢复为 `ACTIVE`。
- 并发安全：所有下发、心跳、暂停、推进批次操作在同一把进程内锁内完成，
  多设备并发上报时失败计数与状态变更不丢失、不重复。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/devices` | 注册设备 `{deviceId, currentVersion}` |
| POST | `/api/releases` | 创建发布 `{releaseId, targetVersion, stagesPercent, failureRateThreshold?, minSamplesForThreshold?}` |
| POST | `/api/releases/{id}/advance` | 灰度扩大到下一批次 |
| POST | `/api/releases/{id}/pause` | 手动暂停 |
| POST | `/api/releases/{id}/resume` | 恢复暂停的发布 |
| POST | `/api/dispatch` | 下发 `{deviceId, releaseId}` |
| POST | `/api/heartbeat` | 心跳 `{deviceId, releaseId, version, event}` |
| GET  | `/api/releases/{id}` | 发布整体进度与批次统计 |
| GET  | `/api/devices/{deviceId}/releases/{releaseId}` | 单设备升级任务状态 |

`event` 取值：`DOWNLOADING`、`PENDING_INSTALL`、`INSTALLED`、`INSTALL_FAILED`、`ROLLBACK_DONE`。
业务拒绝统一返回 HTTP 400，响应体 `{"error": "<错误码>", "message": "<说明>"}`，
错误码见上文（如 `NOT_IN_TARGET`、`STALE_EVENT`、`ILLEGAL_TRANSITION`）。

## 6. 本地验证

环境要求：JDK 21+（开发机使用 JDK 25 验证可编译运行）、Maven 3.9+。

```bash
# 运行全部单测（27 个用例），控制台会打印【业务】【用例】【检查点】【结果】中文日志
mvn test

# 只跑某一类业务测试
mvn test -Dtest=StagedRolloutTest        # 灰度边界：空批次/全量/批次扩大/稳定分桶/幂等
mvn test -Dtest=ProgressHeartbeatTest    # 重复/乱序上报、非法流转、版本与回滚降级
mvn test -Dtest=AutoRollbackTest         # 失败率阈值、自动暂停与回滚、回滚幂等
mvn test -Dtest=ConcurrentHeartbeatTest  # 并发心跳与并发回滚的计数一致性
mvn test -Dtest=HttpApiIntegrationTest   # HTTP 端到端链路与错误码

# 启动服务（端口 8080）
mvn spring-boot:run
```

手动 curl 验证示例：

```bash
curl -s -XPOST localhost:8080/api/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-0","currentVersion":"1.0.0"}'
curl -s -XPOST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"releaseId":"r1","targetVersion":"2.0.0","stagesPercent":[0,100]}'
curl -s -XPOST localhost:8080/api/dispatch -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-0","releaseId":"r1"}'   # 空批次 -> error=NOT_IN_TARGET
curl -s -XPOST localhost:8080/api/releases/r1/advance
curl -s -XPOST localhost:8080/api/dispatch -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-0","releaseId":"r1"}'   # idempotent=false
curl -s -XPOST localhost:8080/api/heartbeat -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-0","releaseId":"r1","version":"2.0.0","event":"INSTALLED"}'
curl -s localhost:8080/api/releases/r1
```

## 7. 测试覆盖一览

| 测试类 | 覆盖业务 |
| --- | --- |
| `SemanticVersionTest` | 语义化版本比较、预发布版本、非法版本、降级判定 |
| `StagedRolloutTest` | 空批次、全量批次、批次扩大单调增加、稳定可复现分桶、重复下发幂等、降级/同版本/暂停拒绝、批次配置校验 |
| `ProgressHeartbeatTest` | 正常流转、重复上报幂等、乱序旧状态拒绝、跳跃非法流转、失败去重、回滚唯一降级路径、未知设备/发布/版本不一致 |
| `AutoRollbackTest` | 阈值严格大于边界、最小样本数保护、自动暂停阻断下发、已升级设备回滚、终态三方一致、回滚上报幂等 |
| `ConcurrentHeartbeatTest` | 同设备 32 线程并发重复上报只生效一次；101 设备并发成功/失败触发回滚并并发回滚完成，计数精确一致 |
| `HttpApiIntegrationTest` | HTTP 端到端链路、400 错误码区分、自动回滚接口行为 |
