package com.github.highcumontoa.sensitivedatagatewayjava.limit;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 默认守卫：按 UTF-8 字节计数，按 System.nanoTime 截止。
 */
@Component
public class DefaultRequestGuard implements RequestGuard {

    @Override
    public void checkPayloadSize(String rawJson, int maxPayloadBytes) {
        if (rawJson == null) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "请求体为空");
        }
        int bytes = rawJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxPayloadBytes) {
            throw new GatewayException(DecisionCode.LIMIT_PAYLOAD_TOO_LARGE,
                    "请求体 " + bytes + " 字节超过上限 " + maxPayloadBytes + "，请求被拒绝");
        }
    }

    @Override
    public Deadline start(long timeoutMillis) {
        long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
        return new Deadline() {
            @Override
            public boolean isExpired() {
                return System.nanoTime() - deadlineNanos > 0;
            }

            @Override
            public void checkExpired() {
                if (isExpired()) {
                    throw new GatewayException(DecisionCode.LIMIT_TIMEOUT,
                            "处理超过配置的时间上限，请求已拒绝");
                }
            }
        };
    }
}
