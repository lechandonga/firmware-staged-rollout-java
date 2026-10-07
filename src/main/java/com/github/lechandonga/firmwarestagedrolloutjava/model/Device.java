package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 已注册设备。固件以版本号标识，不涉及真实文件。
 */
public final class Device {
    private final String deviceId;
    /** 设备当前实际运行的固件版本 */
    private String currentVersion;

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
