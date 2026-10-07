package com.github.lechandonga.firmwarestagedrolloutjava.web;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.ProgressReport;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.RegisterDeviceRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.RollbackRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.DeviceView;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.DispatchResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ProgressResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RolloutController {

    private final RolloutService rolloutService;

    public RolloutController(RolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceView register(@Valid @RequestBody RegisterDeviceRequest request) {
        return rolloutService.registerDevice(request.deviceId(), request.currentVersion());
    }

    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseView createRelease(@Valid @RequestBody CreateReleaseRequest request) {
        return rolloutService.createRelease(request);
    }

    /** 激活下一批灰度：批次从 PENDING 变为 ACTIVE，扩大覆盖范围。 */
    @PostMapping("/releases/{releaseId}/batches/{batchIndex}/activate")
    public ReleaseView activate(@PathVariable String releaseId, @PathVariable int batchIndex) {
        return rolloutService.activateBatch(releaseId, batchIndex);
    }

    /** 设备心跳拉取升级指令：服务端按批次归属与版本规则决定下发/拒绝/幂等。 */
    @PostMapping("/releases/{releaseId}/dispatch")
    public DispatchResponse dispatch(@PathVariable String releaseId,
                                     @RequestParam String deviceId) {
        return rolloutService.dispatch(releaseId, deviceId);
    }

    /** 设备上报升级进度。 */
    @PostMapping("/progress")
    public ProgressResponse report(@Valid @RequestBody ProgressReport report) {
        return rolloutService.reportProgress(report.releaseId(), report.deviceId(), report.state());
    }

    /** 显式触发单设备回滚（安装失败后的人工/策略路径）。 */
    @PostMapping("/rollback")
    public ProgressResponse rollback(@Valid @RequestBody RollbackRequest request) {
        return rolloutService.requestRollback(request.releaseId(), request.deviceId());
    }

    @GetMapping("/releases/{releaseId}")
    public ReleaseView status(@PathVariable String releaseId) {
        return rolloutService.getRelease(releaseId);
    }
}
