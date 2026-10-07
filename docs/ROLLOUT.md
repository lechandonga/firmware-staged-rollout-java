# 设备固件灰度发布与回滚服务

进程内状态（`ConcurrentHashMap` + `AtomicInteger` + 细粒度锁），无数据库、无外部依赖；
固件包仅以语义化版本号标识。

## 1. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/devices` | 注册设备及当前版本，如 `{"deviceId":"d1","currentVersion":"1.0.0"}` |
| POST | `/api/releases` | 创建发布，`rolloutPercents` 为累计灰度比例 |
| POST | `/api/releases/{id}/batches/{i}/activate` | 激活第 i 批，扩大覆盖范围 |
| POST | `/api/releases/{id}/dispatch?deviceId=d1` | 设备心跳拉取升级指令 |
| POST | `/api/progress` | 上报进度：`{deviceId, releaseId, state}` |
| POST | `/api/rollback` | 显式发起单设备回滚：`{deviceId, releaseId}` |
| GET | `/api/releases/{id}` | 批次状态与统计 |

## 2. 灰度分批规则

- 创建发布时给出**累计**覆盖百分比，必须递增、取值 0–100，例如 `[0,10,50,100]`
  表示空批次（0%）、10%、扩大到 50%、最终全量。
- 设备槽位：`slot = |FNV1a64(releaseId + ":" + deviceId)| mod 100`，为 0–99 的稳定整数。
  槽位落在 `(上一批累计比例, 本批累计比例]` 的设备属于本批。
- 结果只由 `releaseId`、`deviceId` 和批次配置决定，与激活/上报顺序、时间无关，
  多次评估完全一致，因此“批次扩大”只会纳入此前未覆盖的设备。
- 批次状态：`PENDING → ACTIVE → COMPLETED`；失败率超阈时 `ACTIVE → PAUSED → ROLLED_BACK`。
  - `PENDING`/`COMPLETED` 批次的设备请求下发：`409 NOT_IN_TARGET`。
  - `PAUSED`/`ROLLED_BACK` 批次：已纳入设备的心跳返回 `result=ROLL_BACK`（回滚指令），
    从未下发的新设备：`409 BATCH_PAUSED`，暂停后不向新设备下发。
- 下发幂等：同一设备同一版本重复下发返回 `200`、`result=IDEMPOTENT`，
    `dispatched/targets` 计数只增加一次；设备已在目标版本同样返回 `IDEMPOTENT`
    （`ALREADY_UP_TO_DATE`）。
- 普通升级目标版本必须更高：降级 `409 VERSION_DOWNGRADE`，同版本 `200 IDEMPOTENT`。

## 3. 状态流转与版本比较

设备上报状态：`DOWNLOADING`、`PENDING_INSTALL`、`INSTALL_SUCCESS`、
`INSTALL_FAILED`、`ROLLBACK_COMPLETE`。

服务端内部状态：`DISPATCHED`（已下发）、`ROLLING_BACK`（回滚指令已发出）。

正常路径：

```
DISPATCHED → DOWNLOADING → PENDING_INSTALL → INSTALL_SUCCESS（版本切换为目标版本）
                                             INSTALL_FAILED → ROLLING_BACK → ROLLBACK_COMPLETE
```

判定规则：

- 重复上报同一状态：`200 DUPLICATE_PROGRESS`（`ROLLBACK_COMPLETE` 重复为
  `ALREADY_ROLLED_BACK`），不重复计数。
- 迟到旧状态（progress 序号更小）：`409 STALE_STATE`，不覆盖更新状态。
- 跳变到更早状态、未进入 `ROLLING_BACK` 直接报 `ROLLBACK_COMPLETE`、
  回滚中上报普通进度：`409 ILLEGAL_STATE`。
- 未注册设备 `UNKNOWN_DEVICE`、发布不存在 `RELEASE_NOT_FOUND`、
  未下发就上报进度 `ILLEGAL_STATE`，拒绝原因彼此可区分。
- **回滚是唯一允许的降级路径**：`ROLLBACK_COMPLETE` 时设备版本恢复为下发时记录的
  `fromVersion`，普通升级路径不接受降级或同版本重装。

版本比较遵循语义化版本（semver）：按 `MAJOR.MINOR.PATCH` 数值比较；
预发布版本（`1.0.0-rc.1`）低于同号正式版本，预发布标识符按 semver 规则比较；
构建后缀（`+build`）不参与比较；非法格式直接拒绝。

## 4. 自动暂停与回滚阈值

- 每收到 `INSTALL_FAILED` / `INSTALL_SUCCESS` 更新统计：`failureRate = failed / (success + failed)`。
- 当已结束设备数 `success + failed ≥ minDecidedForThreshold`（默认 3）且
  `failureRate ≥ failureRateThreshold`（默认 0.3）时，批次**自动暂停一次**。
- 暂停瞬间把批次内所有已下发设备（含已安装成功的设备）统一推进到
  `ROLLING_BACK`：已升级设备沿回滚路径回到上一个版本，设备随后心跳会拿到
  `result=ROLL_BACK`，上报 `ROLLBACK_COMPLETE` 后版本恢复、批次 `rolledBack +1`。
- 批次内全部已下发设备都 `ROLLBACK_COMPLETE` 后，批次变为 `ROLLED_BACK`。
  此时 `rolledBack == dispatched == targets`，成功/失败计数保持冻结，整体进度可解释。
- 并发：状态变更在“设备升级记录”的锁内完成；失败计数与阈值判定在设备锁内再取
  批次锁，保证计数与暂停原子化，多个设备并发上报不丢、不重。

## 5. 本地验证

```bash
mvn test
```

控制台会输出 `BUSINESS_COVERAGE` 日志：按业务域打印横幅、每个用例的开始/通过/失败
和小结，例如：

```
==================== 业务域: GrayBatchTest ====================
>> [用例开始] 空批次(0%): ...
<< [用例通过] 空批次(0%): ...
---- 业务域 GrayBatchTest 小结: 通过 5 个, 失败 0 个 ----
```

测试覆盖：

- `GrayBatchTest`：空批次、全量批次、批次扩大、稳定归属、重复/超范围下发、降级与同版本。
- `ProgressStateMachineTest`：正常流转、重复上报、乱序旧状态、非法流转、回滚恢复、未知实体区分。
- `AutoPauseRollbackTest`：最小样本、阈值暂停、停止新下发、整批回滚一致性、低于阈值正常完成。
- `ConcurrentProgressTest`：多设备并发成功/失败上报的计数精确与只暂停一次。
- `SemanticVersionTest`：版本比较、预发布、构建后缀、非法格式。
- `RolloutApiIntegrationTest`：HTTP 端到端链路与拒绝码。

手工冒烟：

```bash
mvn spring-boot:run
curl -XPOST localhost:8080/api/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"d1","currentVersion":"1.0.0"}'
curl -XPOST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"releaseId":"r1","targetVersion":"2.0.0","rolloutPercents":[100]}'
curl -XPOST localhost:8080/api/releases/r1/batches/0/activate
curl -XPOST 'localhost:8080/api/releases/r1/dispatch?deviceId=d1'
curl -XPOST localhost:8080/api/progress -H 'Content-Type: application/json' \
  -d '{"deviceId":"d1","releaseId":"r1","state":"INSTALL_SUCCESS"}'
```
