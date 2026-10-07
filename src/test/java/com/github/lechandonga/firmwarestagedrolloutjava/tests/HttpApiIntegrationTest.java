package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：HTTP 接口端到端——注册、发布、下发、心跳、自动回滚，
 * 以及 400 响应中可区分的 error 错误码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("业务: HTTP API 端到端")
class HttpApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpApiIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postExpectError(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @Test
    @DisplayName("用例: 完整链路 HTTP 调用 + 非目标设备返回 NOT_IN_TARGET 错误码")
    void fullHttpFlowAndDistinguishableErrors() {
        log.info("【HTTP 链路】用例：注册设备 -> 0% 首批拒绝 -> 扩大全量 -> 下发 -> 心跳成功");
        post("/api/devices", Map.of("deviceId", "http-dev", "currentVersion", "1.0.0"));
        post("/api/releases", Map.of(
                "releaseId", "http-rel",
                "targetVersion", "2.0.0",
                "stagesPercent", List.of(0, 100),
                "failureRateThreshold", 0.5,
                "minSamplesForThreshold", 2));

        Map<String, Object> rejected =
                postExpectError("/api/dispatch",
                        Map.of("deviceId", "http-dev", "releaseId", "http-rel"));
        assertEquals("NOT_IN_TARGET", rejected.get("error"),
                "空批次下发必须返回可区分错误码 NOT_IN_TARGET");

        post("/api/releases/http-rel/advance", Map.of());
        Map<String, Object> dispatched =
                post("/api/dispatch", Map.of("deviceId", "http-dev", "releaseId", "http-rel"));
        assertEquals(false, dispatched.get("idempotent"));

        Map<String, Object> duplicate =
                post("/api/dispatch", Map.of("deviceId", "http-dev", "releaseId", "http-rel"));
        assertEquals(true, duplicate.get("idempotent"), "重复下发必须返回幂等结果");

        Map<String, Object> heartbeat = post("/api/heartbeat", Map.of(
                "deviceId", "http-dev",
                "releaseId", "http-rel",
                "version", "2.0.0",
                "event", "INSTALLED"));
        assertEquals("INSTALLED", heartbeat.get("state"));

        Map<String, Object> unknown =
                postExpectError("/api/heartbeat", Map.of(
                        "deviceId", "ghost",
                        "releaseId", "http-rel",
                        "version", "2.0.0",
                        "event", "INSTALLED"));
        assertEquals("UNKNOWN_DEVICE", unknown.get("error"));

        Map<String, Object> releaseView = rest.getForObject(url("/api/releases/http-rel"), Map.class);
        assertEquals(1, releaseView.get("installed"));
        log.info("【HTTP 链路】结果-通过：错误码可区分，幂等标志正确，进度视图 installed=1");
    }

    @Test
    @DisplayName("用例: HTTP 失败率超阈值触发自动回滚，回滚完成后发布 ROLLED_BACK")
    void httpFailureTriggersRollback() {
        log.info("【HTTP 链路】用例：2失败1成功 -> 自动回滚 -> 成功设备回滚完成 -> ROLLED_BACK");
        post("/api/devices", Map.of("deviceId", "h1", "currentVersion", "1.0.0"));
        post("/api/devices", Map.of("deviceId", "h2", "currentVersion", "1.0.0"));
        post("/api/devices", Map.of("deviceId", "h3", "currentVersion", "1.0.0"));
        post("/api/releases", Map.of(
                "releaseId", "hr",
                "targetVersion", "2.0.0",
                "stagesPercent", List.of(100)));
        for (String device : List.of("h1", "h2", "h3")) {
            post("/api/dispatch", Map.of("deviceId", device, "releaseId", "hr"));
        }

        post("/api/heartbeat", Map.of("deviceId", "h1", "releaseId", "hr",
                "version", "2.0.0", "event", "INSTALLED"));
        post("/api/heartbeat", Map.of("deviceId", "h2", "releaseId", "hr",
                "version", "2.0.0", "event", "INSTALL_FAILED"));
        Map<String, Object> trigger = post("/api/heartbeat", Map.of(
                "deviceId", "h3", "releaseId", "hr",
                "version", "2.0.0", "event", "INSTALL_FAILED"));
        assertEquals(true, trigger.get("rollbackTriggered"));

        Map<String, Object> rollbackDone = post("/api/heartbeat", Map.of(
                "deviceId", "h1", "releaseId", "hr",
                "version", "2.0.0", "event", "ROLLBACK_DONE"));
        assertEquals(true, rollbackDone.get("rollbackFinished"));
        assertEquals("ROLLED_BACK", rollbackDone.get("releaseStatus"));

        Map<String, Object> view = rest.getForObject(url("/api/releases/hr"), Map.class);
        assertEquals("ROLLED_BACK", view.get("status"));
        assertEquals(1, view.get("rolledBack"));
        assertFalse(((Number) view.get("rollingBack")).intValue() > 0);
        log.info("【HTTP 链路】结果-通过：自动回滚生效，整体进度 ROLLED_BACK / rolledBack=1");
    }
}
