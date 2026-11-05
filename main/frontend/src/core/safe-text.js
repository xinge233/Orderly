/**
 * 将外部或后端文本安全写入已有元素。
 *
 * textContent 不会把字符串解释成 HTML，统一经过此函数可以清楚区分
 * “可信静态模板”与“动态文本”。缺少目标元素时返回 false 交由调用方决定。
 *
 * @param {ParentNode} root 查询根
 * @param {string} selector 目标选择器
 * @param {unknown} value 动态文本
 * @returns {boolean} 成功写入时返回 true
 */
export function setText(root, selector, value) {
    const element = root.querySelector(selector);
    if (!element) {
        return false;
    }
    element.textContent = String(value ?? "");
    return true;
}
