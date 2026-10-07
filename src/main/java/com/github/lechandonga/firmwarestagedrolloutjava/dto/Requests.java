package com.github.lechandonga.firmwarestagedrolloutjava.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class Requests {

    private Requests() {
    }

    public record RegisterDeviceRequest(
            @NotBlank String deviceId,
            @NotBlank String currentVersion) {
    }

    public record CreateReleaseRequest(
            @NotBlank String releaseId,
            @NotBlank String targetVersion,
            /** 累计灰度百分比，必须递增且最后一个可为 100，例如 [10, 50, 100]。 */
            @NotNull List<Integer> rolloutPercents,
            /** 失败率阈值，默认 0.3。 */
            Double failureRateThreshold,
            /** 触发阈值判定的最小已结束设备数，默认 3。 */
            Integer minDecidedForThreshold) {
    }

    public record ProgressReport(
            @NotBlank String deviceId,
            @NotBlank String releaseId,
            @NotBlank String state) {
    }

    public record RollbackRequest(
            @NotBlank String deviceId,
            @NotBlank String releaseId) {
    }
}
