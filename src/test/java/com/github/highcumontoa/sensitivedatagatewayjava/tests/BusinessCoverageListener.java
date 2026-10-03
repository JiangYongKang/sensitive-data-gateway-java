package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务覆盖日志扩展：测试运行时按“业务（测试类）-> 用例（测试方法）”输出
 * 覆盖情况与结果，便于从单测日志直接看出覆盖了哪些业务以及各业务的用例情况。
 * 通过 META-INF/services + junit.jupiter.extensions.autodetection 自动注册，
 * 无需改动任何用例。
 */
public class BusinessCoverageListener implements BeforeAllCallback, AfterAllCallback, TestWatcher {

    private static final Logger log = LoggerFactory.getLogger(BusinessCoverageListener.class);

    private static final Map<String, AtomicInteger> PASSED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> FAILED = new ConcurrentHashMap<>();

    @Override
    public void beforeAll(ExtensionContext context) {
        log.info("[业务覆盖] 业务开始: {}", context.getDisplayName());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        PASSED.computeIfAbsent(context.getRequiredTestClass().getName(),
                k -> new AtomicInteger()).incrementAndGet();
        log.info("[业务覆盖]   用例通过: {}#{}",
                context.getRequiredTestClass().getSimpleName(), context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        FAILED.computeIfAbsent(context.getRequiredTestClass().getName(),
                k -> new AtomicInteger()).incrementAndGet();
        log.info("[业务覆盖]   用例失败: {}#{} -> {}",
                context.getRequiredTestClass().getSimpleName(), context.getDisplayName(), cause);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String cls = context.getRequiredTestClass().getName();
        int passed = PASSED.getOrDefault(cls, new AtomicInteger()).get();
        int failed = FAILED.getOrDefault(cls, new AtomicInteger()).get();
        log.info("[业务覆盖] 业务完成: {} (用例 {} 个, 通过 {}, 失败 {})",
                context.getDisplayName(), passed + failed, passed, failed);
    }
}
