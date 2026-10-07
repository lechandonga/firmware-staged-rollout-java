package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.DispatchResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ProgressResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RolloutException;
import com.github.lechandonga.firmwarestagedrolloutjava.model.BatchStatus;
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
 * 业务域：失败率超阈值自动暂停与整批回滚 —— 暂停、停止新下发、
 * 全部设备沿回滚路径回到旧版本，最终设备状态、批次统计、整体进度一致。
 */
@ExtendWith(BusinessLoggingExtension.class)
class AutoPauseRollbackTest {

    private static final int DEVICE_COUNT = 20;
    private RolloutService service;

    @BeforeEach
    void setUp() {
        service = new RolloutService();
        for (int i = 1; i <= DEVICE_COUNT; i++) {
            service.registerDevice("dev-" + i, "1.0.0");
        }
        // 阈值 50%，至少 4 台已结束设备才触发，避免小样本抖动。
        service.createRelease(new CreateReleaseRequest(
                "rel", "2.0.0", List.of(100), 0.5, 4));
        service.activateBatch("rel", 0);
        for (int i = 1; i <= DEVICE_COUNT; i++) {
            assertEquals("DISPATCHED", service.dispatch("rel", "dev-" + i).result());
        }
    }

    @Test
    @DisplayName("未达最小样本数时不暂停; 达到阈值后自动 PAUSED 并停止新设备下发")
    void pauseStopsNewDispatch() {
        report("dev-1", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_SUCCESS");
        report("dev-2", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_FAILED");

        assertEquals(BatchStatus.ACTIVE, service.getRelease("rel").batches().get(0).status(),
                "仅 2 台结束设备, 未达 minDecided=4, 不应暂停");

        report("dev-3", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_SUCCESS");
        report("dev-4", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_FAILED");

        assertEquals(BatchStatus.PAUSED, service.getRelease("rel").batches().get(0).status(),
                "失败率 2/4 = 50% 达到阈值, 必须自动暂停");

        service.registerDevice("dev-late", "1.0.0");
        RolloutException ex = assertThrows(RolloutException.class,
                () -> service.dispatch("rel", "dev-late"));
        assertEquals(RejectReason.BATCH_PAUSED, ex.getReason());
    }

    @Test
    @DisplayName("暂停后整批回滚: 成功与失败设备都回到 ROLLING_BACK, 回滚完成后批次 ROLLED_BACK")
    void fullRollbackAfterPause_isConsistent() {
        report("dev-1", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_SUCCESS");
        report("dev-2", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_SUCCESS");
        report("dev-3", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_FAILED");
        report("dev-4", "DOWNLOADING", "PENDING_INSTALL", "INSTALL_FAILED");

        assertEquals(BatchStatus.PAUSED, service.getRelease("rel").batches().get(0).status());

        // 暂停瞬间所有已下发设备被清扫为 ROLLING_BACK（含两台已成功设备，
        // 它们是“已升级设备按回滚路径回到上一个版本”的覆盖对象）。
        for (int i = 1; i <= DEVICE_COUNT; i++) {
            String deviceId = "dev-" + i;
            DispatchResponse response = service.dispatch("rel", deviceId);
            assertEquals("ROLL_BACK", response.result(), deviceId + " 心跳应收到回滚指令");
            assertEquals(DeviceState.ROLLING_BACK, response.deviceState());

            ProgressResponse done = service.reportProgress(
                    "rel", deviceId, "ROLLBACK_COMPLETE");
            assertEquals("1.0.0", done.currentVersion(), deviceId + " 必须回到旧版本");
        }

        var batch = service.getRelease("rel").batches().get(0);
        assertEquals(BatchStatus.ROLLED_BACK, batch.status(), "全部回滚完成, 批次收尾为 ROLLED_BACK");
        assertEquals(DEVICE_COUNT, batch.rolledBack(), "回滚计数必须等于下发数");
        assertEquals(DEVICE_COUNT, batch.dispatched());
        assertEquals(2, batch.success());
        assertEquals(2, batch.failed(), "失败计数不丢失、不重复");

        // 回滚完成后再次下发，仍被拒绝。
        RolloutException ex = assertThrows(RolloutException.class,
                () -> service.dispatch("rel", "dev-1"));
        assertEquals(RejectReason.BATCH_PAUSED, ex.getReason());
    }

    @Test
    @DisplayName("全部成功时批次正常完成, 统计与进度一致")
    void belowThreshold_batchCompletes() {
        for (int i = 1; i <= DEVICE_COUNT; i++) {
            ProgressResponse response = service.reportProgress("rel", "dev-" + i, "INSTALL_SUCCESS");
            assertEquals(DeviceState.INSTALL_SUCCESS, response.state());
        }
        var batch = service.getRelease("rel").batches().get(0);
        assertEquals(BatchStatus.COMPLETED, batch.status());
        assertEquals(0, batch.failed());
        assertEquals(DEVICE_COUNT, batch.success());
    }

    @Test
    @DisplayName("失败率低于阈值(1/20=5% < 50%)时批次保持 ACTIVE, 不暂停")
    void singleFailureBelowThreshold_doesNotPause() {
        service.reportProgress("rel", "dev-1", "INSTALL_FAILED");
        for (int i = 2; i <= DEVICE_COUNT; i++) {
            service.reportProgress("rel", "dev-" + i, "INSTALL_SUCCESS");
        }
        var batch = service.getRelease("rel").batches().get(0);
        assertEquals(BatchStatus.ACTIVE, batch.status());
        assertEquals(1, batch.failed());
        // 失败设备显式回滚，成功设备保持新版本，批次不算完成也不暂停
        var rollback = service.requestRollback("rel", "dev-1");
        assertEquals(DeviceState.ROLLING_BACK, rollback.state());
        assertEquals("1.0.0", service.reportProgress("rel", "dev-1", "ROLLBACK_COMPLETE")
                .currentVersion());
    }

    private void report(String deviceId, String... states) {
        for (String state : states) {
            service.reportProgress("rel", deviceId, state);
        }
    }
}
