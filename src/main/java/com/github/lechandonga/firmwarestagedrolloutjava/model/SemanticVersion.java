package com.github.lechandonga.firmwarestagedrolloutjava.model;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义化版本号：MAJOR.MINOR.PATCH，可选 '-' 预发布后缀与 '+' 构建后缀。
 * 比较规则遵循 semver 2.0.0 的核心思想：数值段按数值比较，预发布版本低于正式版本。
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;
    private final String raw;

    private SemanticVersion(int major, int minor, int patch, String prerelease, String raw) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.raw = raw;
    }

    public static SemanticVersion parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        String value = text.trim();
        Matcher matcher = SEMVER.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("非法语义化版本号: " + text);
        }
        return new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4),
                value);
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }
        return comparePrerelease(prerelease, other.prerelease);
    }

    private static int comparePrerelease(String left, String right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return 1; // 正式版本 > 预发布版本
        }
        if (right == null) {
            return -1;
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int count = Math.min(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            int cmp = compareIdentifier(leftParts[i], rightParts[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        }
        if (leftNumeric) {
            return -1; // 数字标识符优先级低于字母标识符
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }
}
