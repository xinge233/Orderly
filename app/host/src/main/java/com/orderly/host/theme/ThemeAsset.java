package com.orderly.host.theme;

/**
 * 主题包内已通过白名单读取的浏览器静态资源。
 *
 * <p>实例防御性复制字节数组，访问时再次复制，避免调用方通过数组引用修改内部内容。
 * 内容类型来自固定扩展名白名单，不由主题清单决定。</p>
 *
 * @param contentType 浏览器内容类型
 * @param content 资源字节
 */
public record ThemeAsset(String contentType, byte[] content) {
    /** 保存资源字节的独立副本；不校验内容类型与字节内容是否匹配。 */
    public ThemeAsset {
        content = content.clone();
    }

    /** 返回资源字节的副本；调用方修改不会影响已创建的资源对象。 */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
