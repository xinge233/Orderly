package com.orderly.host.ai;

/** 仅存在于当前进程内的模型连接配置。 */
public record AiProviderConfig(
        AiContracts.Mode mode,
        String baseUrl,
        String model,
        String apiKey,
        boolean allowDeleteDirect
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "AiProviderConfig["
                + "mode=" + mode
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", apiKey=" + (configured() ? "[redacted]" : null)
                + ", allowDeleteDirect=" + allowDeleteDirect
                + "]";
    }
}
