package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 敏感数据分级。rank 越大敏感度越高。
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

    public boolean higherThan(SensitivityLevel other) {
        return this.rank > other.rank;
    }
}
