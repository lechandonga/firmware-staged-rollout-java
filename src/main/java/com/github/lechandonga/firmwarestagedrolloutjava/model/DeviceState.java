package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 设备升级进度状态，顺序值只用于“新旧状态”判定，不代表允许直接跳转。
 */
public enum DeviceState {
    /** 内部态：已被批次选中并下发，设备尚未开始下载。 */
    DISPATCHED(0, false, false),
    /** 设备上报：下载中。 */
    DOWNLOADING(1, false, false),
    /** 设备上报：下载完成，待安装。 */
    PENDING_INSTALL(2, false, false),
    /** 设备上报：安装成功。 */
    INSTALL_SUCCESS(3, true, false),
    /** 设备上报：安装失败。 */
    INSTALL_FAILED(4, false, true),
    /** 内部态：触发回滚，等待设备执行回滚（设备侧可重复看到该指令）。 */
    ROLLING_BACK(5, false, false),
    /** 设备上报：回滚完成，已回到升级前版本。 */
    ROLLBACK_COMPLETE(6, false, true);

    private final int progress;
    private final boolean successTerminal;
    private final boolean failureTerminal;

    DeviceState(int progress, boolean successTerminal, boolean failureTerminal) {
        this.progress = progress;
        this.successTerminal = successTerminal;
        this.failureTerminal = failureTerminal;
    }

    public int progress() {
        return progress;
    }

    public boolean isSuccessTerminal() {
        return successTerminal;
    }

    public boolean isFailureTerminal() {
        return failureTerminal;
    }

    /**
     * 设备心跳允许上报的状态。
     */
    public boolean isReportable() {
        return this != DISPATCHED && this != ROLLING_BACK;
    }
}
