package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.model.ProgressEvent;
import com.github.lechandonga.firmwarestagedrolloutjava.service.ReleaseView;
import com.github.lechandonga.firmwarestagedrolloutjava.service.RolloutService;
import com.github.lechandonga.firmwarestagedrolloutjava.store.InMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：多个设备并发心跳上报时，失败计数与状态变更不得丢失或重复。
 */
@DisplayName("业务: 并发心跳计数一致性")
class ConcurrentHeartbeatTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentHeartbeatTest.class);

    @Test
    @DisplayName("用例: 同一设备 32 线程同时上报安装成功，成功计数恰好为 1")
    void concurrentDuplicateReportsCountOnce() throws InterruptedException {
        log.info("【并发一致性】用例-同设备并发重复上报：32 线程并发 INSTALLED，计数只能 +1");
        RolloutService service = new RolloutService(new InMemoryStore());
        service.registerDevice("dev", "1.0.0");
        service.createRelease("rel", "2.0.0", java.util.List.of(100), 1.0, 2);
        service.dispatch("dev", "rel");

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.heartbeat("dev", "rel", "2.0.0", ProgressEvent.INSTALLED);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, service.releaseView("rel").installed(), "并发重复上报只能有一次生效");
        assertEquals("2.0.0", service.upgradeView("dev", "rel").getActiveVersion());
        log.info("【并发一致性】结果-通过：32 线程竞争后安装成功计数=1，无重复计数");
    }

    @Test
    @DisplayName("用例: 101 设备并发成功/失败上报触发自动回滚，并并发回滚完成，终态一致")
    void concurrentMixedReportsAndRollbackStayConsistent() throws InterruptedException {
        log.info("【并发一致性】用例-多设备并发：40 成功 + 61 失败 -> 自动回滚 -> 40 台并发回滚完成");
        RolloutService service = new RolloutService(new InMemoryStore());
        int successCount = 40;
        int failCount = 61;
        int total = successCount + failCount;
        for (int i = 0; i < total; i++) {
            service.registerDevice("dev-" + i, "1.0.0");
        }
        service.createRelease("rel", "2.0.0", java.util.List.of(100), 0.5, 5);
        for (int i = 0; i < total; i++) {
            service.dispatch("dev-" + i, "rel");
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch reportStart = new CountDownLatch(1);
        CountDownLatch reportsDone = new CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    reportStart.await();
                    ProgressEvent event = index < successCount
                            ? ProgressEvent.INSTALLED : ProgressEvent.INSTALL_FAILED;
                    service.heartbeat("dev-" + index, "rel", "2.0.0", event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    reportsDone.countDown();
                }
            });
        }
        reportStart.countDown();
        assertTrue(reportsDone.await(20, TimeUnit.SECONDS));

        ReleaseView view = service.releaseView("rel");
        assertEquals(total, view.installed() + view.failed(), "成功+失败必须等于总数，不丢失不重复");
        assertEquals(successCount, view.installed());
        assertEquals(failCount, view.failed());
        assertEquals("ROLLING_BACK", view.status(), "61/101 失败率严格大于 0.5 必须已触发回滚");
        assertEquals(successCount, view.rollingBack(), "所有已装新版本设备应处于回滚中");
        log.info("【并发一致性】检查点：并发上报后 成功={} 失败={} 回滚中={}，发布=ROLLING_BACK",
                view.installed(), view.failed(), view.rollingBack());

        CountDownLatch rollbackStart = new CountDownLatch(1);
        CountDownLatch rollbackDone = new CountDownLatch(successCount);
        for (int i = 0; i < successCount; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    rollbackStart.await();
                    service.heartbeat("dev-" + index, "rel", "2.0.0",
                            ProgressEvent.ROLLBACK_DONE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    rollbackDone.countDown();
                }
            });
        }
        rollbackStart.countDown();
        assertTrue(rollbackDone.await(20, TimeUnit.SECONDS));
        pool.shutdown();

        ReleaseView finalView = service.releaseView("rel");
        assertEquals("ROLLED_BACK", finalView.status());
        assertEquals(successCount, finalView.rolledBack(), "回滚完成设备数必须精确等于成功设备数");
        assertEquals(0, finalView.rollingBack(), "不应残留回滚中的设备");
        assertEquals(failCount, finalView.failed());
        for (int i = 0; i < successCount; i++) {
            assertEquals("1.0.0", service.upgradeView("dev-" + i, "rel").getActiveVersion(),
                    "回滚后设备版本必须全部降回 1.0.0");
        }
        log.info("【并发一致性】结果-通过：并发回滚完成 {} 台，状态=ROLLED_BACK，设备全部回到 1.0.0",
                finalView.rolledBack());
    }
}
