package com.orderly.api;

public record HealthResponse(ServiceStatus status, int modules, String version) {
}
