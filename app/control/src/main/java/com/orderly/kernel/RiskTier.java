package com.orderly.kernel;

public enum RiskTier {
    READ_ONLY("read_only"),
    WRITE("write"),
    DELETE("delete");

    private final String contractValue;

    RiskTier(String contractValue) {
        this.contractValue = contractValue;
    }

    public String contractValue() {
        return contractValue;
    }

    public static RiskTier fromContract(String value) {
        for (RiskTier tier : values()) {
            if (tier.contractValue.equals(value)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown risk tier: " + value);
    }
}
