# UI 热预览

该目录只服务本地设计迭代，不会复制到宿主静态资源包。

## 启动

先保持 Java 宿主运行在 `127.0.0.1:8080`，再打开另一个终端：

```powershell
Set-Location app/frontend
npm install
npm run dev:preview
```

访问 `http://127.0.0.1:5173/`。

- 修改 `app/frontend/styles/**` 时由 Vite 直接热替换 CSS。
- 修改 `themes/*/tokens.css`、`themes/*/motion.css` 或模块 `orderly-ui/*.css` 时自动整页刷新。
- 修改主题清单、增删主题或修改模块清单后仍需重启 Java 宿主。
- `/api/**` 会代理到现有 Java 宿主，真实数据、授权和执行请求不经过第二套实现。
- `Alt+P` 可收起或展开开发预览控制条。

如需连接其他宿主端口：

```powershell
$env:ORDERLY_PREVIEW_API = "http://127.0.0.1:8081"
npm run dev:preview
```

预览控制条可以切换主题、动效档位、高亮动效挂点、回放 AI 调用模块时的侧栏进度与成功标记，并重新播放页面进入动效。它仅由 Vite 开发服务器注入。

## 自动截图

保持 Vite 预览运行，再执行：

```powershell
npm run preview:capture
```

脚本会使用本机 Microsoft Edge 无头模式截取桌面、平板、390px 窄屏、设置弹窗、AI Dock 和关闭动效状态，输出到仓库忽略目录 `target/ui-preview/`。可通过 `ORDERLY_EDGE_PATH` 和 `ORDERLY_PREVIEW_URL` 覆盖浏览器或预览地址。
