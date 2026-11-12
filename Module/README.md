# 归序 Orderly 模块添加指南

本文说明如何向归序添加一个自包含业务模块。目标是：新增或移除某个
`Module/module_*` 源码目录后，只需要重新构建并启动程序，不需要修改框架中的
固定模块列表、业务端点、侧边栏或总览卡片。

## 1. 自动发现概要

模块从源码到运行的路径如下：

1. [`scripts/build.ps1`](../scripts/build.ps1) 扫描 `Module/module_*` 目录。
2. 每个模块目录独立执行 Maven 构建。
3. 构建脚本校验模块 JAR 包含 `orderly-ui/module.json`、`orderly-ui/module.js`
   和 `orderly-ui/module.css`。
4. 构建脚本把模块 JAR 放入 `target/orderly-modules/`，并在构建前清空该目录，
   防止已经移除的模块继续从旧 JAR 加载。
5. 宿主启动时扫描 `orderly.modules-dir` 指向的目录，通过 `ServiceLoader` 加载
   `OrderlyModule` 执行器，并读取 JAR 中的契约和 UI 资源。
6. 前端从 `/api/modules` 获取模块目录，并按模块 ID 加载对应 UI。

因此，新增合规模块**不需要**修改根 `pom.xml`、宿主 Java 代码或前端模块列表。

当前构建流程如果仍然生成 `Module/modules.index`，该文件只是构建产物，不是模块
注册入口，运行时也不读取它。不要通过手工编辑该文件注册模块。即使该文件以后被
移除，上述目录扫描和 JAR 发现机制仍然有效。

## 2. 必要结构

每个模块使用一个独立目录，推荐命名为 `module_<name>`：

```text
Module/
└─ module_example/
   ├─ pom.xml
   └─ src/main/
      ├─ java/
      │  └─ com/orderly/module/example/
      │     └─ ExampleModule.java
      └─ resources/
         ├─ META-INF/services/com.orderly.kernel.OrderlyModule
         ├─ orderly-modules/example.json
         └─ orderly-ui/
            ├─ module.json
            ├─ module.js
            └─ module.css
```

以下内容是必需的：

- `pom.xml`：模块自己的 Maven 构建文件。
- `OrderlyModule` 实现类：模块执行器。
- `META-INF/services/com.orderly.kernel.OrderlyModule`：SPI 注册文件。
- `orderly-modules/*.json`：模块工具、查询和计划规则契约。
- `orderly-ui/module.json`：侧边栏、总览卡片和前端视图元数据。
- `orderly-ui/module.js`：模块自有前端逻辑。
- `orderly-ui/module.css`：模块自有样式。

一个 `module_*` 目录应只对应一个业务模块，并只产出一个可运行 JAR。

## 3. 命名约束

- 模块源码目录必须位于 `Module/` 下，名称以 `module_` 开头。
- 推荐目录名、JAR 名和契约中的 `moduleId` 采用一致的命名，例如
  `module_example`。
- `moduleId` 必须匹配 `^[a-z][a-z0-9_-]{0,63}$`。
- `OrderlyModule.moduleId()` 的返回值必须与契约中的 `moduleId` 完全一致。
- 视图 ID 必须匹配 `^[a-z][a-z0-9-]{0,63}$`，推荐使用简短名称，如 `example`。
- 视图 ID 不能使用框架保留的 `overview` 或 `history`，且必须在所有模块间唯一。
- 模块 ID、动作 ID、查询 ID 和计划规则 ID 在整个运行目录内必须全局唯一。
- 动作、查询和计划规则 ID 应带模块命名空间，例如 `example.read`、
  `example.list` 和 `example.rule.read`。

## 4. 创建模块工程

可先复制现有模块作为起点：

- [`module_todo`](module_todo)：包含可撤销写操作、只读查询和计划规则的完整示例。
- [`module_fs`](module_fs)：包含文件列表、回收站查询和多个动作的示例。
- [`module_calendar`](module_calendar)：包含日程查询和时间修改动作的示例。

