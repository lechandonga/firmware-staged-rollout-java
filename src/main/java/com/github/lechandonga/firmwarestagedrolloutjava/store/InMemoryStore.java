package com.github.lechandonga.firmwarestagedrolloutjava.store;

import com.github.lechandonga.firmwarestagedrolloutjava.model.BatchStats;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Device;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Release;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Upgrade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内状态存储：不依赖数据库或任何外部服务。
 */
@Component
public class InMemoryStore {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Release> releases = new ConcurrentHashMap<>();
    /** deviceId -> (releaseId -> 升级任务) */
    private final Map<String, Map<String, Upgrade>> upgrades = new ConcurrentHashMap<>();
    /** releaseId -> 每批次统计 */
    private final Map<String, List<BatchStats>> stats = new ConcurrentHashMap<>();

    public Device putDevice(String deviceId, String currentVersion) {
        Device device = new Device(deviceId, currentVersion);
        devices.put(deviceId, device);
        return device;
    }

    public Optional<Device> findDevice(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public Device getDevice(String deviceId) {
        return devices.get(deviceId);
    }

    public Release putRelease(Release release) {
        releases.put(release.getReleaseId(), release);
        List<BatchStats> batchStats = new ArrayList<>();
        for (int i = 0; i < release.getStagesPercent().size(); i++) {
            batchStats.add(new BatchStats());
        }
        stats.put(release.getReleaseId(), batchStats);
        return release;
    }

    public Release getRelease(String releaseId) {
        return releases.get(releaseId);
    }

    public List<Release> allReleases() {
        return List.copyOf(releases.values());
    }

    public Upgrade putUpgrade(Upgrade upgrade) {
        upgrades.computeIfAbsent(upgrade.getDeviceId(), k -> new ConcurrentHashMap<>())
                .put(upgrade.getReleaseId(), upgrade);
        return upgrade;
    }

    public Upgrade getUpgrade(String deviceId, String releaseId) {
        Map<String, Upgrade> byRelease = upgrades.get(deviceId);
        return byRelease == null ? null : byRelease.get(releaseId);
    }

    public List<Upgrade> upgradesOfRelease(String releaseId) {
        List<Upgrade> result = new ArrayList<>();
        for (Map<String, Upgrade> byRelease : upgrades.values()) {
            Upgrade upgrade = byRelease.get(releaseId);
            if (upgrade != null) {
                result.add(upgrade);
            }
        }
        return result;
    }

    public BatchStats getBatchStats(String releaseId, int stageIndex) {
        return stats.get(releaseId).get(stageIndex);
    }

    public List<BatchStats> allBatchStats(String releaseId) {
        return stats.get(releaseId);
    }
}
