package com.github.lechandonga.firmwarestagedrolloutjava.service;

import com.github.lechandonga.firmwarestagedrolloutjava.model.Upgrade;

/**
 * 下发结果。idempotent=true 表示该设备该版本已有任务，本次未重复生效。
 */
public record DispatchResult(boolean idempotent, String deviceId, String releaseId,
                             String targetVersion, String previousVersion, String state) {

    static DispatchResult accepted(Upgrade upgrade) {
        return of(false, upgrade);
    }

    static DispatchResult idempotent(Upgrade upgrade) {
        return of(true, upgrade);
    }

    private static DispatchResult of(boolean idempotent, Upgrade upgrade) {
        return new DispatchResult(idempotent, upgrade.getDeviceId(), upgrade.getReleaseId(),
                upgrade.getTargetVersion(), upgrade.getPreviousVersion(),
                upgrade.getState().name());
    }
}
