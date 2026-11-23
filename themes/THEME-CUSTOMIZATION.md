# 主题外观与动效定制

本文件说明目录主题可以调整哪些外观与动效。主题包结构、清单字段和加载回退规则仍以 [README.md](README.md) 与 [theme.schema.json](../contracts/theme.schema.json) 为准。

主题只提供语义取值和声明式 CSS。它不改变页面结构、业务状态、授权规则或模块能力，也不得覆盖具体业务模块内部 DOM。

## 1. 文件职责

| 文件 | 可定制内容 |
|---|---|
| `theme.json` | 主题名称、说明、排序、是否默认、颜色方案、浏览器主题色、样式和资源登记 |
| `tokens.css` | 颜色、字体、字号、圆角、间距、阴影和纹理等语义外观 |
| `motion.css` | 框架公开动效挂点上的过渡、关键帧和组合动画 |
| `assets/` | 清单登记的本地位图、AI 图标 mask 与字体资源 |

目录名必须与 `theme.json` 的 `id` 一致。主题 CSS 必须写入 `@layer orderly.theme { ... }`，并使用 `html[data-theme="<主题 id>"]` 限定作用范围。

## 2. 可直接调整的外观

未声明的变量会继承框架基础值。主题应按实际设计需要覆写，不必复制完整令牌表。基础值定义在 [tokens.css](../app/frontend/styles/tokens.css)。

### 2.1 画布与表面

| 变量 | 用途 |
|---|---|
| `--color-canvas` | 应用最底层画布 |
| `--color-canvas-deep` | 画布深层背景或低对比衬底 |
| `--color-sidebar` | 左侧导航背景 |
| `--color-surface` | 普通卡片、面板和内容表面 |
| `--color-surface-raised` | 浮层、弹窗和高一级表面 |
| `--color-surface-muted` | 次要分区、空状态和弱化背景 |
| `--color-surface-strong` | 选中、悬停或较强分区背景 |

### 2.2 文字与边界

| 变量 | 用途 |
|---|---|
| `--color-text` | 主要标题和正文 |
| `--color-text-soft` | 次级说明与辅助文本 |
| `--color-text-faint` | 时间、占位和最低层级文本 |
| `--color-line` | 普通分隔线与边框 |
| `--color-line-strong` | 聚焦边界、强调分隔和较明显轮廓 |

`--color-text-faint` 仍应保持可读，不得只追求低对比视觉而牺牲辅助信息。

### 2.3 主色与辅助色

| 变量 | 用途 |
|---|---|
| `--color-accent` | 主操作、选中态和主要强调 |
| `--color-accent-strong` | 主操作按下、悬停和更深强调 |
| `--color-accent-soft` | 主色弱背景、标签和选中衬底 |
| `--color-accent-contrast` | 主色实心背景上的文字或图标 |
| `--color-secondary` | 辅助分类、次强调和非危险提示 |
| `--color-secondary-soft` | 辅助色弱背景 |

主操作文字与背景、正文与卡片背景、焦点与相邻背景应达到可辨识的对比度。

### 2.4 状态语义色

| 变量 | 用途 |
|---|---|
| `--color-safe` / `--color-safe-soft` | 成功、完成、可用和安全状态 |
| `--color-warning` / `--color-warning-soft` | 等待、注意和需要复查的状态 |
| `--color-danger` / `--color-danger-soft` | 失败、删除和阻断状态 |
| `--color-focus` | 键盘焦点环 |
| `--color-overlay` | 遮罩、投影底色和浮层衬底 |

状态色不能只作为装饰替换。成功、警告和失败必须在文字、图标或状态标签之外仍保持一致的语义。

### 2.5 字体与字号

| 变量 | 用途 |
|---|---|
| `--font-ui` | 导航、正文、按钮和普通界面文字 |
| `--font-display` | 主视觉标题和展示性标题 |
| `--font-mono` | 路径、代码、计数与等宽信息 |
| `--font-size-caption` | 最小辅助文字 |
| `--font-size-small` | 次要说明 |
| `--font-size-body` | 正文 |
| `--font-size-control` | 输入、按钮和导航控制 |
| `--font-size-panel-title` | 面板级标题 |

