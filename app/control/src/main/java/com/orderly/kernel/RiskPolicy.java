package com.orderly.kernel;

public final class RiskPolicy {
    private RiskPolicy() {
    }

    public static RiskTier tierOf(String contractRisk) {
        return RiskTier.fromContract(contractRisk);
    }
}
