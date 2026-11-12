package com.orderly.host.theme;

/** 主题包内已登记、可直接返回给浏览器的静态资源。 */
public record ThemeAsset(String contentType, byte[] content) {
    public ThemeAsset {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
