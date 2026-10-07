package com.github.lechandonga.firmwarestagedrolloutjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 单测业务覆盖日志：按测试类（业务域）打印开始横幅、逐用例执行结果与小结，
 * 便于从 mvn 输出中直接看出覆盖了哪些业务以及每个用例的通过情况。
 */
public class BusinessLoggingExtension implements
        BeforeAllCallback, BeforeTestExecutionCallback, AfterTestExecutionCallback, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS_COVERAGE");

    private int passed;
    private int failed;

    @Override
    public void beforeAll(ExtensionContext context) {
        passed = 0;
        failed = 0;
        log.info("");
        log.info("==================== 业务域: {} ====================",
                context.getRequiredTestClass().getSimpleName());
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info(">> [用例开始] {}", caseName(context));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Optional<Throwable> error = context.getExecutionException();
        if (error.isPresent()) {
            failed++;
            log.info("<< [用例失败] {} -> {}: {}", caseName(context),
                    error.get().getClass().getSimpleName(), error.get().getMessage());
        } else {
            passed++;
            log.info("<< [用例通过] {}", caseName(context));
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        log.info("---- 业务域 {} 小结: 通过 {} 个, 失败 {} 个, 合计 {} 个 ----",
                context.getRequiredTestClass().getSimpleName(),
                passed, failed, passed + failed);
        log.info("");
    }

    private String caseName(ExtensionContext context) {
        return context.getDisplayName();
    }
}
