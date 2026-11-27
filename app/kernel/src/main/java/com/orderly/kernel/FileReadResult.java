package com.orderly.kernel;

/**
 * 受控文本读取结果。
 *
 * <p>读取按字符分页，不把宿主机绝对路径返回给模块或模型。{@code nextOffset} 可用于
 * 继续读取同一文件；{@code eof} 仅表示本次读取已到达末尾或读取上限。</p>
 *
 * @param content 本次读取的文本
 * @param offset 本次起始字符偏移
 * @param nextOffset 下次继续读取的字符偏移
 * @param size 文件字节数
 * @param eof 是否已到达文件末尾
 * @param encoding 文本编码
 */
public record FileReadResult(
        String content,
        int offset,
        int nextOffset,
        long size,
        boolean eof,
        String encoding
) {
}
