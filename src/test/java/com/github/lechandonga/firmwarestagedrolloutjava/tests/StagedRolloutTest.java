package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.exception.BusinessException;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.model.ReleaseStatus;
import com.github.lechandonga.firmwarestagedrolloutjava.service.DispatchResult;
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
 * 业务：固件发布按批次逐步扩大覆盖；分桶稳定可复现；
 * 不在目标范围的下发与同版本重复下发给出可区分结果。
 */
@DisplayName("业务: 灰度分批与稳定分桶")
class StagedRolloutTest {

    private static final Logger log = LoggerFactory.getLogger(StagedRolloutTest.class);

    private RolloutService service;

    @BeforeEach
    void setUp() {
        service = new RolloutService(new InMemoryStore());
        for (int i = 0; i < 200; i++) {
            service.registerDevice("dev-" + i, "1.0.0");
        }
    }

    @Test
    @DisplayName("用例: 空批次（首批准入 0%）时所有设备都被拒绝，原因 NOT_IN_TARGET")
    void emptyStageRejectsAllDevices() {
        log.info("【灰度边界】用例-空批次：stages=[0,50,100]，首批 0% 任何人不可下发");
        service.createRelease("rel-empty", "2.0.0", List.of(0, 50, 100), 0.5, 2);

        for (int i = 0; i < 200; i++) {
            final int index = i;
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.dispatch("dev-" + index, "rel-empty"));
            assertEquals(RejectReason.NOT_IN_TARGET, ex.getReason());
        }
        assertEquals(0, service.releaseView("rel-empty").assigned());
        log.info("【灰度边界】结果-通过：200 台设备全部以 NOT_IN_TARGET 拒绝，已下发数=0");
    }

    @Test
    @DisplayName("用例: 全量批次（100%）覆盖所有设备")
    void fullStageAcceptsAllDevices() {
        log.info("【灰度边界】用例-全量批次：stages=[100]，所有设备都应下发成功");
        service.createRelease("rel-full", "2.0.0", List.of(100), 0.5, 2);

        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            if (!service.dispatch("dev-" + i, "rel-full").idempotent()) {
                accepted++;
            }
        }
        assertEquals(200, accepted);
        assertEquals(200, service.releaseView("rel-full").assigned());
        log.info("【灰度边界】结果-通过：200 台设备全部进入全量批次");
    }

    @Test
    @DisplayName("用例: 批次扩大后覆盖范围单调增加，原先被拒设备可下发")
    void stageExpansionWidensTargetSet() {
        log.info("【灰度边界】用例-批次扩大：10% -> 50% -> 100%，准入集合只增不减");
        service.createRelease("rel-expand", "2.0.0", List.of(10, 50, 100), 0.5, 2);

        int inFirst = countDispatchable("rel-expand");
        assertTrue(inFirst > 0 && inFirst < 200, "10% 批次应只覆盖部分设备");
        service.advanceStage("rel-expand");
        int inSecond = countDispatchable("rel-expand");
        assertTrue(inSecond > inFirst, "扩大到 50% 后覆盖设备应增加");
        service.advanceStage("rel-expand");
        int inThird = countDispatchable("rel-expand");
        assertEquals(200, inThird, "100% 批次覆盖全部设备");
        log.info("【灰度边界】结果-通过：覆盖设备数 {} -> {} -> 200，单调扩大", inFirst, inSecond);
    }

    @Test
    @DisplayName("用例: 同一设备多次评估分桶结果一致（稳定可复现）")
    void bucketingIsStableAcrossReleasesAndEvaluations() {
        log.info("【灰度边界】用例-稳定分桶：同一设备在不同发布、多次评估结果一致");
        service.createRelease("rel-a", "2.0.0", List.of(10, 100), 0.5, 2);
        service.createRelease("rel-b", "2.0.0", List.of(10, 100), 0.5, 2);

        int acceptedA = countDispatchable("rel-a");
        int acceptedB = countDispatchable("rel-b");
        assertEquals(acceptedA, acceptedB, "两个相同批次配置的发布准入设备数必须一致");

        for (int i = 0; i < 200; i++) {
            String deviceId = "dev-" + i;
            boolean first = dispatchQuietly(deviceId, "rel-a");
            for (int repeat = 0; repeat < 5; repeat++) {
                if (first) {
                    DispatchResult result = service.dispatch(deviceId, "rel-a");
                    assertTrue(result.idempotent(), "重复下发必须幂等: " + deviceId);
                }
            }
            assertEquals(first, dispatchQuietly(deviceId, "rel-b"),
                    "同设备在两个发布中的准入结果必须一致: " + deviceId);
        }
        assertEquals(acceptedA, service.releaseView("rel-a").assigned());
        log.info("【灰度边界】结果-通过：分桶稳定，重复下发不重复生效（assigned={}）", acceptedA);
    }

    @Test
    @DisplayName("用例: 重复下发幂等（不重复计数）；降级与同版本重装被可区分拒绝")
    void idempotentAndVersionGuards() {
        log.info("【灰度边界】用例-下发防护：幂等 / 同版本重装 / 降级 / 未知设备 原因可区分");
        service.createRelease("rel-guard", "2.0.0", List.of(100), 0.5, 2);

        DispatchResult first = service.dispatch("dev-0", "rel-guard");
        assertFalse(first.idempotent());
        DispatchResult second = service.dispatch("dev-0", "rel-guard");
        assertTrue(second.idempotent(), "同一设备同一版本重复下发必须幂等");
        assertEquals(1, service.releaseView("rel-guard").assigned(), "幂等下发不得重复计数");

        service.registerDevice("dev-same", "2.0.0");
        BusinessException sameVersion = assertThrows(BusinessException.class,
                () -> service.dispatch("dev-same", "rel-guard"));
        assertEquals(RejectReason.SAME_VERSION_REINSTALL, sameVersion.getReason());

        service.registerDevice("dev-new", "3.0.0");
        BusinessException downgrade = assertThrows(BusinessException.class,
                () -> service.dispatch("dev-new", "rel-guard"));
        assertEquals(RejectReason.DOWNGRADE_NOT_ALLOWED, downgrade.getReason());

        BusinessException unknownDevice = assertThrows(BusinessException.class,
                () -> service.dispatch("ghost-device", "rel-guard"));
        assertEquals(RejectReason.UNKNOWN_DEVICE, unknownDevice.getReason());

        BusinessException unknownRelease = assertThrows(BusinessException.class,
                () -> service.dispatch("dev-0", "no-such-release"));
        assertEquals(RejectReason.UNKNOWN_RELEASE, unknownRelease.getReason());
        log.info("【灰度边界】结果-通过：四类结果原因分别为 IDEMPOTENT / SAME_VERSION_REINSTALL "
                + "/ DOWNGRADE_NOT_ALLOWED / UNKNOWN_DEVICE|UNKNOWN_RELEASE");
    }

    @Test
    @DisplayName("用例: 暂停后不再下发，暂停原因 DISPATCH_BLOCKED_BY_STATUS；恢复后可继续")
    void pauseBlocksDispatchAndResumeAllows() {
        log.info("【灰度边界】用例-暂停：暂停后新设备被拒，恢复后可继续灰度");
        service.createRelease("rel-pause", "2.0.0", List.of(100), 0.5, 2);
        service.pause("rel-pause");

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.dispatch("dev-1", "rel-pause"));
        assertEquals(RejectReason.DISPATCH_BLOCKED_BY_STATUS, blocked.getReason());

        service.resume("rel-pause");
        assertFalse(service.dispatch("dev-1", "rel-pause").idempotent());
        assertEquals(ReleaseStatus.ACTIVE.name(), service.releaseView("rel-pause").status());
        log.info("【灰度边界】结果-通过：暂停阻断下发，恢复后下发成功");
    }

    @Test
    @DisplayName("用例: 非法批次配置被拒绝（不递增、非 100 收尾、越界）")
    void invalidStageConfig() {
        log.info("【灰度边界】用例-批次配置校验：[50,10] / [50] / [101] 均非法");
        assertThrows(BusinessException.class,
                () -> service.createRelease("r1", "2.0.0", List.of(50, 10), 0.5, 2));
        assertThrows(BusinessException.class,
                () -> service.createRelease("r2", "2.0.0", List.of(50), 0.5, 2));
        assertThrows(BusinessException.class,
                () -> service.createRelease("r3", "2.0.0", List.of(101), 0.5, 2));
        log.info("【灰度边界】结果-通过");
    }

    private int countDispatchable(String releaseId) {
        int count = 0;
        for (int i = 0; i < 200; i++) {
            if (dispatchQuietly("dev-" + i, releaseId)) {
                count++;
            }
        }
        return count;
    }

    private boolean dispatchQuietly(String deviceId, String releaseId) {
        try {
            // 评估设备是否属于目标集合：已下发（幂等返回）或新下发成功都算“在目标内”
            service.dispatch(deviceId, releaseId);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
