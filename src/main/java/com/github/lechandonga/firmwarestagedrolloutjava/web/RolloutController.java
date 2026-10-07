package com.github.lechandonga.firmwarestagedrolloutjava.web;

import com.github.lechandonga.firmwarestagedrolloutjava.exception.BusinessException;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.model.ProgressEvent;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Upgrade;
import com.github.lechandonga.firmwarestagedrolloutjava.service.DispatchResult;
import com.github.lechandonga.firmwarestagedrolloutjava.service.HeartbeatResult;
import com.github.lechandonga.firmwarestagedrolloutjava.service.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import com.github.lechandonga.firmwarestagedrolloutjava.web.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.web.dto.Requests.DispatchRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.web.dto.Requests.HeartbeatRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.web.dto.Requests.RegisterDeviceRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RolloutController {

    private final RolloutService service;

    public RolloutController(RolloutService service) {
        this.service = service;
    }

    @PostMapping("/devices")
    public Map<String, Object> register(@RequestBody RegisterDeviceRequest request) {
        require(request != null, "请求体不能为空");
        return Map.of("deviceId",
                service.registerDevice(request.deviceId(), request.currentVersion()).getDeviceId());
    }

    @PostMapping("/releases")
    public ReleaseView createRelease(@RequestBody CreateReleaseRequest request) {
        require(request != null, "请求体不能为空");
        service.createRelease(request.releaseId(), request.targetVersion(),
                request.stagesPercent(), request.failureRateThreshold(),
                request.minSamplesForThreshold());
        return service.releaseView(request.releaseId());
    }

    @PostMapping("/releases/{releaseId}/advance")
    public ReleaseView advance(@PathVariable String releaseId) {
        service.advanceStage(releaseId);
        return service.releaseView(releaseId);
    }

    @PostMapping("/releases/{releaseId}/pause")
    public ReleaseView pause(@PathVariable String releaseId) {
        service.pause(releaseId);
        return service.releaseView(releaseId);
    }

    @PostMapping("/releases/{releaseId}/resume")
    public ReleaseView resume(@PathVariable String releaseId) {
        service.resume(releaseId);
        return service.releaseView(releaseId);
    }

    @PostMapping("/dispatch")
    public DispatchResult dispatch(@RequestBody DispatchRequest request) {
        require(request != null, "请求体不能为空");
        return service.dispatch(request.deviceId(), request.releaseId());
    }

    @PostMapping("/heartbeat")
    public HeartbeatResult heartbeat(@RequestBody HeartbeatRequest request) {
        require(request != null, "请求体不能为空");
        ProgressEvent event = parseEvent(request.event());
        return service.heartbeat(request.deviceId(), request.releaseId(),
                request.version(), event);
    }

    @GetMapping("/releases/{releaseId}")
    public ReleaseView release(@PathVariable String releaseId) {
        return service.releaseView(releaseId);
    }

    @GetMapping("/devices/{deviceId}/releases/{releaseId}")
    public Map<String, Object> upgrade(@PathVariable String deviceId,
                                       @PathVariable String releaseId) {
        Upgrade upgrade = service.upgradeView(deviceId, releaseId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceId", upgrade.getDeviceId());
        view.put("releaseId", upgrade.getReleaseId());
        view.put("targetVersion", upgrade.getTargetVersion());
        view.put("previousVersion", upgrade.getPreviousVersion());
        view.put("state", upgrade.getState().name());
        view.put("activeVersion", upgrade.getActiveVersion());
        view.put("stageIndex", upgrade.getStageIndex());
        return view;
    }

    private ProgressEvent parseEvent(String raw) {
        require(raw != null && !raw.isBlank(), "event 不能为空");
        try {
            return ProgressEvent.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RejectReason.INVALID_REQUEST,
                    "未知进度事件: " + raw);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(RejectReason.INVALID_REQUEST, message);
        }
    }
}
