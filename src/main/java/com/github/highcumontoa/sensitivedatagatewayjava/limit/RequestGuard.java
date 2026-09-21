package com.github.highcumontoa.sensitivedatagatewayjava.limit;

/**
 * 请求规模/耗时守卫。
 */
public interface RequestGuard {

    /**
     * 校验请求体字节规模，超限抛出可区分错误。
     */
    void checkPayloadSize(String rawJson, int maxPayloadBytes);

    /**
     * 返回带截止时间的处理令牌；处理中各阶段可检查是否超时。
     */
    Deadline start(long timeoutMillis);

    /** 处理截止时间。 */
    interface Deadline {
        boolean isExpired();

        void checkExpired();
    }
}
