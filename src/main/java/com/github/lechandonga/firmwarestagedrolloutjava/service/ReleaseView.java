package com.github.lechandonga.firmwarestagedrolloutjava.service;

import java.util.List;

/**
 * 发布整体进度视图，设备状态与批次统计口径一致可解释。
 *
 * @param installed       当前新版本生效中的设备数
 * @param failed          安装失败（仍在旧版本）设备数
 * @param rollingBack     已触发回滚、尚未回滚完成的设备数
 * @param rolledBack      已回到上一稳定版本的设备数
 * @param inFlight        处于下载/待安装等中间态的设备数
 * @param failureRate     当前已出结果设备中的失败率 failed/(installed+failed)
 */
public record ReleaseView(String releaseId, String targetVersion, List<Integer> stagesPercent,
                          int currentStage, int currentPercent, String status,
                          double failureRateThreshold, int assigned, int installed,
                          int failed, int rollingBack, int rolledBack, int inFlight,
                          double failureRate) {
}
