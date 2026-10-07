package com.github.lechandonga.firmwarestagedrolloutjava.service;

import com.github.lechandonga.firmwarestagedrolloutjava.exception.BusinessException;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.model.BatchStats;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Device;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;
import com.github.lechandonga.firmwarestagedrolloutjava.model.ProgressEvent;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Release;
import com.github.lechandonga.firmwarestagedrolloutjava.model.ReleaseStatus;
import com.github.lechandonga.firmwarestagedrolloutjava.model.SemanticVersion;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Upgrade;
import com.github.lechandonga.firmwarestagedrolloutjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;

/**
 * 灰度发布与回滚核心服务。
 *
 * 所有改变状态的操作都在同一把进程内锁内执行，保证多设备并发心跳时
 * 失败计数与状态变更不丢失、不重复。
 */
@Service
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);
    /** 分桶空间：设备哈希落在 [0,999]，累计百分比 p 覆盖 bucket < p*10 的设备 */
    private static final int BUCKET_SPACE = 1000;

    private final InMemoryStore store;
    private final Object lock = new Object();

    @Value("${rollout.failure-rate-threshold:0.5}")
    private double defaultFailureRateThreshold;

    @Value("${rollout.min-samples-for-threshold:2}")
    private int defaultMinSamples;

    public RolloutService(InMemoryStore store) {
        this.store = store;
        this.defaultFailureRateThreshold = 0.5;
        this.defaultMinSamples = 2;
    }

    // ------------------------------------------------------------ 设备注册

    public Device registerDevice(String deviceId, String currentVersion) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "deviceId 不能为空");
        }
        SemanticVersion.parse(currentVersion);
        synchronized (lock) {
            return store.putDevice(deviceId.trim(), currentVersion.trim());
        }
    }

    // ------------------------------------------------------------ 发布管理

    /**
     * 创建一次灰度发布。
     *
     * @param stagesPercent 各批次累计覆盖百分比，严格递增、取值 [0,100]、以 100 收尾；
     *                      如 [0, 10, 50, 100] 表示空批次 -> 10% -> 50% -> 全量
     */
    public Release createRelease(String releaseId, String targetVersion,
                                 List<Integer> stagesPercent,
                                 Double failureRateThreshold,
                                 Integer minSamples) {
        if (releaseId == null || releaseId.isBlank()) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "releaseId 不能为空");
        }
        SemanticVersion.parse(targetVersion);
        validateStages(stagesPercent);
        double threshold = failureRateThreshold != null
                ? failureRateThreshold : defaultFailureRateThreshold;
        if (threshold <= 0 || threshold > 1) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "失败率阈值必须在 (0,1] 区间: " + threshold);
        }
        int min = minSamples != null ? minSamples : defaultMinSamples;
        if (min < 1) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "最小样本数必须 >= 1: " + min);
        }
        synchronized (lock) {
            if (store.getRelease(releaseId.trim()) != null) {
                throw new BusinessException(RejectReason.INVALID_REQUEST,
                        "发布已存在: " + releaseId);
            }
            Release release = new Release(releaseId.trim(), targetVersion.trim(),
                    stagesPercent, threshold, min);
            store.putRelease(release);
            log.info("创建灰度发布 releaseId={} targetVersion={} stages={} 失败率阈值={} 最小样本={}",
                    releaseId, targetVersion, stagesPercent, threshold, min);
            return release;
        }
    }

    private void validateStages(List<Integer> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, "至少需要一个批次");
        }
        int prev = -1;
        for (int percent : stages) {
            if (percent < 0 || percent > 100) {
                throw new BusinessException(RejectReason.INVALID_REQUEST,
                        "批次百分比必须在 [0,100]: " + percent);
            }
            if (percent <= prev) {
                throw new BusinessException(RejectReason.INVALID_REQUEST,
                        "批次百分比必须严格递增: " + stages);
            }
            prev = percent;
        }
        if (prev != 100) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "最后一个批次必须是全量 100: " + stages);
        }
    }

    /** 灰度扩大到下一个批次 */
    public Release advanceStage(String releaseId) {
        synchronized (lock) {
            Release release = requireRelease(releaseId);
            if (release.getStatus() != ReleaseStatus.ACTIVE) {
                throw new BusinessException(RejectReason.RELEASE_NOT_ACTIVE,
                        "发布当前状态 " + release.getStatus() + "，不能推进批次");
            }
            if (release.isLastStage()) {
                throw new BusinessException(RejectReason.RELEASE_NOT_ACTIVE,
                        "已是全量批次，无法继续扩大");
            }
            release.setCurrentStage(release.getCurrentStage() + 1);
            log.info("发布 {} 灰度扩大到第 {} 批，累计覆盖 {}%",
                    releaseId, release.getCurrentStage(), release.currentPercent());
            return release;
        }
    }

    public Release pause(String releaseId) {
        synchronized (lock) {
            Release release = requireRelease(releaseId);
            if (release.getStatus() != ReleaseStatus.ACTIVE) {
                throw new BusinessException(RejectReason.RELEASE_NOT_ACTIVE,
                        "发布当前状态 " + release.getStatus() + "，不能暂停");
            }
            release.setStatus(ReleaseStatus.PAUSED);
            log.info("发布 {} 已手动暂停", releaseId);
            return release;
        }
    }

    public Release resume(String releaseId) {
        synchronized (lock) {
            Release release = requireRelease(releaseId);
            if (release.getStatus() != ReleaseStatus.PAUSED) {
                throw new BusinessException(RejectReason.RELEASE_NOT_ACTIVE,
                        "发布当前状态 " + release.getStatus() + "，不能恢复");
            }
            release.setStatus(ReleaseStatus.ACTIVE);
            log.info("发布 {} 已恢复", releaseId);
            return release;
        }
    }

    // ------------------------------------------------------------ 下发

    /**
     * 对设备下发某次发布。稳定分桶：同一设备多次评估结果一致。
     *
     * @return accepted=新下发；idempotent=该设备该版本已有任务，原样返回不重复生效
     */
    public DispatchResult dispatch(String deviceId, String releaseId) {
        synchronized (lock) {
            Device device = requireDevice(deviceId);
            Release release = requireRelease(releaseId);

            Upgrade existing = store.getUpgrade(device.getDeviceId(), release.getReleaseId());
            if (existing != null) {
                log.info("幂等下发 device={} release={} currentState={}",
                        deviceId, releaseId, existing.getState());
                return DispatchResult.idempotent(existing);
            }

            if (release.getStatus() != ReleaseStatus.ACTIVE) {
                throw new BusinessException(RejectReason.DISPATCH_BLOCKED_BY_STATUS,
                        "发布状态 " + release.getStatus() + "，不接受新下发");
            }

            SemanticVersion current = SemanticVersion.parse(device.getCurrentVersion());
            SemanticVersion target = SemanticVersion.parse(release.getTargetVersion());
            if (current.equals(target)) {
                throw new BusinessException(RejectReason.SAME_VERSION_REINSTALL,
                        "设备 " + deviceId + " 已在版本 " + current + "，不允许同版本重装");
            }
            if (current.isNewerThan(target)) {
                throw new BusinessException(RejectReason.DOWNGRADE_NOT_ALLOWED,
                        "设备 " + deviceId + " 当前版本 " + current + " 高于目标 "
                                + target + "，普通升级不接受降级");
            }

            int bucket = bucketOf(device.getDeviceId());
            int percent = release.currentPercent();
            if (!inTarget(bucket, percent)) {
                throw new BusinessException(RejectReason.NOT_IN_TARGET,
                        "设备 " + deviceId + " 分桶 " + bucket + " 不在当前批次覆盖 "
                                + percent + "% 内");
            }

            Upgrade upgrade = new Upgrade(device.getDeviceId(), release.getReleaseId(),
                    release.getTargetVersion(), device.getCurrentVersion(),
                    release.getCurrentStage());
            store.putUpgrade(upgrade);
            store.getBatchStats(release.getReleaseId(), release.getCurrentStage())
                    .assigned.incrementAndGet();
            log.info("新下发 device={} bucket={} release={} stage={} {} -> {}",
                    deviceId, bucket, releaseId, release.getCurrentStage(),
                    device.getCurrentVersion(), release.getTargetVersion());
            return DispatchResult.accepted(upgrade);
        }
    }

    /**
     * 设备心跳上报进度。重复上报幂等，迟到旧状态拒绝，非法流转拒绝；
     * 失败率超阈值时自动暂停并触发回滚。
     */
    public HeartbeatResult heartbeat(String deviceId, String releaseId,
                                     String version, ProgressEvent event) {
        synchronized (lock) {
            Device device = store.findDevice(deviceId)
                    .orElseThrow(() -> new BusinessException(RejectReason.UNKNOWN_DEVICE,
                            "未知设备: " + deviceId));
            Release release = store.getRelease(releaseId);
            if (release == null) {
                throw new BusinessException(RejectReason.UNKNOWN_RELEASE,
                        "未知发布: " + releaseId);
            }
            Upgrade upgrade = store.getUpgrade(deviceId, releaseId);
            if (upgrade == null) {
                throw new BusinessException(RejectReason.UPGRADE_NOT_FOUND,
                        "设备 " + deviceId + " 没有发布 " + releaseId + " 的下发任务");
            }
            if (!release.getTargetVersion().equals(version)) {
                throw new BusinessException(RejectReason.UPGRADE_NOT_FOUND,
                        "上报版本 " + version + " 与发布目标版本 "
                                + release.getTargetVersion() + " 不一致");
            }

            DeviceState currentState = upgrade.getState();
            DeviceState targetState = toState(event);

            if (targetState == currentState) {
                log.info("幂等心跳 device={} release={} state={}（重复上报不计数）",
                        deviceId, releaseId, currentState);
                return HeartbeatResult.idempotent(upgrade, release);
            }

            if (event == ProgressEvent.ROLLBACK_DONE) {
                applyRollbackDone(device, upgrade, release);
            } else if (currentState == DeviceState.ROLLING_BACK
                    || currentState == DeviceState.ROLLBACK_DONE) {
                throw new BusinessException(RejectReason.ILLEGAL_TRANSITION,
                        "设备 " + deviceId + " 已在回滚路径(" + currentState
                                + ")，普通事件 " + event + " 为非法流转");
            } else if (currentState == DeviceState.INSTALL_FAILED) {
                throw new BusinessException(RejectReason.ILLEGAL_TRANSITION,
                        "设备 " + deviceId + " 已安装失败(" + currentState
                                + ")，普通事件 " + event + " 为非法流转");
            } else if (targetState.rank() < currentState.rank()) {
                throw new BusinessException(RejectReason.STALE_EVENT,
                        "设备 " + deviceId + " 当前状态 " + currentState
                                + "，迟到旧事件 " + event + " 不得覆盖");
            } else if (currentState.terminal()) {
                throw new BusinessException(RejectReason.ILLEGAL_TRANSITION,
                        "设备 " + deviceId + " 已处于终态 " + currentState
                                + "，事件 " + event + " 为非法流转");
            } else if (!isLegalForward(currentState, targetState)) {
                throw new BusinessException(RejectReason.ILLEGAL_TRANSITION,
                        "非法状态流转: " + currentState + " -> " + targetState);
            } else {
                applyForward(device, upgrade, release, targetState);
                // 触发回滚后仍有设备完成安装（暂停前已开始），
                // 这类设备同样必须回滚，立即并入回滚集合。
                if (release.getStatus() == ReleaseStatus.ROLLING_BACK
                        && upgrade.isOnNewVersion()) {
                    upgrade.setState(DeviceState.ROLLING_BACK);
                    log.info("设备 {} 在回滚期间安装成功，一并要求回滚", deviceId);
                }
            }

            boolean rollbackTriggered = maybeTriggerRollback(release);
            boolean rollbackFinished = maybeFinishRollback(release);
            log.info("心跳生效 device={} release={} {} -> {} rollbackTriggered={} rollbackFinished={}",
                    deviceId, releaseId, currentState, upgrade.getState(),
                    rollbackTriggered, rollbackFinished);
            return new HeartbeatResult(false, upgrade.getState().name(),
                    release.getStatus().name(),
                    rollbackTriggered, rollbackFinished);
        }
    }

    // ------------------------------------------------------------ 状态机

    private DeviceState toState(ProgressEvent event) {
        return switch (event) {
            case DOWNLOADING -> DeviceState.DOWNLOADING;
            case PENDING_INSTALL -> DeviceState.PENDING_INSTALL;
            case INSTALLED -> DeviceState.INSTALLED;
            case INSTALL_FAILED -> DeviceState.INSTALL_FAILED;
            case ROLLBACK_DONE -> DeviceState.ROLLBACK_DONE;
        };
    }

    private boolean isLegalForward(DeviceState from, DeviceState to) {
        return switch (to) {
            case DOWNLOADING -> from == DeviceState.ASSIGNED;
            case PENDING_INSTALL -> from == DeviceState.DOWNLOADING;
            case INSTALLED, INSTALL_FAILED ->
                    from == DeviceState.ASSIGNED
                            || from == DeviceState.DOWNLOADING
                            || from == DeviceState.PENDING_INSTALL;
            default -> false;
        };
    }

    private void applyForward(Device device, Upgrade upgrade, Release release, DeviceState target) {
        upgrade.setState(target);
        BatchStats stats = store.getBatchStats(release.getReleaseId(), upgrade.getStageIndex());
        switch (target) {
            case INSTALLED -> {
                device.setCurrentVersion(upgrade.getTargetVersion());
                upgrade.setActiveVersion(upgrade.getTargetVersion());
                stats.installed.incrementAndGet();
            }
            case INSTALL_FAILED -> {
                // 安装失败设备仍停留在旧版本；失败按设备只计一次
                stats.failed.incrementAndGet();
            }
            default -> { /* DOWNLOADING / PENDING_INSTALL 只推进状态，不计统计 */ }
        }
    }

    private void applyRollbackDone(Device device, Upgrade upgrade, Release release) {
        DeviceState current = upgrade.getState();
        // 回滚是唯一允许的降级路径，仅新版本曾生效的设备可回滚：
        // ROLLING_BACK（自动回滚已标记）或 INSTALLED（设备直接上报，服务端折叠中间步骤）
        if (current != DeviceState.ROLLING_BACK && current != DeviceState.INSTALLED) {
            throw new BusinessException(RejectReason.NOT_IN_ROLLBACK,
                    "设备 " + device.getDeviceId() + " 当前状态 " + current
                            + "，不在回滚路径上，ROLLBACK_DONE 为非法上报");
        }
        upgrade.setState(DeviceState.ROLLBACK_DONE);
        upgrade.setActiveVersion(upgrade.getPreviousVersion());
        device.setCurrentVersion(upgrade.getPreviousVersion());
        store.getBatchStats(release.getReleaseId(), upgrade.getStageIndex())
                .rolledBack.incrementAndGet();
    }

    // ------------------------------------------------------------ 自动暂停与回滚

    /**
     * 仅统计“当前进行中批次”的失败率：
     * 失败设备数 / (成功设备数 + 失败设备数)。
     * 达到最小样本数且失败率严格大于阈值时，暂停发布并把所有已装上新版本的
     * 设备置为 ROLLING_BACK，指示设备降回上一个版本。
     */
    private boolean maybeTriggerRollback(Release release) {
        if (release.getStatus() != ReleaseStatus.ACTIVE
                && release.getStatus() != ReleaseStatus.PAUSED) {
            return false;
        }
        BatchStats stats = store.getBatchStats(release.getReleaseId(), release.getCurrentStage());
        int success = stats.installed.get();
        int failed = stats.failed.get();
        int decided = success + failed;
        if (decided < release.getMinSamplesForThreshold()) {
            return false;
        }
        double rate = (double) failed / decided;
        if (!(rate > release.getFailureRateThreshold())) {
            return false;
        }

        release.setStatus(ReleaseStatus.ROLLING_BACK);
        log.warn("发布 {} 第 {} 批失败率 {} > 阈值 {}（成功={} 失败={}），自动暂停并触发回滚",
                release.getReleaseId(), release.getCurrentStage(),
                String.format("%.2f", rate), release.getFailureRateThreshold(),
                success, failed);

        for (Upgrade upgrade : store.upgradesOfRelease(release.getReleaseId())) {
            if (upgrade.isOnNewVersion()) {
                upgrade.setState(DeviceState.ROLLING_BACK);
                log.info("通知设备 {} 回滚 {} -> {}",
                        upgrade.getDeviceId(), upgrade.getTargetVersion(),
                        upgrade.getPreviousVersion());
            }
        }
        return true;
    }

    /**
     * 回滚触发后，所有曾装上新版本的设备均回到上一版本时，发布进入 ROLLED_BACK 终态。
     */
    private boolean maybeFinishRollback(Release release) {
        if (release.getStatus() != ReleaseStatus.ROLLING_BACK) {
            return false;
        }
        boolean pending = store.upgradesOfRelease(release.getReleaseId()).stream()
                .anyMatch(u -> u.getState() == DeviceState.ROLLING_BACK || u.isOnNewVersion());
        if (pending) {
            return false;
        }
        release.setStatus(ReleaseStatus.ROLLED_BACK);
        log.warn("发布 {} 所有新版本设备已回滚完成", release.getReleaseId());
        return true;
    }

    // ------------------------------------------------------------ 灰度分桶

    /**
     * 稳定分桶：SHA-256(deviceId) 取前 8 个十六进制字符映射到 [0,999]。
     * 只依赖 deviceId，不随批次、时间、顺序变化，因此多次评估一致。
     */
    public static int bucketOf(String deviceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(deviceId.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash, 0, 4);
            return (int) (Long.parseLong(hex, 16) % BUCKET_SPACE);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 累计百分比 p 覆盖 bucket < p*10 的设备；p=0 为空批次，p=100 为全量。 */
    public static boolean inTarget(int bucket, int cumulativePercent) {
        return bucket < cumulativePercent * (BUCKET_SPACE / 100);
    }

    // ------------------------------------------------------------ 查询视图

    public ReleaseView releaseView(String releaseId) {
        synchronized (lock) {
            Release release = requireRelease(releaseId);
            List<BatchStats> all = store.allBatchStats(releaseId);
            int assigned = 0;
            int installed = 0;
            int failed = 0;
            int rollingBack = 0;
            int inFlight = 0;
            for (Upgrade upgrade : store.upgradesOfRelease(releaseId)) {
                switch (upgrade.getState()) {
                    case ROLLING_BACK -> rollingBack++;
                    case ASSIGNED, DOWNLOADING, PENDING_INSTALL -> inFlight++;
                    default -> { /* INSTALLED / INSTALL_FAILED / ROLLBACK_DONE 取批次历史口径 */ }
                }
            }
            int rolledBack = 0;
            for (BatchStats s : all) {
                assigned += s.assigned.get();
                installed += s.installed.get();
                failed += s.failed.get();
                rolledBack += s.rolledBack.get();
            }
            int decided = installed + failed;
            double failureRate = decided == 0 ? 0.0 : (double) failed / decided;
            return new ReleaseView(release.getReleaseId(), release.getTargetVersion(),
                    release.getStagesPercent(), release.getCurrentStage(),
                    release.currentPercent(), release.getStatus().name(),
                    release.getFailureRateThreshold(), assigned, installed, failed,
                    rollingBack, rolledBack, inFlight, failureRate);
        }
    }

    public Upgrade upgradeView(String deviceId, String releaseId) {
        synchronized (lock) {
            requireDevice(deviceId);
            requireRelease(releaseId);
            Upgrade upgrade = store.getUpgrade(deviceId, releaseId);
            if (upgrade == null) {
                throw new BusinessException(RejectReason.UPGRADE_NOT_FOUND,
                        "设备 " + deviceId + " 没有发布 " + releaseId + " 的下发任务");
            }
            return upgrade;
        }
    }

    private Device requireDevice(String deviceId) {
        Device device = store.getDevice(deviceId);
        if (device == null) {
            throw new BusinessException(RejectReason.UNKNOWN_DEVICE, "未知设备: " + deviceId);
        }
        return device;
    }

    private Release requireRelease(String releaseId) {
        Release release = store.getRelease(releaseId);
        if (release == null) {
            throw new BusinessException(RejectReason.UNKNOWN_RELEASE, "未知发布: " + releaseId);
        }
        return release;
    }
}
