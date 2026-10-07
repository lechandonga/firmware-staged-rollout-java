package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 一次发布中单个设备的升级记录（服务端持有唯一锁对象做细粒度并发控制）。
 */
public class DeviceUpgrade {

    public final Object lock = new Object();

    private final String deviceId;
    private final String releaseId;
    private final int batchIndex;
    private final String targetVersion;
    private final String fromVersion;

    private DeviceState state = DeviceState.DISPATCHED;
    private long lastUpdateMillis = System.currentTimeMillis();

    public DeviceUpgrade(String deviceId, String releaseId, int batchIndex,
                         String targetVersion, String fromVersion) {
        this.deviceId = deviceId;
        this.releaseId = releaseId;
        this.batchIndex = batchIndex;
        this.targetVersion = targetVersion;
        this.fromVersion = fromVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState state) {
        this.state = state;
        this.lastUpdateMillis = System.currentTimeMillis();
    }

    public long getLastUpdateMillis() {
        return lastUpdateMillis;
    }
}
