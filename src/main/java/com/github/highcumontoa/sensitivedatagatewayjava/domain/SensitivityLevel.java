package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 敏感等级：数字越大越敏感。
 */
public enum SensitivityLevel {
    L1(1),
    L2(2),
    L3(3),
    L4(4);

    private final int rank;

    SensitivityLevel(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }
}
