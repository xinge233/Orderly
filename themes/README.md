# 目录主题包

归序在宿主启动时扫描 `themes/` 的直接子目录。添加、替换或移除主题目录后，重启宿主即可生效，不需要重新构建 fat JAR，也不支持运行中热加载或热卸载。

## 目录结构

```text
themes/
└─ paper/
   ├─ theme.json
   ├─ tokens.css
   ├─ motion.css
   ├─ assets/
   └─ README.md
```

- 目录名必须与 `theme.json` 的 `id` 完全一致。
- 每个包必须包含 `theme.json`、`tokens.css` 和 `motion.css`。
- `theme.json` 必须声明 `schemaVersion: 1`，并通过 `styles` 登记 `tokens.css` 与 `motion.css`。
- 静态资源放在 `assets/`，每个文件都必须在清单 `assets` 数组中登记。
- 允许的主动资源类型仅为栅格图与字体：PNG、JPEG、WebP、GIF、AVIF、WOFF/WOFF2、TTF、OTF。SVG 和其它可承载脚本的主动内容不允许进入白名单。
- 主题包不能是符号链接，包内资源也不能经过链接。
- `_legacy-flat-json/` 只是迁移前的 JSON 归档，不会被宿主发现。

## 清单示例

```json
{
  "schemaVersion": 1,
  "id": "paper",
  "label": "纸页",
  "note": "温暖明亮",
  "order": 10,
  "default": true,
  "colorScheme": "light",
  "browserColor": "#f3efe7",
  "styles": ["tokens.css", "motion.css"],
  "assets": ["assets/paper-grain.webp"]
}
```

完整字段与路径规则以 `contracts/theme.schema.json` 为准。目录级只允许一个可用主题声明 `default: true`；重复 id 或多个默认主题会阻止宿主启动。

## 样式与动效

- `tokens.css` 负责颜色、字体、圆角、间距、时长和缓动等语义变量。
- `motion.css` 负责框架公开语义挂点对应的关键帧、过渡和组合动画。
- 主题 CSS 必须写入 `@layer orderly.theme { ... }`。
- 主题不得携带 JavaScript，也不得选择具体业务模块内部 DOM。
- 框架公开挂点只包括 `app-shell`、`sidebar`、`main`、`overview`、`module-card`、`settings-dialog`、`command-panel` 和 `feedback`。
- 桌面 `sidebar` 是固定 272px 覆盖层，收起时通过 `clip-path` 只露出 72px 图标轨道；主内容网格始终只预留 72px。72px 轨道边线由侧栏背景层绘制，展开态清除该背景线并保留覆盖层自身的右边框，不能用覆盖内容的伪元素制造边线。主题只负责该挂点公开状态的视觉与动效，不得改变主内容重排规则或侧栏内部几何。
- `sidebar` 桌面端只使用 `clip-path var(--duration-sidebar) var(--ease-out)` 过渡，不再动画 `width`、内边距、文字或高度；移动抽屉使用 `clip-path: none` 并单独保留 `transform` 过渡。基础 `app.css` 不声明过渡。`attention` 是由计划目标提示短暂覆盖的动效状态，结束后必须回到真实的 `expanded` 或 `collapsed` 状态。
- 默认主题的 `--duration-sidebar` 为 `200ms`，克制动效为 `110ms`，`motion: none` 与 `prefers-reduced-motion` 为 `0ms`。主题不得在该侧栏规则中写死时长。
- 模块正整数数量由框架外置到 `#sidebar-count-layer` 的左侧带尾聊天气泡中。气泡在收起态锚定图标轨道右侧，在展开过程中持续跟踪模块文字边界；主题不得选择或改写该气泡内部结构。
- 根代码保留 `orderly.safety` 层，优先关闭 `motion: none` 与 `prefers-reduced-motion` 下的动画、过渡和滚动行为。

主题 CSS 可以直接引用已登记的相对资源。由于样式表通过 `/api/themes/{id}/assets/tokens.css` 提供，`tokens.css` 内的 `url("assets/paper-grain.webp")` 会解析为 `/api/themes/{id}/assets/assets/paper-grain.webp`：

```css
html[data-theme="paper"] body {
    background-image: url("assets/paper-grain.webp");
}
```

不要在自定义属性中保存 `url(...)` 后再由基础样式消费，例如 `--theme-texture: url("assets/...")`。浏览器会按实际消费该变量的基础样式表位置解析相对 URL，可能请求错误路径。相对资源 URL 应直接声明在主题自己的 `tokens.css` 或 `motion.css` 中。

## 局部错误与回退

清单损坏、版本不支持、缺少样式、路径越界、链接资源或不可读资源只会让对应主题不可用，并出现在设置页的局部错误区域。其他主题仍可加载。

前端应用顺序固定为：当前可用主题 -> 可用默认主题 -> 内置安全外观。不会自动选择排序后的第一项作为默认。
