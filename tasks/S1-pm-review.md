# S1 验收报告 · 第 1 轮

## 结论

**通过**

## 门禁原始输出

非受限本地环境完整门禁，连续两次通过：

```text
G1 PASS repository and program directory whitelists passed
G2 PASS no AI, Python, frontend package, or unexpected dependency detected
G3 PASS schema, empty registry, valid sample, and invalid samples passed
G4 PASS compile and tests passed
G5 PASS api-to-kernel layering passed
G6 PASS application started with status=ok, modules=0 on port 64395
G7 PASS non-theme style files contain no literal colors, durations, or font families
G8 PASS three主体 anchors, settings controls, theme entry, and empty state are present

GATE PASS mode=full elapsed=19.3s
```

```text
G1 PASS repository and program directory whitelists passed
G2 PASS no AI, Python, frontend package, or unexpected dependency detected
G3 PASS schema, empty registry, valid sample, and invalid samples passed
G4 PASS compile and tests passed
G5 PASS api-to-kernel layering passed
G6 PASS application started with status=ok, modules=0 on port 61443
G7 PASS non-theme style files contain no literal colors, durations, or font families
G8 PASS three主体 anchors, settings controls, theme entry, and empty state are present

GATE PASS mode=full elapsed=19.1s
```

说明：受限沙箱中两次运行均在 javac 关闭系统临时目录中的 `spring-beans-6.1.14.jar` 时收到 `AccessDeniedException`，导致 G4/G6 失败；同一命令在非受限本地环境连续两次通过，因此判定为沙箱文件访问限制，不是项目实现失败。

## 模块化审计

| 检查点 | 结果 | 证据 |
|---|---|---|
| 框架是否含业务能力 | 通过 | `app/control/src/main/java/com/orderly/` 中未发现目录遍历、用户文件读写、删除或移动调用；`WorkspacePath` 只做词法路径校验。`ContractValidator` 仅读取应用自带契约文件，属于启动配置读取，不操作用户工作区。 |
| 框架是否硬编码具体模块 | 通过 | 未发现 `module` 包、具体模块实现或按模块 id 分支的代码；`app/contracts/registry.json` 为 `{"modules":[]}`。 |
| 模块是否自包含 | 通过 | S1 没有业务模块目录；不存在模块间 import 或互相依赖。 |
| 契约是否唯一 | 通过 | 字段与结果白名单只定义在 `app/contracts/module.schema.json`；前端未重复声明模块字段表，只读取演示 JSON 数据。 |
| 空注册表能否启动 | 通过 | 两次完整门禁 G6 均为 `status=ok, modules=0`，分别监听 64395、61443 端口。 |
| 结果是否结构化 | 通过 | `app/contracts/module.schema.json:102-137` 仅允许 `status`、`changed`、`counts`、`errorCode`、`inverse`，且 `additionalProperties:false`；健康检查返回 `status/modules/version` 结构化对象。 |
| 模块是否越权 | 通过 | S1 无业务模块；G7 通过，非主题文件没有色值、写死时长或字体名；未发现模块自有确认、进度或撤销流程。 |

## 报错归因

| 失败项 | 类别 | 责任角色 | 已排除的可能 |
|---|---|---|---|
| 沙箱门禁 G4：临时 JAR `AccessDeniedException` | 执行环境限制 | 无 | 已排除源码编译失败、缺失依赖和 JAR 损坏；非受限环境连续两次完整门禁通过。 |
| 沙箱门禁 G6：因 G4 未产出 JAR 而无法启动 | 派生失败 | 无 | 非受限环境 G6 两次健康检查均为 `modules=0`。 |

产品实现失败项：**无**。

## 界面逐条

| 编号 | 结果 | 独立验证现象 |
|---|---|---|
| U1 | 通过 | 输入“笔记”后仅保留“查阅笔记”；清空后恢复 6 条。 |
| U2 | 通过 | 将第一条拖到第二条之后，顺序由 `notes, calendar` 变为 `calendar, notes`，并写入本地存储；刷新后顺序保持。 |
| U3 | 通过 | `.o-sidebar__nav` 为 `overflow-y:auto`；补充条目后 `scrollHeight=1448 > clientHeight=542`。 |
| U4 | 通过 | 点击设置入口后弹窗 `open=true` 且可见。 |
| U5 | 通过 | 右上角关闭按钮可关闭；真实 Esc 按键事件可关闭。 |
| U6 | 通过 | 点击“功能设置”后系统面板隐藏、功能面板显示；设置分类侧栏为独立滚动区域。 |
| U7 | 通过 | 功能设置区同时存在 number、toggle、select 三种控件各 1 个。 |
| U8 | 通过 | dock 默认高 50px、宽 272px；悬停后展开为高约 201.8px、宽 768px。 |
| U9 | 通过 | 演示调用后生成 1 个红点；动画结束后目标条目出现 `is-called` 高亮。 |
| U10 | 通过 | 主题变量 `--accent-primary` 由 `#2E6BE6` 变为 `#167D68`；三个 stylesheet URL 前后完全一致，未替换样式文件。 |
| U11 | 通过 | 开启“减少动效”并刷新后，`body[data-o-motion=reduced]`，首屏标题立即可见且 `opacity=1`。 |
| U12 | 通过 | 直接打开 `index.html?empty=1` 后模块数为 0，空状态可见，文案为“还没有可显示的模块”，页面无 `undefined`。 |

其他界面结果：

```text
health: HTTP 200, status=ok, modules=0, version=0.1.0-SNAPSHOT
SSE: HTTP 200, text/event-stream, 收到 ":connected"
browser console/exceptions: none
```

## 分辨率检查

| 分辨率 | 横向溢出 | 三主体相交面积 |
|---|---|---|
| 1366×768 | 无：`scrollWidth=1366, clientWidth=1366` | 侧栏/主界面 0；侧栏/dock 0；主界面/dock 0 |
| 1920×1080 | 无：`scrollWidth=1920, clientWidth=1920` | 侧栏/主界面 0；侧栏/dock 0；主界面/dock 0 |

## 反向验证

在系统临时副本的 `app/web/css/components.css:1` 故意加入 `#123456`，完整门禁按预期变红：

```text
G1 PASS
G2 PASS
G3 PASS
G4 PASS
G5 PASS
G6 PASS application started with status=ok, modules=0 on port 51649
G7 C:\Users\lecoo\AppData\Local\Temp\orderly-pm-reverse-20261109\app\web\css\components.css:1 -> hex color in non-theme file (#123456) | Move the value to themes and use var(--semantic-token)
G8 PASS
GATE FAIL mode=full failures=1 elapsed=20.1s
```

原工作区文件未参与破坏性验证。

## 打回清单

无。
