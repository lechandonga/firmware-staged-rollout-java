package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.exception.BusinessException;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;
import com.github.lechandonga.firmwarestagedrolloutjava.model.ProgressEvent;
import com.github.lechandonga.firmwarestagedrolloutjava.service.HeartbeatResult;
import com.github.lechandonga.firmwarestagedrolloutjava.service.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import com.github.lechandonga.firmwarestagedrolloutjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：当前批次失败率严格超过阈值（且达到最小样本数）后自动暂停并回滚；
 * 暂停后不再向新设备下发；已升级设备沿回滚路径降回上一版本；
 * 回滚完成后设备状态、批次统计、整体进度三者一致。
 */
@DisplayName("业务: 失败率超阈值自动暂停与回滚")
class AutoRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(AutoRollbackTest.class);

    private RolloutService service;

    @BeforeEach
    void setUp() {
        service = new RolloutService(new InMemoryStore());
        for (int i = 0; i < 6; i++) {
            service.registerDevice("dev-" + i, "1.0.0");
        }
        // 阈值 0.5（严格大于才触发），最小样本 2
        service.createRelease("rel", "2.0.0", List.of(100), 0.5, 2);
        for (int i = 0; i < 4; i++) {
            service.dispatch("dev-" + i, "rel");
        }
    }

    @Test
    @DisplayName("用例: 失败率等于阈值不触发（严格大于）；超过后自动暂停+回滚+终态一致")
    void failureRateThresholdTriggersPauseAndRollback() {
        log.info("【自动回滚】用例-阈值边界：1成功1失败=0.50 不触发；2失败/3台=0.67 触发");

        service.heartbeat("dev-0", "rel", "2.0.0", ProgressEvent.INSTALLED);
        HeartbeatResult atThreshold =
                service.heartbeat("dev-1", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        assertFalse(atThreshold.rollbackTriggered(), "失败率等于阈值时不应触发回滚");
        assertEquals("ACTIVE", service.releaseView("rel").status());
        log.info("【自动回滚】检查点：失败率=0.50 等于阈值，发布仍 ACTIVE");

        HeartbeatResult triggered =
                service.heartbeat("dev-2", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        assertTrue(triggered.rollbackTriggered(), "失败率超过阈值必须触发回滚");
        assertEquals("ROLLING_BACK", service.releaseView("rel").status());

        assertEquals(DeviceState.ROLLING_BACK, service.upgradeView("dev-0", "rel").getState());
        assertEquals(DeviceState.INSTALL_FAILED, service.upgradeView("dev-1", "rel").getState());
        log.info("【自动回滚】检查点：dev-0=ROLLING_BACK，dev-1/dev-2=INSTALL_FAILED 停留旧版");

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.dispatch("dev-4", "rel"));
        assertEquals(RejectReason.DISPATCH_BLOCKED_BY_STATUS, blocked.getReason());
        assertEquals(4, service.releaseView("rel").assigned(), "暂停后下发数不应增加");
        log.info("【自动回滚】检查点：新设备 dev-4 下发被拒，已下发数保持 4");

        HeartbeatResult rolledBack =
                service.heartbeat("dev-0", "rel", "2.0.0", ProgressEvent.ROLLBACK_DONE);
        assertTrue(rolledBack.rollbackFinished());

        ReleaseView view = service.releaseView("rel");
        assertEquals("ROLLED_BACK", view.status(), "所有新版本设备回滚后发布进入终态");
        assertEquals(1, view.installed(), "历史成功口径仍为 1");
        assertEquals(2, view.failed(), "失败设备数为 2");
        assertEquals(1, view.rolledBack(), "回滚完成设备数为 1");
        assertEquals(0, view.rollingBack(), "不应残留回滚中的设备");
        assertEquals(DeviceState.ROLLBACK_DONE, service.upgradeView("dev-0", "rel").getState());
        assertEquals("1.0.0", service.upgradeView("dev-0", "rel").getActiveVersion(),
                "回滚后设备版本必须降回 1.0.0");
        log.info("【自动回滚】结果-通过：状态=ROLLED_BACK，成功=1 失败=2 回滚=1 残留=0，dev-0=1.0.0");
    }

    @Test
    @DisplayName("用例: 未达最小样本数时即使失败率高也不触发")
    void minSamplesGuard() {
        log.info("【自动回滚】用例-最小样本：minSamples=2，仅 1 台失败（失败率 100%）也不触发");
        HeartbeatResult onlyOneFailed =
                service.heartbeat("dev-0", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        assertFalse(onlyOneFailed.rollbackTriggered());
        assertEquals("ACTIVE", service.releaseView("rel").status());
        log.info("【自动回滚】结果-通过：样本不足不触发，发布仍 ACTIVE");
    }

    @Test
    @DisplayName("用例: 回滚完成上报重复时幂等，回滚计数不重复增加")
    void duplicateRollbackDoneIsIdempotent() {
        log.info("【自动回滚】用例-回滚幂等：1成功2失败触发回滚，重复 ROLLBACK_DONE 不重复计数");
        service.heartbeat("dev-0", "rel", "2.0.0", ProgressEvent.INSTALLED);
        service.heartbeat("dev-1", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        HeartbeatResult triggered =
                service.heartbeat("dev-2", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        assertTrue(triggered.rollbackTriggered());
        assertEquals(DeviceState.ROLLING_BACK, service.upgradeView("dev-0", "rel").getState());

        service.heartbeat("dev-0", "rel", "2.0.0", ProgressEvent.ROLLBACK_DONE);
        HeartbeatResult duplicate =
                service.heartbeat("dev-0", "rel", "2.0.0", ProgressEvent.ROLLBACK_DONE);
        assertTrue(duplicate.idempotent());
        assertEquals(1, service.releaseView("rel").rolledBack());
        assertEquals("1.0.0", service.upgradeView("dev-0", "rel").getActiveVersion());
        assertEquals("ROLLED_BACK", service.releaseView("rel").status());
        log.info("【自动回滚】结果-通过：回滚计数=1，设备停留在 1.0.0，发布终态 ROLLED_BACK");
    }
}
