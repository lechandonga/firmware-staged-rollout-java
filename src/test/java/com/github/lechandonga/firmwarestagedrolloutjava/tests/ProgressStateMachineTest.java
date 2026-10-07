package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ProgressResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RolloutException;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务域：升级状态机 —— 正常流转、重复上报幂等拒绝、乱序/迟到旧状态拒绝、
 * 非法流转拒绝、回滚是唯一降级路径、未知设备区分拒绝。
 */
@ExtendWith(BusinessLoggingExtension.class)
class ProgressStateMachineTest {

    private RolloutService service;
    private String deviceId;

    @BeforeEach
    void setUp() {
        service = new RolloutService();
        service.registerDevice("dev-1", "1.0.0");
        service.createRelease(new CreateReleaseRequest(
                "rel", "2.0.0", List.of(100), 0.9, 1000));
        service.activateBatch("rel", 0);
        assertEquals("DISPATCHED", service.dispatch("rel", "dev-1").result());
        deviceId = "dev-1";
    }

    @Test
    @DisplayName("正常顺序: DOWNLOADING -> PENDING_INSTALL -> INSTALL_SUCCESS, 版本切到 2.0.0")
    void happyPath_updatesVersion() {
        assertEquals(DeviceState.DOWNLOADING, report("DOWNLOADING").state());
        assertEquals(DeviceState.PENDING_INSTALL, report("PENDING_INSTALL").state());
        ProgressResponse done = report("INSTALL_SUCCESS");
        assertEquals(DeviceState.INSTALL_SUCCESS, done.state());
        assertEquals("2.0.0", done.currentVersion());
    }

    @Test
    @DisplayName("重复上报同一状态: DUPLICATE_PROGRESS 幂等拒绝, 计数不增加")
    void duplicateReport_isIdempotent() {
        report("DOWNLOADING");
        report("PENDING_INSTALL");
        report("INSTALL_SUCCESS");
        RolloutException ex = assertThrows(RolloutException.class,
                () -> report("INSTALL_SUCCESS"));
        assertEquals(RejectReason.DUPLICATE_PROGRESS, ex.getReason());
        assertEquals(1, service.getRelease("rel").batches().get(0).success());
    }

    @Test
    @DisplayName("乱序迟到的旧状态: STALE_STATE 拒绝, 不覆盖更新的状态")
    void staleState_neverOverwrites() {
        report("DOWNLOADING");
        report("PENDING_INSTALL");
        RolloutException ex = assertThrows(RolloutException.class,
                () -> report("DOWNLOADING"));
        assertEquals(RejectReason.STALE_STATE, ex.getReason());
        assertEquals(0, service.getRelease("rel").batches().get(0).success(),
                "被拒绝的迟到状态不产生任何计数");
    }

    @Test
    @DisplayName("安装失败后走回滚路径: 失败计数 +1, 回滚完成后版本回到 1.0.0")
    void failureThenRollback_restoresPreviousVersion() {
        report("DOWNLOADING");
        report("PENDING_INSTALL");
        ProgressResponse failed = report("INSTALL_FAILED");
        assertEquals(DeviceState.INSTALL_FAILED, failed.state());

        ProgressResponse rollback = service.requestRollback("rel", deviceId);
        assertEquals(DeviceState.ROLLING_BACK, rollback.state());

        RolloutException illegal = assertThrows(RolloutException.class,
                () -> report("DOWNLOADING"));
        assertEquals(RejectReason.ILLEGAL_STATE, illegal.getReason(),
                "回滚进行中不允许普通进度");

        ProgressResponse restored = report("ROLLBACK_COMPLETE");
        assertEquals(DeviceState.ROLLBACK_COMPLETE, restored.state());
        assertEquals("1.0.0", restored.currentVersion());

        RolloutException duplicate = assertThrows(RolloutException.class,
                () -> report("ROLLBACK_COMPLETE"));
        assertEquals(RejectReason.ALREADY_ROLLED_BACK, duplicate.getReason());
    }

    @Test
    @DisplayName("未下发直接上报回滚完成: ILLEGAL_STATE 拒绝")
    void rollbackCompleteWithoutDispatch_rejected() {
        service.registerDevice("dev-2", "1.0.0");
        RolloutException ex = assertThrows(RolloutException.class,
                () -> service.reportProgress("rel", "dev-2", "ROLLBACK_COMPLETE"));
        assertEquals(RejectReason.ILLEGAL_STATE, ex.getReason());
    }

    @Test
    @DisplayName("未知设备/未知状态/未知发布: 拒绝原因相互可区分")
    void unknownEntities_areDistinguishable() {
        RolloutException unknownDevice = assertThrows(RolloutException.class,
                () -> service.reportProgress("rel", "ghost", "DOWNLOADING"));
        assertEquals(RejectReason.UNKNOWN_DEVICE, unknownDevice.getReason());

        RolloutException unknownRelease = assertThrows(RolloutException.class,
                () -> service.reportProgress("nope", deviceId, "DOWNLOADING"));
        assertEquals(RejectReason.RELEASE_NOT_FOUND, unknownRelease.getReason());

        RolloutException badState = assertThrows(RolloutException.class,
                () -> service.reportProgress("rel", deviceId, "BRICKED"));
        assertEquals(RejectReason.ILLEGAL_STATE, badState.getReason());
    }

    private ProgressResponse report(String state) {
        return service.reportProgress("rel", deviceId, state);
    }
}
