package com.github.lechandonga.firmwarestagedrolloutjava.exception;

/**
 * 可区分的拒绝原因。{@code idempotent} 为 true 表示重复请求但结果幂等（HTTP 200）。
 */
public enum RejectReason {
    UNKNOWN_DEVICE(false, "设备未注册"),
    RELEASE_NOT_FOUND(false, "发布不存在"),
    NOT_IN_TARGET(false, "设备不属于任何已激活批次的目标集合"),
    BATCH_PAUSED(false, "批次已暂停，不再向新设备下发"),
    VERSION_DOWNGRADE(false, "普通升级不允许降级"),
    VERSION_REINSTALL(false, "不允许同版本重装"),
    ILLEGAL_STATE(false, "非法状态流转"),
    STALE_STATE(false, "迟到的旧状态已被忽略"),
    ALREADY_UP_TO_DATE(true, "设备已在该版本，重复下发幂等忽略"),
    DUPLICATE_PROGRESS(true, "重复进度上报，幂等忽略"),
    ALREADY_ROLLED_BACK(true, "设备已完成回滚，重复上报幂等忽略");

    private final boolean idempotent;
    private final String description;

    RejectReason(boolean idempotent, String description) {
        this.idempotent = idempotent;
        this.description = description;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public String getDescription() {
        return description;
    }
}
