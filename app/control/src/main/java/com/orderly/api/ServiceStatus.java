package com.orderly.api;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ServiceStatus {
    OK;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }
}