模块的 `pom.xml` 应继承仓库父 POM，并依赖 `orderly-kernel`。例如：

```xml
<parent>
    <groupId>com.orderly</groupId>
    <artifactId>orderly-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
</parent>

<artifactId>module-example</artifactId>

<dependencies>
    <dependency>
        <groupId>com.orderly</groupId>
        <artifactId>orderly-kernel</artifactId>
        <version>${project.version}</version>
    </dependency>
</dependencies>
```

模块不得自行判定授权、执行未经确认的写操作或绕过宿主访问文件系统。授权、确认、
执行顺序、失败处理和审计由框架统一担保。

## 5. 实现模块执行器

执行器必须实现
[`OrderlyModule`](../app/kernel/src/main/java/com/orderly/kernel/OrderlyModule.java)：

- `moduleId()`：返回稳定且唯一的模块 ID。
- `initialize(ModuleRuntime)`：可选；用于幂等初始化模块自有状态。
- `query(String, Map, ModuleRuntime)`：实现契约中声明的只读查询。
- `prepareSnapshot(...)`：正向操作前收集 JSON 可序列化快照，不得产生副作用。
- `execute(...)`：执行契约声明的正向动作。
- `rollback(...)`：根据快照和正向输出执行可逆回滚。

模块只能通过传入的 `ModuleRuntime` 访问宿主提供的能力和模块数据。不要直接打开
项目数据库、保存文件路径或建立绕过宿主的 IO 通道。

在模块 JAR 中提供以下 SPI 文件：

```text
src/main/resources/META-INF/services/com.orderly.kernel.OrderlyModule
```

文件内容为实现类的完整限定名，例如：

```text
com.orderly.module.example.ExampleModule
```

文件必须位于 JAR 内相同路径，否则宿主无法发现模块执行器。

## 6. 声明模块契约

在以下位置创建模块契约：

```text
src/main/resources/orderly-modules/example.json
```

契约至少包含：

- `moduleId`
- `title`
- `description`
- `order`
- `actions`，必须是数组
- `queries`，必须是数组
- `plannerRules`，必须是数组

只有查询、没有动作的模块可以令 `actions` 和 `plannerRules` 为空数组。可参考
[`contracts/samples/valid/module.json`](../contracts/samples/valid/module.json)。

契约注意事项：

- 写操作和删除操作必须设置 `confirmationRequired: true`。
- 删除操作还必须声明非空 `confirmationPhrase`。
- 参数类型目前支持 `string`、`boolean` 和 `integer`。
- 计划规则的 `actionId` 必须指向同一模块中的动作。
- `argumentGroups` 只能映射该动作已经声明的参数，捕获组编号必须有效。
- `pathArguments` 中的参数必须声明为字符串参数，并出现在 `argumentGroups` 中。

契约是动作、参数、风险等级、确认要求和计划规则的唯一真相源。不要在 Java 代码或
前端代码中维护第二套冲突定义。

## 7. 添加模块 UI

模块必须在自己的 JAR 中提供以下固定资源：

```text
orderly-ui/module.json
orderly-ui/module.js
orderly-ui/module.css
```

`module.json` 至少需要合法的 `version` 和 `view.id`。完整示例参见
[`contracts/samples/valid/module-ui.json`](../contracts/samples/valid/module-ui.json)：

```json
{
  "version": 1,
  "view": {
    "id": "example",
    "caption": "示例模块",
    "icon": "modules"
  },
  "card": {
    "title": "示例模块",
    "description": "模块自有摘要。",
    "badge": "模块数据",
    "icon": "modules",
    "queryId": "example.list",
    "countKey": "count",
    "unit": "项"
  },
  "examples": [
    { "label": "读取示例", "text": "读取示例" }
  ],
  "script": "module.js",
  "style": "module.css"
}
```

UI 约束：

- `script` 和 `style` 只能是安全的单级资源名；当前模块应使用固定的
  `module.js` 和 `module.css`。
