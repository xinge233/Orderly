package com.orderly.kernel;

import java.util.List;

public final class ContractValidationException extends RuntimeException {
    private final List<String> violations;

    public ContractValidationException(String message, List<String> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
