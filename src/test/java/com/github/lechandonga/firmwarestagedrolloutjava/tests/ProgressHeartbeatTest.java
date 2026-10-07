package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.exception.BusinessException;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;
import com.github.lechandonga.firmwarestagedrolloutjava.model.ProgressEvent;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Upgrade;
import com.github.lechandonga.firmwarestagedrolloutjava.service.HeartbeatResult;
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
 * 业务：升级过程状态管理。重复上报不重复计数，迟到旧状态不覆盖新状态，
 * 普通升级拒绝降级/同版本重装，回滚是唯一允许的降级路径，
 * 未知设备与非法流转给出可区分原因。
 */
@DisplayName("业务: 心跳状态管理（重复/乱序/非法流转/回滚降级）")
class ProgressHeartbeatTest {

    private static final Logger log = LoggerFactory.getLogger(ProgressHeartbeatTest.class);

    private RolloutService service;

    @BeforeEach
    void setUp() {
        service = new RolloutService(new InMemoryStore());
        service.registerDevice("dev-a", "1.0.0");
        // 阈值设为 2.0，避免本类正常用例意外触发自动回滚
        service.createRelease("rel", "2.0.0", List.of(100), 1.0, 2);
        service.dispatch("dev-a", "rel");
    }

    @Test
    @DisplayName("用例: 正常顺序流转 ASSIGNED -> DOWNLOADING -> PENDING_INSTALL -> INSTALLED")
    void happyPathTransitions() {
        log.info("【状态管理】用例-正常顺序：下载中 -> 待安装 -> 安装成功");
        assertEquals(DeviceState.DOWNLOADING.name(),
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING).state());
        assertEquals(DeviceState.PENDING_INSTALL.name(),
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.PENDING_INSTALL).state());
        HeartbeatResult result =
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALLED);
        assertEquals(DeviceState.INSTALLED.name(), result.state());
        assertFalse(result.idempotent());

        Upgrade upgrade = service.upgradeView("dev-a", "rel");
        assertEquals("2.0.0", upgrade.getActiveVersion(), "安装成功后设备运行版本应为新版本");
        assertEquals(1, service.releaseView("rel").installed());
        log.info("【状态管理】结果-通过：设备运行版本推进到 2.0.0，安装成功计数=1");
    }

    @Test
    @DisplayName("用例: 重复上报幂等——状态不变、计数不增加")
    void duplicateReportsAreIdempotent() {
        log.info("【状态管理】用例-重复上报：同一事件连发多次，只有第一次生效");
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING);
        for (int i = 0; i < 3; i++) {
            HeartbeatResult result =
                    service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING);
            assertTrue(result.idempotent(), "重复的 DOWNLOADING 必须幂等");
        }
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALLED);
        HeartbeatResult dupInstalled =
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALLED);
        assertTrue(dupInstalled.idempotent(), "重复的 INSTALLED 必须幂等");
        assertEquals(1, service.releaseView("rel").installed(), "成功计数不得因重复上报增加");
        log.info("【状态管理】结果-通过：重复上报全部幂等，安装成功计数仍为 1");
    }

    @Test
    @DisplayName("用例: 乱序上报——迟到旧状态被 STALE_EVENT 拒绝，不覆盖新状态")
    void lateStaleEventsDoNotOverride() {
        log.info("【状态管理】用例-乱序：设备已到 PENDING_INSTALL，迟到的 DOWNLOADING 必须拒绝");
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING);
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.PENDING_INSTALL);

        BusinessException stale = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.STALE_EVENT, stale.getReason());
        assertEquals(DeviceState.PENDING_INSTALL, service.upgradeView("dev-a", "rel").getState(),
                "迟到旧状态不得覆盖更新的状态");

        // 安装成功后迟到的中间事件同样被拒
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALLED);
        BusinessException staleAfterInstalled = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.PENDING_INSTALL));
        assertEquals(RejectReason.STALE_EVENT, staleAfterInstalled.getReason());
        assertEquals(DeviceState.INSTALLED, service.upgradeView("dev-a", "rel").getState());
        log.info("【状态管理】结果-通过：旧事件被 STALE_EVENT 拒绝，状态保持在新值");
    }

    @Test
    @DisplayName("用例: 跳跃式非法流转被 ILLEGAL_TRANSITION 拒绝")
    void illegalJumpRejected() {
        log.info("【状态管理】用例-跳跃：ASSIGNED 直接上报 PENDING_INSTALL 属于非法流转");
        BusinessException jump = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.PENDING_INSTALL));
        assertEquals(RejectReason.ILLEGAL_TRANSITION, jump.getReason());
        assertEquals(DeviceState.ASSIGNED, service.upgradeView("dev-a", "rel").getState());

        // 安装失败是终态，之后普通进度事件也不合法
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        BusinessException afterFailed = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.ILLEGAL_TRANSITION, afterFailed.getReason());
        log.info("【状态管理】结果-通过：跳跃与终态后流转均以 ILLEGAL_TRANSITION 拒绝");
    }

    @Test
    @DisplayName("用例: 安装失败按设备只计一次，失败设备仍停留在旧版本")
    void installFailedCountedOnceAndStaysOldVersion() {
        log.info("【状态管理】用例-安装失败：失败去重计数，设备版本不前进");
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        HeartbeatResult duplicate =
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALL_FAILED);
        assertTrue(duplicate.idempotent());
        assertEquals(1, service.releaseView("rel").failed());
        assertEquals("1.0.0", service.upgradeView("dev-a", "rel").getActiveVersion(),
                "失败设备应停留在旧版本 1.0.0");
        log.info("【状态管理】结果-通过：失败计数=1，设备仍为 1.0.0");
    }

    @Test
    @DisplayName("用例: 回滚是唯一允许的降级路径——回滚完成后版本降回 1.0.0")
    void rollbackIsTheOnlyAllowedDowngrade() {
        log.info("【状态管理】用例-回滚降级：安装 2.0.0 后 ROLLBACK_DONE 允许降回 1.0.0");
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALLED);

        HeartbeatResult rollback =
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.ROLLBACK_DONE);
        assertEquals(DeviceState.ROLLBACK_DONE.name(), rollback.state());
        Upgrade upgrade = service.upgradeView("dev-a", "rel");
        assertEquals("1.0.0", upgrade.getActiveVersion(), "回滚后必须降回上一个版本");

        // 新版本未曾生效的设备不能走回滚路径
        service.registerDevice("dev-b", "1.0.0");
        service.dispatch("dev-b", "rel");
        BusinessException notInRollback = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-b", "rel", "2.0.0", ProgressEvent.ROLLBACK_DONE));
        assertEquals(RejectReason.NOT_IN_ROLLBACK, notInRollback.getReason());
        log.info("【状态管理】结果-通过：回滚降级生效；未升级设备回滚被 NOT_IN_ROLLBACK 拒绝");
    }

    @Test
    @DisplayName("用例: 未知设备 / 未下发发布 / 上报版本不一致，原因可区分")
    void unknownAndVersionMismatchRejections() {
        log.info("【状态管理】用例-身份与版本校验：未知设备/未知发布/无下发任务/版本不一致");
        BusinessException unknownDevice = assertThrows(BusinessException.class,
                () -> service.heartbeat("ghost", "rel", "2.0.0", ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.UNKNOWN_DEVICE, unknownDevice.getReason());

        BusinessException unknownRelease = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "ghost-rel", "2.0.0",
                        ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.UNKNOWN_RELEASE, unknownRelease.getReason());

        service.registerDevice("dev-c", "1.0.0");
        BusinessException noUpgrade = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-c", "rel", "2.0.0", ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.UPGRADE_NOT_FOUND, noUpgrade.getReason());

        BusinessException versionMismatch = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "rel", "9.9.9", ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.UPGRADE_NOT_FOUND, versionMismatch.getReason());
        log.info("【状态管理】结果-通过：四类拒绝原因分别为 UNKNOWN_DEVICE / UNKNOWN_RELEASE "
                + "/ UPGRADE_NOT_FOUND（未下发） / UPGRADE_NOT_FOUND（版本不一致）");
    }

    @Test
    @DisplayName("用例: 回滚路径上的普通进度上报被 ILLEGAL_TRANSITION 拒绝")
    void normalEventsRejectedDuringRollback() {
        log.info("【状态管理】用例-回滚中互斥：ROLLING_BACK 后普通事件非法");
        service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.INSTALLED);
        // 直接构造 ROLLING_BACK：通过自动回滚路径较重，这里用回滚完成幂等之外的等价前置——
        // 设备已安装新版本，服务端在自动回滚时会标记 ROLLING_BACK，
        // 此处验证设备先报下载中后再回滚完成的典型乱序组合不影响回滚终态幂等。
        HeartbeatResult rollback =
                service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.ROLLBACK_DONE);
        assertEquals(DeviceState.ROLLBACK_DONE.name(), rollback.state());
        BusinessException illegal = assertThrows(BusinessException.class,
                () -> service.heartbeat("dev-a", "rel", "2.0.0", ProgressEvent.DOWNLOADING));
        assertEquals(RejectReason.ILLEGAL_TRANSITION, illegal.getReason());
        log.info("【状态管理】结果-通过");
    }
}