使用本地字体时，字体文件必须放入 `assets/` 并在 `theme.json` 的 `assets` 中登记。不得引用远程字体服务。

### 2.6 圆角与间距

主题可以调整以下语义值，但改变后必须重新检查卡片、弹窗、按钮、表格和窄屏布局：

| 变量组 | 用途 |
|---|---|
| `--radius-xs` 至 `--radius-xl` | 小标签、控件、卡片、面板和浮层圆角 |
| `--space-1` 至 `--space-12` | 组件内部与页面区块的统一间距 |

不要把间距改成页面专用像素组合，也不要为某个模块增加独立间距。模块应继续消费同一组框架语义变量。

### 2.7 阴影与纹理

| 变量 | 用途 |
|---|---|
| `--shadow-soft` | 弹窗、浮层和强抬升表面 |
| `--shadow-card` | 普通卡片阴影 |
| `--grid-line` | 基础背景网格线 |

纹理资源应登记在 `assets/`，并在主题自己的 CSS 中直接声明 `url("assets/<file>")`。不要把 `url(...)` 存入自定义属性后再交给基础样式解析，否则相对路径可能指向错误位置。

### 2.8 AI 图标贴图

主题可以通过 `aiIconAssets` 为 AI Dock 的四枚身份图标提供 PNG 贴图：

```json
{
  "aiIconAssets": {
    "spark": "assets/ai/icons/spark.png",
    "route": "assets/ai/icons/route.png",
    "shield": "assets/ai/icons/shield.png",
    "modules": "assets/ai/icons/modules.png"
  },
  "assets": [
    "assets/ai/icons/spark.png",
    "assets/ai/icons/route.png",
    "assets/ai/icons/shield.png",
    "assets/ai/icons/modules.png"
  ]
}
```

- 只允许 `spark`、`route`、`shield`、`modules` 四个固定 ID。
- 只允许 `assets/ai/icons/<id>.png`，且路径必须同时登记在 `assets`。
- PNG 应为透明单色 alpha mask；前端使用 `currentColor` 着色，不直接显示 PNG 的像素颜色。
- 用户选择的是图标 ID，不是主题文件。切换主题后保持同一 ID，只替换视觉贴图。
- 主题缺少某个 ID、清单未声明 `aiIconAssets` 或图片加载失败时，AI Dock 回退到框架通用图形。

当前 AI 身份图标只用于收起态入口、展开后的生成按钮和设置页图标预览。关闭、执行、警告、风险盾牌等通用图标仍属于框架，不随主题 AI 贴图迁移。

### 2.9 应保留的框架令牌

以下令牌属于运行时安全或结构性约束，普通主题不应覆写：

| 保留项 | 原因 |
|---|---|
| `--duration-fast`、`--duration-normal`、`--duration-slow`、`--duration-sidebar`、`--duration-ai-call` | 由程序的“灵动 / 克制 / 关闭”三档统一控制，主题写死会让关闭动效失效 |
| `--sidebar-width`、`--sidebar-expanded-width`、`--sidebar-row-height`、`--sidebar-icon-size` | 维持桌面 72px 轨道、272px 覆盖层和移动抽屉的既定几何 |
| `--ai-*` 面板与入口尺寸 | 维持 AI Dock 的拖动、吸附、展开和窄屏边界 |
| `--mobile-toolbar-height`、`--content-max` | 维持移动工具栏和主内容宽度约束 |

如果设计确实需要改变这些结构令牌，应作为框架布局任务单独评估，而不是在普通主题包中覆盖。

## 3. 可调整的动效

主题只能选择框架公开挂点。当前挂点和常用状态如下：

