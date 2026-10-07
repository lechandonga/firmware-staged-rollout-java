package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import com.github.lechandonga.firmwarestagedrolloutjava.model.SemanticVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：语义化版本比较规则 —— 数值段、预发布、构建后缀、非法格式。
 */
@ExtendWith(BusinessLoggingExtension.class)
class SemanticVersionTest {

    @Test
    @DisplayName("MAJOR.MINOR.PATCH 按数值比较, 前导数位不改变大小")
    void numericOrder() {
        assertTrue(v("1.2.3").compareTo(v("1.2.4")) < 0);
        assertTrue(v("1.2.10").compareTo(v("1.2.9")) > 0);
        assertTrue(v("2.0.0").compareTo(v("1.99.99")) > 0);
        assertEquals(0, v("1.2.3").compareTo(v("1.2.3")));
    }

    @Test
    @DisplayName("预发布版本低于同号正式版本; 预发布按 semver 标识符规则比较")
    void prereleaseOrder() {
        assertTrue(v("1.0.0-alpha").compareTo(v("1.0.0")) < 0);
        assertTrue(v("1.0.0-alpha").compareTo(v("1.0.0-alpha.1")) < 0);
        assertTrue(v("1.0.0-alpha.1").compareTo(v("1.0.0-alpha.beta")) < 0);
        assertTrue(v("1.0.0-rc.1").compareTo(v("1.0.0-rc.2")) < 0);
    }

    @Test
    @DisplayName("构建后缀不参与比较")
    void buildMetadataIgnored() {
        assertEquals(0, v("1.0.0+exp.sha.5114f85").compareTo(v("1.0.0")));
    }

    @Test
    @DisplayName("非法版本号抛出 IllegalArgumentException")
    void invalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("v1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(""));
    }

    private SemanticVersion v(String text) {
        return SemanticVersion.parse(text);
    }
}
