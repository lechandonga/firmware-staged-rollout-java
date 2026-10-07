package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 设备在一次升级任务（设备 + 发布）维度上的当前状态。
 */
public enum DeviceState {
    /** 已下发，等待设备开始下载 */
    ASSIGNED(0, false),
    /** 下载中 */
    DOWNLOADING(1, false),
    /** 待安装 */
    PENDING_INSTALL(2, false),
    /** 安装成功（新版本已生效） */
    INSTALLED(3, true),
    /** 安装失败 */
    INSTALL_FAILED(4, true),
    /** 回滚中（设备正在从上一个生效版本降回稳定版本） */
    ROLLING_BACK(5, false),
    /** 回滚完成，设备已回到稳定版本 */
    ROLLBACK_DONE(6, true);

    private final int rank;
    private final boolean terminal;

    DeviceState(int rank, boolean terminal) {
        this.rank = rank;
        this.terminal = terminal;
    }

    public int rank() {
        return rank;
    }

    public boolean terminal() {
        return terminal;
    }
}
