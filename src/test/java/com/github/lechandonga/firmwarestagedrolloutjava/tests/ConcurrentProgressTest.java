package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.dto.Requests.CreateReleaseRequest;
import com.github.lechandonga.firmwarestagedrolloutjava.dto.Responses.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RejectReason;
import com.github.lechandonga.firmwarestagedrolloutjava.exception.RolloutException;
import com.github.lechandonga.firmwarestagedrolloutjava.model.BatchStatus;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：并发安全 —— 多设备并发心跳、单设备重复并发心跳下，
 * 失败计数、成功计数与状态变更不丢失、不重复。
 */
@ExtendWith(BusinessLoggingExtension.class)
class ConcurrentProgressTest {

    private static final int DEVICE_COUNT = 30;
    private static final int REPEAT_PER_DEVICE = 8;

    @Test
    @DisplayName("多设备并发上报: 完整进度链 + 重复成功, 成功数恰为设备数")
    void concurrentReports_countsExactlyOnce() throws InterruptedException {
        RolloutService service = newService();

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        int tasksPerDevice = 2 + REPEAT_PER_DEVICE;
        CountDownLatch done = new CountDownLatch(DEVICE_COUNT * tasksPerDevice);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        for (int i = 1; i <= DEVICE_COUNT; i++) {
            String deviceId = "dev-" + i;
            pool.submit(() -> submit(start, done, errors, service, deviceId, "DOWNLOADING"));
            pool.submit(() -> submit(start, done, errors, service, deviceId, "PENDING_INSTALL"));
            for (int r = 0; r < REPEAT_PER_DEVICE; r++) {
                pool.submit(() -> submit(start, done, errors, service, deviceId, "INSTALL_SUCCESS"));
            }
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(errors.isEmpty(), "并发期间不应出现非幂等异常: " + errors);
        ReleaseView view = service.getRelease("rel");
        assertEquals(DEVICE_COUNT, view.batches().get(0).success(),
                "并发重复上报只允许成功计数一次");
        assertEquals(0, view.batches().get(0).failed());
    }

    private static void submit(CountDownLatch start, CountDownLatch done,
                               ConcurrentLinkedQueue<String> errors,
                               RolloutService service, String deviceId, String state) {
        try {
            start.await();
            // 各设备的进度链可能乱序被调度：旧状态按 STALE_STATE 拒绝属预期，
            // 唯一不允许的是“重复计数”。
            service.reportProgress("rel", deviceId, state);
        } catch (RolloutException ex) {
            if (ex.getReason() != RejectReason.DUPLICATE_PROGRESS
                    && ex.getReason() != RejectReason.STALE_STATE) {
                errors.add(deviceId + ":" + ex.getReason());
            }
        } catch (Exception e) {
            errors.add(deviceId + ":" + e);
        } finally {
            done.countDown();
        }
    }

    @Test
    @DisplayName("多设备并发失败上报: 被接受的失败 1:1 计数, 阈值触发暂停一次")
    void concurrentFailures_pauseExactlyOnce() throws InterruptedException {
        RolloutService service = newService();

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(DEVICE_COUNT);
        ConcurrentLinkedQueue<String> states = new ConcurrentLinkedQueue<>();

        for (int i = 1; i <= DEVICE_COUNT; i++) {
            String deviceId = "dev-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    var response = service.reportProgress("rel", deviceId, "INSTALL_FAILED");
                    states.add(response.state().name());
                } catch (Exception ignored) {
                    // 阈值触发清扫后, 其余失败上报按非法/旧状态拒绝, 统计断言在下方
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        ReleaseView view = service.getRelease("rel");
        var batch = view.batches().get(0);
        long reportedFailed = states.stream()
                .filter(s -> s.equals("INSTALL_FAILED") || s.equals("ROLLING_BACK")).count();
        assertEquals(batch.failed(), reportedFailed,
                "被服务端接受的失败上报必须 1:1 计入(触发阈值的设备同时被推进回滚), 不丢失不重复");
        assertTrue(reportedFailed >= 3, "至少达到阈值台设备计入失败, 实际 " + reportedFailed);
        assertEquals(BatchStatus.PAUSED, batch.status(), "达到阈值后暂停一次");
    }

    private RolloutService newService() {
        RolloutService service = new RolloutService();
        for (int i = 1; i <= DEVICE_COUNT; i++) {
            service.registerDevice("dev-" + i, "1.0.0");
        }
        service.createRelease(new CreateReleaseRequest(
                "rel", "2.0.0", List.of(100), 0.3, 3));
        service.activateBatch("rel", 0);
        for (int i = 1; i <= DEVICE_COUNT; i++) {
            assertEquals("DISPATCHED", service.dispatch("rel", "dev-" + i).result());
        }
        return service;
    }
}
