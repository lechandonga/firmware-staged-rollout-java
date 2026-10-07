package com.github.lechandonga.firmwarestagedrolloutjava.model;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义化版本号（SemVer），只比较版本本身，忽略预发布后缀之外的构建元数据。
 * 比较规则：major.minor.patch 数值比较；带预发布后缀（如 1.0.0-rc1）的版本
 * 低于同号正式版本。
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;

    private SemanticVersion(int major, int minor, int patch, String preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    public static SemanticVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        Matcher matcher = SEMVER.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("非法语义化版本号: " + raw);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        return new SemanticVersion(major, minor, patch, matcher.group(4));
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(patch, other.patch);
        if (cmp != 0) {
            return cmp;
        }
        if (Objects.equals(preRelease, other.preRelease)) {
            return 0;
        }
        if (preRelease == null) {
            return 1; // 正式版本 > 同号预发布版本
        }
        if (other.preRelease == null) {
            return -1;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            if (i >= left.length) {
                return -1; // 字段更少的预发布版本更低
            }
 if (i >= right.length) {
                return 1;
            }
            int cmp = compareIdentifier(left[i], right[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNumeric = a.chars().allMatch(Character::isDigit);
        boolean bNumeric = b.chars().allMatch(Character::isDigit);
        if (aNumeric && bNumeric) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (aNumeric) {
            return -1; // 数字标识低于字母数字标识
        }
        if (bNumeric) {
            return 1;
        }
        return a.compareTo(b);
    }

    public boolean isNewerThan(SemanticVersion other) {
        return compareTo(other) > 0;
    }

    public boolean isOlderThan(SemanticVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SemanticVersion other)) {
            return false;
        }
        return compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        return preRelease == null ? core : core + "-" + preRelease;
    }
}
