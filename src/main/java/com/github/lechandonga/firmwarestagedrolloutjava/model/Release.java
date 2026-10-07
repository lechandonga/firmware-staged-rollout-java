package com.github.lechandonga.firmwarestagedrolloutjava.model;

import java.util.List;

/**
 * 一次灰度发布。
 *
 * @param stagesPercent 各批次的累计覆盖百分比（如 [10, 50, 100]），
 *                      第 0 个元素是第一批的覆盖范围，必须严格递增且以 100 收尾
 */
public final class Release {
    private final String releaseId;
    private final String targetVersion;
    private final List<Integer> stagesPercent;
    private final double failureRateThreshold;
    private final int minSamplesForThreshold;

    private ReleaseStatus status = ReleaseStatus.ACTIVE;
    private int currentStage;

    public Release(String releaseId, String targetVersion, List<Integer> stagesPercent,
                   double failureRateThreshold, int minSamplesForThreshold) {
        this.releaseId = releaseId;
        this.targetVersion = targetVersion;
        this.stagesPercent = List.copyOf(stagesPercent);
        this.failureRateThreshold = failureRateThreshold;
        this.minSamplesForThreshold = minSamplesForThreshold;
        this.currentStage = 0;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public List<Integer> getStagesPercent() {
        return stagesPercent;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public int getMinSamplesForThreshold() {
        return minSamplesForThreshold;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public void setStatus(ReleaseStatus status) {
        this.status = status;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(int currentStage) {
        this.currentStage = currentStage;
    }

    public int currentPercent() {
        return stagesPercent.get(currentStage);
    }

    public boolean isLastStage() {
        return currentStage == stagesPercent.size() - 1;
    }
}
