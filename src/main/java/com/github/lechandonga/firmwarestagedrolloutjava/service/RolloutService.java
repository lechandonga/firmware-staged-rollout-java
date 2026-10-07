package com.github.lechandonga.firmwarestagedrolloutjava.service;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.BatchView;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.DeviceView;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.DispatchResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ProgressResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RolloutException;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Batch;
import com.github.lechandonga.firmwarestagedrolloutjava.model.BatchStatus;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Device;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceUpgrade;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Release;
import com.github.lechandonga.firmwarestagedrolloutjava.model.SemanticVersion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RolloutService {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Release> releases = new ConcurrentHashMap<>();
    /** releaseId -> (deviceId -> 升级记录)。记录对象自带锁，作为设备级互斥单元。 */
    private final Map<String, Map<String, DeviceUpgrade>> upgrades = new ConcurrentHashMap<>();

    public DeviceView registerDevice(String deviceId, String currentVersion) {
        SemanticVersion.parse(currentVersion);
        devices.computeIfAbsent(deviceId, id -> new Device(id, currentVersion));
        return new DeviceView(deviceId, devices.get(deviceId).getCurrentVersion());
    }

    public synchronized ReleaseView createRelease(CreateReleaseRequest request) {
        if (releases.containsKey(request.releaseId())) {
            throw new IllegalArgumentException("发布已存在: " + request.releaseId());
        }
        SemanticVersion.parse(request.targetVersion());
        List<Integer> percents = request.rolloutPercents() == null
                ? List.of() : request.rolloutPercents();
        if (percents.isEmpty()) {
            throw new IllegalArgumentException("至少配置一个灰度批次");
        }
        int previous = -1;
        for (Integer percent : percents) {
            if (percent == null || percent < previous || percent > 100) {
                throw new IllegalArgumentException("累计灰度比例必须递增且在 0-100 之间");
            }
            previous = percent;
        }
        List<Batch> batches = new ArrayList<>();
        for (int i = 0; i < percents.size(); i++) {
            batches.add(new Batch(i, percents.get(i)));
        }
        double threshold = request.failureRateThreshold() != null
                ? request.failureRateThreshold() : 0.3d;
        int minDecided = request.minDecidedForThreshold() != null
                ? request.minDecidedForThreshold() : 3;
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("失败率阈值必须在 (0,1] 之间");
        }
        Release release = new Release(request.releaseId(), request.targetVersion(),
                batches, threshold, minDecided);
        releases.put(release.getReleaseId(), release);
        upgrades.put(release.getReleaseId(), new ConcurrentHashMap<>());
        return toReleaseView(release);
    }

    public synchronized ReleaseView activateBatch(String releaseId, int batchIndex) {
        Release release = requireRelease(releaseId);
        if (batchIndex < 0 || batchIndex >= release.getBatches().size()) {
            throw new IllegalArgumentException("批次下标越界");
        }
        Batch batch = release.getBatches().get(batchIndex);
        synchronized (batch) {
            if (batch.getStatus() != BatchStatus.PENDING) {
                throw new IllegalStateException("批次当前状态为 " + batch.getStatus() + "，不可激活");
            }
            batch.setStatus(BatchStatus.ACTIVE);
        }
        return toReleaseView(release);
    }

    // @@SERVICE_APPEND@@
    /**
     * 暂停后把批次内所有已下发设备（含已成功的）切到 ROLLING_BACK。
     * 触发设备之外的设备在批次锁内整体清扫；触发设备本身的状态由当前线程稍后推进，
     * 避免在其自身锁等待期间被其他线程重复修改。
     */
    private void sweepRollback(Release release, Batch batch, DeviceUpgrade trigger) {
        for (DeviceUpgrade upgrade : upgrades.get(release.getReleaseId()).values()) {
            if (upgrade.getBatchIndex() != batch.getIndex()) {
                continue;
            }
            if (upgrade == trigger) {
                continue;
            }
            synchronized (upgrade.lock) {
                synchronized (batch) {
                    if (batch.getStatus() != BatchStatus.PAUSED) {
                        return;
                    }
                }
                DeviceState state = upgrade.getState();
                if (state != DeviceState.ROLLING_BACK && state != DeviceState.ROLLBACK_COMPLETE) {
                    upgrade.setState(DeviceState.ROLLING_BACK);
                }
            }
        }
    }

    public ProgressResponse requestRollback(String releaseId, String deviceId) {
        Device device = requireDevice(deviceId);
        Release release = requireRelease(releaseId);
        DeviceUpgrade upgrade = upgrades.get(releaseId).get(deviceId);
        if (upgrade == null) {
            throw new RolloutException(RejectReason.ILLEGAL_STATE, "设备尚未获得下发，无需回滚");
        }
        synchronized (upgrade.lock) {
            DeviceState state = upgrade.getState();
            if (state == DeviceState.ROLLBACK_COMPLETE) {
                throw new RolloutException(RejectReason.ALREADY_ROLLED_BACK);
            }
            if (state != DeviceState.ROLLING_BACK) {
                upgrade.setState(DeviceState.ROLLING_BACK);
            }
        }
        Batch batch = release.getBatches().get(upgrade.getBatchIndex());
        return new ProgressResponse("ROLL_BACK", deviceId, releaseId, DeviceState.ROLLING_BACK,
                device.getCurrentVersion(), batch.getStatus(),
                "请回滚至原版本 " + upgrade.getFromVersion());
    }

    private void evaluateBatchRolledBack(Batch batch, Release release) {
        synchronized (batch) {
            if (batch.getStatus() != BatchStatus.PAUSED) {
                return;
            }
        }
        int total = batch.dispatched().get();
        long done = upgrades.get(release.getReleaseId()).values().stream()
                .filter(u -> u.getBatchIndex() == batch.getIndex())
                .filter(u -> u.getState() == DeviceState.ROLLBACK_COMPLETE)
                .count();
        synchronized (batch) {
            if (batch.getStatus() == BatchStatus.PAUSED && total > 0 && done >= total) {
                batch.setStatus(BatchStatus.ROLLED_BACK);
            }
        }
    }

    private void evaluateBatchCompleted(Batch batch, Release release) {
        synchronized (batch) {
            int targets = batch.targets().get();
            if (batch.getStatus() == BatchStatus.ACTIVE && targets > 0
                    && batch.success().get() >= targets) {
                batch.setStatus(BatchStatus.COMPLETED);
            }
        }
    }

    public ReleaseView getRelease(String releaseId) {
        return toReleaseView(requireRelease(releaseId));
    }

    private Device requireDevice(String deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new RolloutException(RejectReason.UNKNOWN_DEVICE, deviceId);
        }
        return device;
    }

    private Release requireRelease(String releaseId) {
        Release release = releases.get(releaseId);
        if (release == null) {
            throw new RolloutException(RejectReason.RELEASE_NOT_FOUND, releaseId);
        }
        return release;
    }

    private ReleaseView toReleaseView(Release release) {
        List<BatchView> views = release.getBatches().stream().map(batch -> {
            int decided = batch.success().get() + batch.failed().get();
            double rate = decided == 0 ? 0d : (double) batch.failed().get() / decided;
            return new BatchView(batch.getIndex(), batch.getRolloutPercent(), batch.getStatus(),
                    batch.dispatched().get(), batch.targets().get(),
                    batch.success().get(), batch.failed().get(),
                    batch.rolledBack().get(), rate);
        }).toList();
        return new ReleaseView(release.getReleaseId(), release.getTargetVersion(),
                release.getFailureRateThreshold(), release.getMinDecidedForThreshold(), views);
    }
    public ProgressResponse reportProgress(String releaseId, String deviceId, String stateText) {
        Device device = requireDevice(deviceId);
        Release release = requireRelease(releaseId);
        DeviceState reported;
        try {
            reported = DeviceState.valueOf(stateText);
        } catch (IllegalArgumentException e) {
            throw new RolloutException(RejectReason.ILLEGAL_STATE, "未知状态: " + stateText);
        }
        if (!reported.isReportable()) {
            throw new RolloutException(RejectReason.ILLEGAL_STATE, stateText + " 为服务端内部状态");
        }
        DeviceUpgrade upgrade = upgrades.get(releaseId).get(deviceId);
        if (upgrade == null) {
            throw new RolloutException(RejectReason.ILLEGAL_STATE, "设备尚未获得该版本下发");
        }
        Batch batch = release.getBatches().get(upgrade.getBatchIndex());

        boolean changed;
        boolean needPauseSweep = false;
        boolean checkCompleted = false;
        boolean checkRolledBack = false;
        synchronized (upgrade.lock) {
            DeviceState current = upgrade.getState();
            if (current == reported) {
                throw new RolloutException(current == DeviceState.ROLLBACK_COMPLETE
                        ? RejectReason.ALREADY_ROLLED_BACK : RejectReason.DUPLICATE_PROGRESS);
            }
            validateTransition(current, reported);
            applyStateChange(upgrade, reported);
            changed = true;

            if (reported == DeviceState.INSTALL_FAILED
                    && batch.getStatus() == BatchStatus.ACTIVE) {
                synchronized (batch) {
                    int decided = batch.success().get() + batch.failed().get();
                    double rate = (double) batch.failed().get() / decided;
                    if (decided >= release.getMinDecidedForThreshold()
                            && rate >= release.getFailureRateThreshold()) {
                        batch.setStatus(BatchStatus.PAUSED);
                        needPauseSweep = true;
                    }
                }
            } else if (reported == DeviceState.ROLLBACK_COMPLETE) {
                checkRolledBack = true;
            } else if (reported == DeviceState.INSTALL_SUCCESS) {
                checkCompleted = true;
            }
        }

        if (needPauseSweep) {
            sweepRollback(release, batch, upgrade);
            synchronized (upgrade.lock) {
                if (upgrade.getState() == DeviceState.INSTALL_FAILED) {
                    upgrade.setState(DeviceState.ROLLING_BACK);
                }
            }
        } else if (checkRolledBack) {
            evaluateBatchRolledBack(batch, release);
        } else if (checkCompleted) {
            evaluateBatchCompleted(batch, release);
        }

        DeviceState stateNow;
        String action = "ACCEPTED";
        synchronized (upgrade.lock) {
            stateNow = upgrade.getState();
            if (stateNow == DeviceState.ROLLING_BACK) {
                action = "ROLL_BACK";
            }
        }
        return new ProgressResponse(action, deviceId, releaseId, stateNow,
                device.getCurrentVersion(), batch.getStatus(),
                changed ? "进度已更新" : "进度未变化");
    }

    private void validateTransition(DeviceState current, DeviceState reported) {
        if (current == DeviceState.ROLLING_BACK) {
            if (reported != DeviceState.ROLLBACK_COMPLETE) {
                throw new RolloutException(RejectReason.ILLEGAL_STATE,
                        "回滚进行中只接受 ROLLBACK_COMPLETE，实际收到 " + reported);
            }
            return;
        }
        if (current == DeviceState.ROLLBACK_COMPLETE) {
            throw new RolloutException(RejectReason.ILLEGAL_STATE, "回滚已结束");
        }
        if (reported == DeviceState.ROLLBACK_COMPLETE) {
            throw new RolloutException(RejectReason.ILLEGAL_STATE,
                    "未进入 ROLLING_BACK 不允许直接上报回滚完成");
        }
        if (reported.progress() < current.progress()) {
            throw new RolloutException(RejectReason.STALE_STATE,
                    current + " -> " + reported);
        }
    }

    private void applyStateChange(DeviceUpgrade upgrade, DeviceState reported) {
        Device device = devices.get(upgrade.getDeviceId());
        Batch batch = releases.get(upgrade.getReleaseId())
                .getBatches().get(upgrade.getBatchIndex());
        switch (reported) {
            case INSTALL_SUCCESS -> {
                upgrade.setState(DeviceState.INSTALL_SUCCESS);
                batch.success().incrementAndGet();
                device.setCurrentVersion(upgrade.getTargetVersion());
            }
            case INSTALL_FAILED -> {
                upgrade.setState(DeviceState.INSTALL_FAILED);
                batch.failed().incrementAndGet();
            }
            case ROLLBACK_COMPLETE -> {
                upgrade.setState(DeviceState.ROLLBACK_COMPLETE);
                batch.rolledBack().incrementAndGet();
                device.setCurrentVersion(upgrade.getFromVersion());
            }
            default -> upgrade.setState(reported);
        }
    }
    public DispatchResponse dispatch(String releaseId, String deviceId) {
        Device device = requireDevice(deviceId);
        Release release = requireRelease(releaseId);
        DeviceUpgrade existing = upgrades.get(releaseId).get(deviceId);
        if (existing != null) {
            synchronized (existing.lock) {
                DeviceState state = existing.getState();
                if (state == DeviceState.ROLLING_BACK) {
                    return new DispatchResponse("ROLL_BACK", releaseId, existing.getFromVersion(),
                            existing.getTargetVersion(), existing.getBatchIndex(), state,
                            "批次触发回滚，请回滚至原版本 " + existing.getFromVersion());
                }
                Batch existingBatch = release.getBatches().get(existing.getBatchIndex());
                if (existingBatch.getStatus() == BatchStatus.PAUSED) {
                    existing.setState(DeviceState.ROLLING_BACK);
                    return new DispatchResponse("ROLL_BACK", releaseId, existing.getFromVersion(),
                            existing.getTargetVersion(), existing.getBatchIndex(),
                            DeviceState.ROLLING_BACK,
                            "批次已暂停，请回滚至原版本 " + existing.getFromVersion());
                }
                if (existingBatch.getStatus() == BatchStatus.ROLLED_BACK
                        || state == DeviceState.ROLLBACK_COMPLETE) {
                    throw new RolloutException(RejectReason.BATCH_PAUSED, "设备已完成回滚，不再下发");
                }
                return new DispatchResponse("IDEMPOTENT", releaseId, release.getTargetVersion(),
                        existing.getFromVersion(), existing.getBatchIndex(), state,
                        "同一设备同一版本的重复下发，幂等忽略");
            }
        }
        int[] cumulative = release.getBatches().stream()
                .mapToInt(Batch::getRolloutPercent).toArray();
        int batchIndex = BatchSelector.selectBatchIndex(releaseId, deviceId, cumulative);
        if (batchIndex < 0) {
            throw new RolloutException(RejectReason.NOT_IN_TARGET,
                    "设备槽位超出全部批次累计覆盖范围");
        }
        Batch batch = release.getBatches().get(batchIndex);
        switch (batch.getStatus()) {
            case PENDING, COMPLETED -> throw new RolloutException(RejectReason.NOT_IN_TARGET,
                    "所属批次 " + batchIndex + " 状态为 " + batch.getStatus());
            case PAUSED, ROLLED_BACK -> throw new RolloutException(RejectReason.BATCH_PAUSED,
                    "所属批次 " + batchIndex + " 已暂停/回滚");
            default -> {
            }
        }

        String target = release.getTargetVersion();
        int compared = SemanticVersion.parse(device.getCurrentVersion())
                .compareTo(SemanticVersion.parse(target));
        if (compared > 0) {
            throw new RolloutException(RejectReason.VERSION_DOWNGRADE,
                    "当前 " + device.getCurrentVersion() + " -> 目标 " + target);
        }
        if (compared == 0) {
            return new DispatchResponse("IDEMPOTENT", releaseId, target,
                    device.getCurrentVersion(), batchIndex, DeviceState.DISPATCHED,
                    RejectReason.ALREADY_UP_TO_DATE.getDescription());
        }
        DeviceUpgrade candidate = new DeviceUpgrade(deviceId, releaseId, batchIndex,
                target, device.getCurrentVersion());
        DeviceUpgrade upgrade = upgrades.get(releaseId).putIfAbsent(deviceId, candidate);
        if (upgrade == null) {
            upgrade = candidate;
        }
        synchronized (upgrade.lock) {
            if (upgrade != candidate) {
                DeviceState state = upgrade.getState();
                if (state == DeviceState.ROLLING_BACK) {
                    return new DispatchResponse("ROLL_BACK", releaseId, upgrade.getFromVersion(),
                            upgrade.getTargetVersion(), upgrade.getBatchIndex(), state,
                            "批次触发回滚，请回滚至原版本 " + upgrade.getFromVersion());
                }
                if (state == DeviceState.ROLLBACK_COMPLETE) {
                    throw new RolloutException(RejectReason.BATCH_PAUSED, "设备已完成回滚，不再下发");
                }
                return new DispatchResponse("IDEMPOTENT", releaseId, target,
                        upgrade.getFromVersion(), upgrade.getBatchIndex(), state,
                        "同一设备同一版本的重复下发，幂等忽略");
            }
            batch.dispatched().incrementAndGet();
            batch.targets().incrementAndGet();
            return new DispatchResponse("DISPATCHED", releaseId, target,
                    upgrade.getFromVersion(), batchIndex, DeviceState.DISPATCHED,
                    "已纳入批次 " + batchIndex + "，开始灰度升级");
        }
    }
}
