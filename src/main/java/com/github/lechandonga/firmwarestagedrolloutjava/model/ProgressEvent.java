package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 设备通过心跳上报的升级进度事件。
 */
public enum ProgressEvent {
    /** 下载中 */
    DOWNLOADING,
    /** 待安装（下载完成，等待安装） */
    PENDING_INSTALL,
    /** 安装成功 */
    INSTALLED,
    /** 安装失败 */
    INSTALL_FAILED,
    /** 回滚完成（设备已回到上一个稳定版本，是回滚路径的终态） */
    ROLLBACK_DONE
}
