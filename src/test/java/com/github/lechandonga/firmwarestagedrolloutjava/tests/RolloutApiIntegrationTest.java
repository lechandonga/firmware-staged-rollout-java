package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务域：HTTP 端到端冒烟 —— 注册、建发布、激活批次、下发、心跳、回滚、拒绝码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(BusinessLoggingExtension.class)
class RolloutApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("完整链路: 注册->发布->激活->下发->成功上报; 未知设备返回 UNKNOWN_DEVICE")
    void endToEnd_overHttp() throws Exception {
        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"web-1","currentVersion":"1.0.0"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"releaseId":"rel-web","targetVersion":"2.0.0",
                                 "rolloutPercents":[100],"failureRateThreshold":0.5,
                                 "minDecidedForThreshold":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batches[0].status").value("PENDING"));

        mockMvc.perform(post("/api/releases/rel-web/batches/0/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].status").value("ACTIVE"));

        mockMvc.perform(post("/api/releases/rel-web/dispatch").param("deviceId", "web-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"))
                .andExpect(jsonPath("$.targetVersion").value("2.0.0"));

        mockMvc.perform(post("/api/releases/rel-web/dispatch").param("deviceId", "web-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("IDEMPOTENT"));

        mockMvc.perform(post("/api/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"web-1","releaseId":"rel-web","state":"INSTALL_SUCCESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        mockMvc.perform(get("/api/releases/rel-web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].success").value(1))
                .andExpect(jsonPath("$.batches[0].status").value("COMPLETED"));

        mockMvc.perform(post("/api/releases/rel-web/dispatch").param("deviceId", "ghost"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNKNOWN_DEVICE"));
    }
}
