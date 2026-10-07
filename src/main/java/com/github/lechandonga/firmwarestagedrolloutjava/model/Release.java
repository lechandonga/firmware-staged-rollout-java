package com.github.lechandonga.firmwarestagedrolloutjava.model;

import java.util.List;

/**
 * 一次固件灰度发布。
 */
public class Release {

    private final String releaseId;
    private final String targetVersion;
    private final List<Batch> batches;
    /** 失败率阈值（0-1）：已结束设备（成功+失败）中失败占比达到/超过即暂停。 */
    private final double failureRateThreshold;
    /** 触发失败率统计所需的最小已结束设备数，避免小样本抖动。 */
    private final int minDecidedForThreshold;

    public Release(String releaseId, String targetVersion, List<Batch> batches,
                   double failureRateThreshold, int minDecidedForThreshold) {
        this.releaseId = releaseId;
        this.targetVersion = targetVersion;
        this.batches = List.copyOf(batches);
        this.failureRateThreshold = failureRateThreshold;
        this.minDecidedForThreshold = minDecidedForThreshold;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public List<Batch> getBatches() {
        return batches;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public int getMinDecidedForThreshold() {
        return minDecidedForThreshold;
    }
}
