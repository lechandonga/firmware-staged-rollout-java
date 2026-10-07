package com.github.lechandonga.firmwarestagedrolloutjava.service;

import com.github.lechandonga.firmwarestagedrolloutjava.model.DeviceState;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Release;
import com.github.lechandonga.firmwarestagedrolloutjava.model.Upgrade;

/**
 * 心跳处理结果。idempotent=true 表示重复上报，状态与计数均无变化。
 */
public record HeartbeatResult(boolean idempotent, String state, String releaseStatus,
                              boolean rollbackTriggered, boolean rollbackFinished) {

    static HeartbeatResult idempotent(Upgrade upgrade, Release release) {
        return new HeartbeatResult(true, upgrade.getState().name(),
                release.getStatus().name(), false, false);
    }
}
