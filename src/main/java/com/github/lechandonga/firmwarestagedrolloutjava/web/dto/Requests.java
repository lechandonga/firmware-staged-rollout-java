package com.github.lechandonga.firmwarestagedrolloutjava.web.dto;

import java.util.List;

public final class Requests {

    private Requests() {
    }

    public record RegisterDeviceRequest(String deviceId, String currentVersion) {
    }

    public record CreateReleaseRequest(String releaseId, String targetVersion,
                                       List<Integer> stagesPercent,
                                       Double failureRateThreshold,
                                       Integer minSamplesForThreshold) {
    }

    public record DispatchRequest(String deviceId, String releaseId) {
    }

    public record HeartbeatRequest(String deviceId, String releaseId, String version,
                                   String event) {
    }
}
