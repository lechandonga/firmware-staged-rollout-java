package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 设备档案：版本号即固件标识，不涉及真实固件文件。
 */
public class Device {

    private final String deviceId;
    /** 当前实际运行版本（注册版本 / 升级成功后的版本 / 回滚完成后的原版本）。 */
    private volatile String currentVersion;

    public Device(String deviceId, String currentVersion) {
        this.deviceId = deviceId;
        this.currentVersion = currentVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }
}
