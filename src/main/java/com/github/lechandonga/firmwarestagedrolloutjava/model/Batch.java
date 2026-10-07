package com.github.lechandonga.firmwarestagedrolloutjava.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 灰度批次：rolloutPercent 为截至本批次累计覆盖比例（0-100）。
 */
public class Batch {

    private final int index;
    private final int rolloutPercent;
    private volatile BatchStatus status = BatchStatus.PENDING;

    private final AtomicInteger dispatched = new AtomicInteger();
    /** 已确认纳入批次的设备数（重复下发不增加）。 */
    private final AtomicInteger targets = new AtomicInteger();
    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger rolledBack = new AtomicInteger();

    public Batch(int index, int rolloutPercent) {
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("灰度比例必须在 0-100 之间");
        }
        this.index = index;
        this.rolloutPercent = rolloutPercent;
    }

    public int getIndex() {
        return index;
    }

    public int getRolloutPercent() {
        return rolloutPercent;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public AtomicInteger dispatched() {
        return dispatched;
    }

    public AtomicInteger targets() {
        return targets;
    }

    public AtomicInteger success() {
        return success;
    }

    public AtomicInteger failed() {
        return failed;
    }

    public AtomicInteger rolledBack() {
        return rolledBack;
    }
}
