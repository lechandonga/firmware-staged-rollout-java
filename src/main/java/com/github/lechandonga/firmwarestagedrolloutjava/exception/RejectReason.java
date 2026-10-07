package com.github.lechandonga.firmwarestagedrolloutjava.exception;

/**
 * 可区分的业务拒绝原因，同时作为返回错误体的错误码。
 */
public enum RejectReason {
    /** 设备未注册（未知设备） */
    UNKNOWN_DEVICE,
    /** 发布不存在 */
    UNKNOWN_RELEASE,
    /** 请求参数非法（版本号、批次配置等） */
    INVALID_REQUEST,
    /** 设备不在当前批次的灰度目标集合内 */
    NOT_IN_TARGET,
    /** 普通升级不允许降级 */
    DOWNGRADE_NOT_ALLOWED,
    /** 普通升级不允许同版本重装 */
    SAME_VERSION_REINSTALL,
    /** 发布处于暂停/回滚/终态，不接受新下发 */
    DISPATCH_BLOCKED_BY_STATUS,
    /** 设备上报了自己没有被下发的发布，或上报版本与目标版本不一致 */
    UPGRADE_NOT_FOUND,
    /** 迟到的旧状态：上报的事件落后于设备当前状态 */
    STALE_EVENT,
    /** 非法状态流转（跳跃、终态后再流转等） */
    ILLEGAL_TRANSITION,
    /** 设备已在回滚路径上，重复/错误的普通上报 */
    NOT_IN_ROLLBACK,
    /** 发布已经是终态，不能暂停/推进/恢复 */
    RELEASE_NOT_ACTIVE
}
