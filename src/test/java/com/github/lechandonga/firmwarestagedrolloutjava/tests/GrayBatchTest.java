package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.DispatchResponse;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RolloutException;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务域：灰度分批规则 —— 空批次、全量批次、批次扩大、稳定可复现、下发幂等与拒绝区分。
 */
@ExtendWith(BusinessLoggingExtension.class)
class GrayBatchTest {

    private RolloutService service;

    @BeforeEach
    void setUp() {
        service = new RolloutService();
        for (int i = 1; i <= 200; i++) {
            service.registerDevice("dev-" + i, "1.0.0");
        }
    }

    @Test
    @DisplayName("空批次(0%): 激活后任何设备都不属于目标集合, 拒绝原因 NOT_IN_TARGET")
    void emptyBatch_coversNobody() {
        service.createRelease(new CreateReleaseRequest(
                "rel-empty", "2.0.0", List.of(0, 100), null, null));
        service.activateBatch("rel-empty", 0);

        int rejected = 0;
        for (int i = 1; i <= 200; i++) {
            final String id = "dev-" + i;
            RolloutException ex = assertThrows(RolloutException.class,
                    () -> service.dispatch("rel-empty", id));
            assertEquals(RejectReason.NOT_IN_TARGET, ex.getReason());
            rejected++;
        }
        assertEquals(200, rejected);
        ReleaseView view = service.getRelease("rel-empty");
        assertEquals(0, view.batches().get(0).dispatched());
    }

    @Test
    @DisplayName("全量批次(100%): 激活后所有设备都能下发, 槽位评估多次结果一致")
    void fullBatch_coversEverybody_andStable() {
        service.createRelease(new CreateReleaseRequest(
                "rel-full", "2.0.0", List.of(100), null, null));
        service.activateBatch("rel-full", 0);

        int dispatched = 0;
        for (int i = 1; i <= 200; i++) {
            DispatchResponse first = service.dispatch("rel-full", "dev-" + i);
            DispatchResponse second = service.dispatch("rel-full", "dev-" + i);
            assertEquals("DISPATCHED", first.result());
            assertEquals("IDEMPOTENT", second.result(), "重复下发必须幂等");
            dispatched++;
        }
        assertEquals(200, dispatched);
        assertEquals(200, service.getRelease("rel-full").batches().get(0).dispatched(),
                "重复下发不得重复计数");
    }

    @Test
    @DisplayName("批次扩大(10%->50%->100%): 每批只纳入新增槽位设备, 归属稳定可复现")
    void batchExpansion_addsOnlyNewSlots() {
        service.createRelease(new CreateReleaseRequest(
                "rel-expand", "2.0.0", List.of(10, 50, 100), null, null));

        service.activateBatch("rel-expand", 0);
        int firstWave = dispatchAllAndCount("rel-expand");
        assertEquals(expectedWaveSize("rel-expand", 0), firstWave,
                "第一批只纳入槽位 [0,10) 的设备");

        service.activateBatch("rel-expand", 1);
        int secondWave = dispatchAllAndCount("rel-expand");
        assertEquals(expectedWaveSize("rel-expand", 0) + expectedWaveSize("rel-expand", 1),
                firstWave + secondWave, "累计覆盖到 50%");

        service.activateBatch("rel-expand", 2);
        int thirdWave = dispatchAllAndCount("rel-expand");
        assertEquals(200, firstWave + secondWave + thirdWave, "最终 100% 覆盖");

        int dispatchedAgain = dispatchAllAndCount("rel-expand");
        assertEquals(0, dispatchedAgain, "扩大后重扫不应产生新下发");
    }

    private int expectedWaveSize(String releaseId, int batchIndex) {
        int low = batchIndex == 0 ? 0 : List.of(10, 50, 100).get(batchIndex - 1);
        int high = List.of(10, 50, 100).get(batchIndex);
        int count = 0;
        for (int i = 1; i <= 200; i++) {
            int slot = com.github.lechandonga.firmwarestagedrolloutjava.service.BatchSelector
                    .slot(releaseId, "dev-" + i);
            if (slot >= low && slot < high) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("未激活批次的设备被拒绝, 与已激活目标设备的下发结果可区分")
    void pendingBatchRejection_isDistinguishable() {
        service.createRelease(new CreateReleaseRequest(
                "rel-pending", "2.0.0", List.of(10, 100), null, null));
        service.activateBatch("rel-pending", 0);

        boolean sawDispatched = false;
        boolean sawNotInTarget = false;
        for (int i = 1; i <= 200; i++) {
            try {
                DispatchResponse response = service.dispatch("rel-pending", "dev-" + i);
                assertEquals("DISPATCHED", response.result());
                sawDispatched = true;
            } catch (RolloutException ex) {
                assertEquals(RejectReason.NOT_IN_TARGET, ex.getReason());
                sawNotInTarget = true;
            }
        }
        assertEquals(true, sawDispatched);
        assertEquals(true, sawNotInTarget);
    }

    @Test
    @DisplayName("降级与同版本重装走可区分结果: 409 降级, 200 幂等重装")
    void downgradeRejected_reinstallIdempotent() {
        service.createRelease(new CreateReleaseRequest(
                "rel-ver", "1.0.0", List.of(100), null, null));
        service.activateBatch("rel-ver", 0);

        service.registerDevice("dev-old", "2.5.0");
        RolloutException downgrade = assertThrows(RolloutException.class,
                () -> service.dispatch("rel-ver", "dev-old"));
        assertEquals(RejectReason.VERSION_DOWNGRADE, downgrade.getReason());

        DispatchResponse response = service.dispatch("rel-ver", "dev-1");
        assertNotEquals("DISPATCHED", response.result());
        assertEquals("IDEMPOTENT", response.result());
        assertEquals(0, service.getRelease("rel-ver").batches().get(0).dispatched());
    }

    private int dispatchAllAndCount(String releaseId) {
        int count = 0;
        for (int i = 1; i <= 200; i++) {
            try {
                if ("DISPATCHED".equals(service.dispatch(releaseId, "dev-" + i).result())) {
                    count++;
                }
            } catch (RolloutException ex) {
                assertEquals(RejectReason.NOT_IN_TARGET, ex.getReason());
            }
        }
        return count;
    }
}
