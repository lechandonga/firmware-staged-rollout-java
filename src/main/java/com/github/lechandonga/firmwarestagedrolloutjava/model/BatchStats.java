package com.github.lechandonga.firmwarestagedrolloutjava.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个批次的统计计数器。所有计数均在发布服务的锁内更新，
 * 使用原子类型方便对外读取一致快照。
 */
public final class BatchStats {
    /** 已下发（进入本批次升级流程）的设备数 */
    public final AtomicInteger assigned = new AtomicInteger();
    /** 安装成功（新版本生效）过的设备数，历史口径，去重计数 */
    public final AtomicInteger installed = new AtomicInteger();
    /** 安装失败过的设备数，历史口径，去重计数 */
    public final AtomicInteger failed = new AtomicInteger();
    /** 当前已回滚完成的设备数，去重计数 */
    public final AtomicInteger rolledBack = new AtomicInteger();
}
