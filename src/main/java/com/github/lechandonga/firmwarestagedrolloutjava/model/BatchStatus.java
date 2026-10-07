package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 批次状态。
 */
public enum BatchStatus {
    /** 尚未激活：设备不属于当前灰度范围。 */
    PENDING,
    /** 灰度进行中：可向目标设备下发。 */
    ACTIVE,
    /** 失败率超阈值，自动暂停：停止向新设备下发，等待/执行回滚。 */
    PAUSED,
    /** 批次内设备全部回到上一个版本，回滚收尾完成。 */
    ROLLED_BACK,
    /** 批次目标设备全部安装成功。 */
    COMPLETED
}