| 挂点 | 常用状态 | 适合的动效 |
|---|---|---|
| `app-shell` | `ready`、`busy` | 全局忙碌提示、低强度状态反馈 |
| `sidebar` | `collapsed`、`expanded`、`open`、`attention` | 桌面 `clip-path` 展开、移动 `transform`、短暂注意提示 |
| `main` | `loading`、`view-change`、`error` | 加载指示、视图切换、错误出现 |
| `overview` | `entering` | 总览内容首次进入 |
| `module-card` | `entering`、`reordering` | 卡片进入、拖动排序和落位反馈 |
| `settings-dialog` | `open`、`closing`、`closed` | 设置弹窗出现和退场 |
| `ai-dialog` | `data-mode`、`data-composer`、`data-panel`、`data-popover` | AI 入口、输入区、计划面板和运行状态 |
| `feedback` | `idle`、`success`、`error`、`running` | 提示条出现、结果反馈和目标提示 |

动效必须使用框架时长与缓动变量，例如 `var(--duration-normal)`、`var(--duration-slow)` 和 `var(--ease-out)`。不得在主题中写死秒数或毫秒数。

### 3.1 示例

```css
@layer orderly.theme {
    html[data-theme="my-theme"] [data-motion-scope="main"][data-motion-state="view-change"] {
        animation: my-view-enter var(--duration-normal) var(--ease-out) both;
    }

    html[data-theme="my-theme"] [data-motion-scope="module-card"] {
        transition:
            transform var(--duration-fast) var(--ease-out),
            border-color var(--duration-fast) var(--ease-out);
    }

    html[data-theme="my-theme"] [data-motion-scope="sidebar"] {
        transition: clip-path var(--duration-sidebar) var(--ease-out);
    }

    @media (max-width: 820px) {
        html[data-theme="my-theme"] [data-motion-scope="sidebar"] {
            transition: transform var(--duration-normal) var(--ease-out);
        }
    }

    @keyframes my-view-enter {
        from {
            opacity: 0;
            transform: translateY(10px);
        }
        to {
            opacity: 1;
            transform: translateY(0);
        }
    }
}
```

### 3.2 动效限制

- 桌面侧栏只通过 `clip-path` 过渡，不通过 `width`、内边距、文字或高度重排主内容。
- 移动侧栏使用独立 `transform` 过渡，不复用桌面裁切规则。
- 不得使用 `!important` 绕过框架的动效关闭安全层。
- 不得读取或响应 `prefers-reduced-motion`；动效档位只由程序内设置控制。
- 不得让进入、退出、加载或错误状态依赖动画结束才显示。关闭动效后内容必须立即可见。
- 持续动画只用于真实的加载、运行或等待状态，不得用动画掩盖失败、空数据或未接入能力。
- 关键帧应使用主题前缀命名，避免与框架或其他主题冲突。

## 4. 资源与选择器边界

- 仅允许登记 PNG、JPEG、WebP、GIF、AVIF、WOFF、WOFF2、TTF 和 OTF。
- 不允许 JavaScript、SVG、远程脚本、远程样式和追踪资源。
- 不得选择 `module-view`、业务模块内部类名或宿主数据节点。
- 不得通过 `display: none`、`visibility: hidden` 或 `content` 增删业务文案。
- 不得修改授权、确认、删除、快照、回滚、模块发现或文件访问语义。

## 5. 交付前检查

| 检查项 | 最低要求 |
|---|---|
| 主题清单 | `id` 与目录一致，样式和资源全部登记，最多一个可用默认主题 |
| 资源 | 无 SVG、JavaScript、外部 URL、路径逃逸或未登记文件 |
| 对比度 | 正文、辅助文字、主操作、状态和焦点在桌面与窄屏均可辨识 |
| 布局 | 常用桌面宽度和 390px 左右窄屏无横向溢出、遮挡或不可操作控件 |
| 动效三档 | “灵动”“克制”“关闭”均可切换；关闭后全部状态与内容仍可见 |
| 侧栏 | 桌面裁切、移动抽屉、键盘焦点和展开后的内部控件正常 |
| 模块隔离 | 主题不按模块写特判，不选择模块内部 DOM |
| 回退 | 主题 CSS 加载失败时不影响其他主题和内置安全外观 |

新增或替换主题后需要重启宿主。宿主运行中不支持热加载主题。
