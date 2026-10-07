package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 设备在一次发布中的升级任务（deviceId + releaseId 唯一）。
 */
public final class Upgrade {
    private final String deviceId;
    private final String releaseId;
    private final String targetVersion;
    private final String previousVersion;
    private final int stageIndex;

    private DeviceState state;
    /** 设备在本次任务中实际生效过的版本（安装成功后更新，回滚完成后回到旧版本） */
    private String activeVersion;

    public Upgrade(String deviceId, String releaseId, String targetVersion,
                   String previousVersion, int stageIndex) {
        this.deviceId = deviceId;
        this.releaseId = releaseId;
        this.targetVersion = targetVersion;
        this.previousVersion = previousVersion;
        this.stageIndex = stageIndex;
        this.state = DeviceState.ASSIGNED;
        this.activeVersion = previousVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public int getStageIndex() {
        return stageIndex;
    }

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState state) {
        this.state = state;
    }

    public String getActiveVersion() {
        return activeVersion;
    }

    public void setActiveVersion(String activeVersion) {
        this.activeVersion = activeVersion;
    }

    public boolean isOnNewVersion() {
        return state == DeviceState.INSTALLED && targetVersion.equals(activeVersion);
    }
}
