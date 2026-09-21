package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 一次请求实际使用的策略版本解析结论，供审计使用。
 *
 * @param requestedVersion 请求显式指定的版本（可空）
 * @param resolvedVersion  实际生效版本
 * @param latestVersion    当前存储中最新版本
 * @param fallbackDenied   是否因显式回退旧版本而被拒绝
 */
public record PolicyVersionResolution(
        String requestedVersion,
        String resolvedVersion,
        String latestVersion,
        boolean fallbackDenied) {
}
