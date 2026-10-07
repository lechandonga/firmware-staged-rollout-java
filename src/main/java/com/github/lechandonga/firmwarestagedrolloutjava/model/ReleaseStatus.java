package com.github.lechandonga.firmwarestagedrolloutjava.model;

/**
 * 一次灰度发布的整体状态。
 */
public enum ReleaseStatus {
    /** 灰度进行中（可以向当前批次下发） */
    ACTIVE,
    /** 已手动暂停：不再下发，但尚未触发回滚，可恢复 */
    PAUSED,
    /** 失败率超阈值，已自动暂停并正在回滚 */
    ROLLING_BACK,
    /** 回滚已全部完成（终态） */
    ROLLED_BACK,
    /** 全部批次已下发完成 */
    COMPLETED
}
