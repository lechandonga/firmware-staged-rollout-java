package com.github.lechandonga.firmwarestagedrolloutjava.dto;

import com.github.lechandonga.firmwarestagedrolloutjava.model.BatchStatus;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;

import java.util.List;

public final class Responses {

    private Responses() {
    }

    public record DispatchResponse(
            String result,
            String releaseId,
            String targetVersion,
            String fromVersion,
            int batchIndex,
            DeviceState deviceState,
            String message) {
    }

    public record ProgressResponse(
            String result,
            String deviceId,
            String releaseId,
            DeviceState state,
            String currentVersion,
            BatchStatus batchStatus,
            String message) {
    }

    public record BatchView(
            int index,
            int rolloutPercent,
            BatchStatus status,
            int dispatched,
            int targets,
            int success,
            int failed,
            int rolledBack,
            double failureRate) {
    }

    public record ReleaseView(
            String releaseId,
            String targetVersion,
            double failureRateThreshold,
            int minDecidedForThreshold,
            List<BatchView> batches) {
    }

    public record DeviceView(
            String deviceId,
            String currentVersion) {
    }

    public record ErrorBody(String code, String message) {
    }
}