- `card.queryId` 应指向模块声明的无必填参数查询，或返回可用的计数结果。
- 前端只从模块 JAR 读取 UI，不会从宿主 fat JAR 或框架目录加载模块业务界面。
- UI 加载失败或清单无效只应影响该模块的局部显示，不能导致其他模块不可用。
- 模块样式必须使用主题语义变量，不得硬编码颜色值。

`module.js` 使用默认导出挂载视图：

```js
export default async function mount(root, context) {
    // root：模块视图容器
    // context.module / context.ui：模块契约和 UI 清单
    // context.query(...)：调用模块声明的通用查询
    // context.element / context.icon：创建主题一致的界面元素和图标
    // context.toast / context.fillCommand / context.formatDate：宿主交互辅助能力
}
```

不要在 `app/frontend/src/app.js` 中添加该模块的固定视图映射或业务加载函数。

## 8. 构建与启动

新增源码目录后，运行仓库根目录的：

```powershell
.\启动归序.cmd
```

该入口会重新构建模块、清空并重建 `target/orderly-modules/`，然后启动宿主。不能在
应用运行期间直接热加载新模块；新增、更新或移除模块后都需要重新构建并启动。

如只需要使用已经构建好的模块 JAR，可执行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -SkipBuild
```

注意：正常启动入口会重建运行模块目录，因此不要把手工放入
`target/orderly-modules/` 的 JAR 当作长期模块来源。长期模块应保留完整的
`Module/module_*` 源码目录。

## 9. 验证与排查

新增模块后至少检查：

- `gate.ps1` 通过，前端和模块 UI 语法有效。
- 模块 JAR 包含 SPI 文件、契约和三个 UI 资源。
- 宿主 fat JAR 不包含该模块的 JAR、契约或 UI。
- `/api/modules` 返回新模块。
- 新模块出现在侧边栏和总览，卡片排序、拖动和键盘移动正常。
- `/api/modules/{moduleId}/ui/module.json` 及 JS/CSS 可访问。
- 契约中声明的只读查询能够通过通用查询接口执行。
- 写操作先进入计划确认，删除操作只进入项目回收站。
- 移除模块源码并重新启动后，旧侧边栏、卡片、API 和 UI 不再出现。
- 删除全部模块目录后，程序仍可启动，侧边栏和总览显示空状态。

建议执行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\gate.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\modularity.ps1
```

启动测试实例后，再在另一个终端执行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18082
```

常见失败原因：

- 目录不以 `module_` 开头：构建不会扫描它。
- `pom.xml` 缺失或构建出多个 JAR：构建失败。
- SPI 文件路径或类名错误：宿主找不到执行器。
- `moduleId()` 与契约 `moduleId` 不一致：宿主拒绝契约与执行器集合不一致。
- 两个模块声明相同 ID：宿主拒绝重复模块、动作、查询或计划规则。
- UI 三件资源缺失：构建失败。
- `orderly-ui/module.json` 的资源名或视图 ID 非法：前端拒绝该模块 UI。
- 模块 CSS 使用硬编码颜色：门禁失败。

## 10. 移除模块与零模块

移除模块时，从 `Module/` 中移走对应的 `module_*` 源码目录，然后重新构建并启动。
不需要修改框架代码。构建会清空 `target/orderly-modules/`，不会继续加载旧 JAR。

删除最后一个模块也是合法状态：宿主应正常启动，`/api/modules` 返回空目录，侧边栏
和总览显示空状态，其他框架功能不得报错。

## 11. 安全与责任边界

- 模块 JAR 是本地受信任代码，不是安全沙箱。
- 当前不支持远程分发、来源验证、签名校验、热加载或热卸载。
- 模块负责自己的业务能力、契约、逆操作、界面和动效声明。
- 框架负责注册、授权、确认、调度、快照、回滚、事件记录和统一外观。
- 新增能力应先更新契约、样例和校验，再实现执行器和界面。
