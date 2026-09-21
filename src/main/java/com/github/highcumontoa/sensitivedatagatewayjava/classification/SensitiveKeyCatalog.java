package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置敏感字段名目录：当字段名命中但当前策略未对其路径给出分级时，
 * 说明“数据疑似敏感但分级未定义”，必须显式拒绝（CLASSIFICATION_UNDEFINED），
 * 不得静默跳过或默认放行。
 */
public final class SensitiveKeyCatalog {

    private static final Map<String, SensitivityLevel> KEYS = new LinkedHashMap<>();

    static {
        // 低敏：一般标识
        put("name", SensitivityLevel.L1);
        put("fullName", SensitivityLevel.L1);
        put("username", SensitivityLevel.L1);
        put("email", SensitivityLevel.L2);
        put("phone", SensitivityLevel.L2);
        put("mobile", SensitivityLevel.L2);
        put("address", SensitivityLevel.L2);
        // 高敏：证件/金融/认证
        put("idCard", SensitivityLevel.L4);
        put("idNumber", SensitivityLevel.L4);
        put("passport", SensitivityLevel.L4);
        put("bankCard", SensitivityLevel.L4);
        put("cardNumber", SensitivityLevel.L4);
        put("password", SensitivityLevel.L4);
        put("secret", SensitivityLevel.L4);
        put("token", SensitivityLevel.L4);
        put("salary", SensitivityLevel.L3);
        put("income", SensitivityLevel.L3);
        put("creditScore", SensitivityLevel.L3);
    }

    private SensitiveKeyCatalog() {
    }

    private static void put(String key, SensitivityLevel level) {
        KEYS.put(key, level);
        KEYS.put(camelToSnake(key), level);
    }

    private static String camelToSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    public static boolean isSensitiveKey(String key) {
        return key != null && KEYS.containsKey(key);
    }

    public static SensitivityLevel hintLevel(String key) {
        return KEYS.get(key);
    }
}
