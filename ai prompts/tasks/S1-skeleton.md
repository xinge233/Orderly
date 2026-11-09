# S1-skeleton · 框架骨架

> 本任务是 S1 阶段唯一的总任务卡。三位实现角色共用同一份目标，各自只取属于自己的部分。
> 派发时按角色拆分：guardian 全量，engineer 取 §3、§5、§7，ui 取 §4、§5、§8，pm 取 §9。

---

## 一句话目标

让仓库在干净克隆后一条命令能起、浏览器能打开三主体界面、注册表以 0 个模块正常启动、门禁一条命令全绿。

---

## 1. owned_paths（按角色）

| 角色 | owned_paths | 只读 |
|---|---|---|
| `guardian` | `contracts/`、`docs/` | 其余全部 |
| `engineer` | `control/`、`scripts/` | 其余全部 |
| `ui` | `web/` | 其余全部 |
| `pm` | `tasks/`（仅 `S1-pm-review.md`） | 其余全部 |
| `orchestrator` | `tasks/*.handoff.md` | 全部 |

---

## 2. forbidden（全体）

```
ai/                    S1 不存在，连空目录都不建
任何 .py 文件
任何真实文件系统读写（control/ 与 web/ 均不允许）
任何数据库、ORM、迁移
任何模型调用、任何出站 HTTP 请求
任何第三方依赖（前端无 npm，后端无新增 Maven 依赖）
任何业务模块（文件、目录、待办、日程一律不做）
AGENTS.md 与 prompts/  仅人可改
别人的 owned_paths    只读
```

---

## 3. guardian 的交付物

| # | 交付物 | 验证方式 |
|---|---|---|
| 1 | `contracts/module.schema.json` | 12 字段齐全，`additionalProperties: false` |
| 2 | `contracts/registry.json` | `modules: []`，通过自身 Schema |
| 3 | `contracts/samples/valid/*.json` | 至少 1 份，覆盖全部字段 |
| 4 | `contracts/samples/invalid/*.json` | 至少 4 份，每份只错一处，报错能指到字段 |
| 5 | `contracts/RFC-0001-module-ui.md` | 定义 `ui` 三键与四个插槽 |
| 6 | `contracts/RFC-TEMPLATE.md` | 后续结构变更的入口 |
| 7 | `docs/MODULAR-CONTRACT.md` | 与 Schema 一一对应，无实现代码 |
| 8 | `docs/UI-SPEC.md` | 插槽名清单、语义变量表、图标清单、动效三档归属 |

冻结条件：见 `prompts/roles/contract-guardian.md`「冻结的判断」五条。全过才写"契约已冻结"。

---

## 4. ui 的交付物

| # | 交付物 | 验证方式 |
|---|---|---|
| 1 | `web/index.html` | 三主体 + 设置弹窗 + 六个 G8 锚点齐全 |
| 2 | `web/css/{reset,layout,components}.css` | 无色值、无写死时长、无字体名 |
| 3 | `web/js/{shell,registry,theme,settings,dock,motion}.js` | 原生，无依赖 |
| 4 | `web/themes/{default,minimal}.json` | `minimal` 只覆盖两个键 |
| 5 | `web/mock/registry.json` | 4~6 条演示数据，带 `_note` 标注 |

验收：`prompts/01-stage-scope.md` §5 的 U1~U12 逐条通过 + 两个分辨率下不重叠。

---

## 5. engineer 的交付物

| # | 交付物 | 验证方式 |
|---|---|---|
| 1 | `control/` 应用入口 | 能启动、能优雅退出 |
| 2 | `kernel/ModuleRegistry` | 读 `registry.json`、按 Schema 校验、**0 模块启动成功** |
| 3 | `kernel/RiskPolicy` | 三档枚举 + 判定，不含流程 |
| 4 | `kernel/WorkspacePath` | 签名 + 空实现，不碰磁盘 |
| 5 | `api/` 健康检查 | 返回结构化状态（状态、模块数、版本），不是句子 |
| 6 | `api/` SSE 端点 | 可连接，不产事件，不报错 |
| 7 | `scripts/gate.cmd` + `.ps1` | G1~G8 全实现，一次输出所有失败项，< 90 秒 |
| 8 | 启动脚本 | 端口检查 → 起服务 → 轮询 → 开浏览器 |
| 9 | 单元测试 | 注册表空载、Schema 校验、非法样例拒绝 |

