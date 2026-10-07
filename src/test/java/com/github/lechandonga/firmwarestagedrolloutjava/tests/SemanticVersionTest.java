package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.model.SemanticVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：版本号按语义化版本规则比较；普通升级不接受降级，回滚是唯一允许的降级路径。
 */
@DisplayName("业务: 语义化版本比较")
class SemanticVersionTest {

    private static final Logger log = LoggerFactory.getLogger(SemanticVersionTest.class);

    @Test
    @DisplayName("用例: major/minor/patch 数值比较，2.0.0 > 1.9.9")
    void compareNumericParts() {
        log.info("【版本比较】用例-数值段比较：2.0.0 应大于 1.9.9");
        assertTrue(SemanticVersion.parse("2.0.0").isNewerThan(SemanticVersion.parse("1.9.9")));
        assertTrue(SemanticVersion.parse("1.0.1").isNewerThan(SemanticVersion.parse("1.0.0")));
        assertTrue(SemanticVersion.parse("1.1.0").isNewerThan(SemanticVersion.parse("1.0.9")));
        log.info("【版本比较】结果-通过：按数值而非字符串比较");
    }

    @Test
    @DisplayName("用例: 同版本相等，预发布版本低于同号正式版本")
    void comparePreRelease() {
        log.info("【版本比较】用例-预发布：1.0.0-rc1 < 1.0.0，且 1.0.0-rc1 < 1.0.0-rc2");
        assertEquals(0, SemanticVersion.parse("1.0.0").compareTo(SemanticVersion.parse("1.0.0")));
        assertTrue(SemanticVersion.parse("1.0.0").isNewerThan(SemanticVersion.parse("1.0.0-rc1")));
        assertTrue(SemanticVersion.parse("1.0.0-rc2").isNewerThan(SemanticVersion.parse("1.0.0-rc1")));
        assertTrue(SemanticVersion.parse("1.0.0-rc.2").isNewerThan(SemanticVersion.parse("1.0.0-rc.1")));
        log.info("【版本比较】结果-通过");
    }

    @Test
    @DisplayName("用例: 非法版本号被拒绝（供降级/重装判断做前置校验）")
    void rejectInvalidVersion() {
        log.info("【版本比较】用例-非法版本：v1 / 1.0 / 1.0.0.0 均应抛出异常");
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("v1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.0"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(""));
        log.info("【版本比较】结果-通过");
    }

    @Test
    @DisplayName("用例: 降级/同版本判定辅助方法符合预期")
    void downgradeHelpers() {
        log.info("【版本比较】用例-降级判定：1.0.0 对 2.0.0 为更旧（需拒绝，回滚除外）");
        assertTrue(SemanticVersion.parse("1.0.0").isOlderThan(SemanticVersion.parse("2.0.0")));
        assertFalse(SemanticVersion.parse("2.0.0").isOlderThan(SemanticVersion.parse("1.0.0")));
        log.info("【版本比较】结果-通过");
    }
}
