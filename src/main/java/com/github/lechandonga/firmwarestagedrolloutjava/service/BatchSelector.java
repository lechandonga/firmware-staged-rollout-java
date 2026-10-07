package com.github.lechandonga.firmwarestagedrolloutjava.service;

/**
 * 基于 FNV-1a 64 位哈希的稳定分批选择器。
 *
 * <p>分片键为 {@code releaseId:deviceId}，取哈希绝对值模 100 得到设备在该发布中的
 * 固定槽位（0-99）。批次按累计覆盖百分比划分：槽位落在 (上一批累计比例, 本批累计比例]
 * 的设备属于本批。结果只取决于发布 ID、设备 ID 与批次配置，多次评估完全一致，
 * 与激活顺序、上报顺序无关。
 */
public final class BatchSelector {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private BatchSelector() {
    }

    public static int slot(String releaseId, String deviceId) {
        long hash = FNV_OFFSET_BASIS;
        String key = releaseId + ":" + deviceId;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= FNV_PRIME;
        }
        return (int) (Math.abs(hash) % 100);
    }

    /**
     * 返回设备所属批次下标（基于累计百分比）；当前配置未覆盖该槽位时返回 -1。
     */
    public static int selectBatchIndex(String releaseId, String deviceId, int[] cumulativePercents) {
        int slot = slot(releaseId, deviceId);
        for (int i = 0; i < cumulativePercents.length; i++) {
            if (slot < cumulativePercents[i]) {
                return i;
            }
        }
        return -1;
    }
}