---

## 6. 依赖关系

```
guardian(§3) ──冻结──┬──→ engineer(§5.2~§5.6)
                     └──→ ui(§4.1~§4.3)
engineer(§5.7 gate) ─────→ pm(§9 全量跑)
ui(§4.4 锚点) ───────────→ engineer(§5.7 G8 断言) ← 这一条要倒过来沟通：
                            锚点名由 ui 定并写进 docs/UI-SPEC.md，engineer 读文档而非读 HTML
```

注意最后一条：**engineer 不要直接去 `web/index.html` 里找节点名。** 那样一旦 ui 改名，门禁就静默失效。正确做法是 ui 把锚点名登记进 `docs/UI-SPEC.md`，engineer 从文档读取并写进 G8。

---

## 7. engineer 的实现顺序

1. G1 / G2（最简单，立刻挡住越阶段）
2. G4（编译与单测，其余检查的载体）
3. G3（等 guardian 的 Schema）
4. G6（等控制层能启动）
5. G5（等包结构稳定）
6. G7 / G8（等 `web/` 有内容）

前五项跑通前不要开始写 G7/G8，否则会为调正则反复返工。

---

## 8. ui 的实现顺序

1. 布局骨架（三主体能站住，两个分辨率不重叠）
2. 主题系统（变量写入、切换、`minimal.json` 部分覆盖）
3. 侧边栏（搜索、拖拽、滚动、设置入口）
4. 设置弹窗（开合、左右联动、三种控件）
5. AI 对话栏（收起/展开、红点飞行）
6. 降级（减少动效、首屏、空注册表、SSE 失败）

顺序不能颠倒：主题系统必须在侧边栏之前，否则你会先把色值写进 `components.css`，然后被迫回头改。

---

## 9. pm 的验收

按 `prompts/roles/pm.md` 执行，输出 `tasks/S1-pm-review.md`。必须包含：

- [ ] gate 原始输出（完整模式）
- [ ] 模块化审计七项逐条 + 证据
- [ ] 报错归因表（或"无"）
- [ ] U1~U12 逐条结论
- [ ] 两个分辨率检查
- [ ] **反向验证**：故意破坏一处，确认门禁会红
- [ ] 结论：通过 / 打回（不许"基本通过"）

---

## 10. open_questions

开工前需编排者确认。未确认时按默认处理。

| # | 问题 | 默认处理 |
|---|---|---|
| 1 | 仓库根是否就是提示词投放目录 | 是。`AGENTS.md` 与 `prompts/` 直接放在仓库根 |
| 2 | 端口号用哪个 | 8080，被占用时脚本自动 +1 并打印实际端口 |
| 3 | 演示数据几条 | 6 条，覆盖"只读/写/删除"三档风险各两条 |
| 4 | 图标从哪来 | 用 CSS 或内联方式自绘几何图形，清单登记在 `docs/UI-SPEC.md`，不引外部资源 |
| 5 | 界面语言 | 简体中文 |
| 6 | 是否需要单元测试覆盖 `WorkspacePath` | S1 只测"越界路径被拒绝"这一个用例，其余留 S3 |

---

## 11. 完成判据

`AGENTS.md` §9 的八条。这里再强调两条最常被绕过的：

- **第 1 条（干净克隆一条命令能起）** 是唯一硬门槛。只能在你机器上跑的骨架不算完成。
- **第 6 条（0 模块启动）** 是模块化的最小证明。如果这条靠"临时注释掉一段代码"才过，说明框架偷偷依赖了具体模块，必须改掉依赖而不是改验收。

---

## 交接记录

完成后按 `tasks/_TEMPLATE.md` 末尾六段格式填写，由编排者落盘为 `tasks/S1-<角色>.handoff.md`。
