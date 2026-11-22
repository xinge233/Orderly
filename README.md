# 归序 Orderly 模块分发分支

本分支不是应用源码开发分支，而是归序 Orderly 的模块分发目录。

应用中的“设置 → 模组设置 → 模组下载”会自动读取本分支的 `catalog.json`，展示当前可用的业务模块。用户选择模块并显式确认后，应用才会下载对应的源码包并在本机校验、构建和安装。

## 目录内容

```text
catalog.json
packages/
  module_<moduleId>-<version>-<sourceCommit>.zip
README.md
```

- `catalog.json`：模块目录，记录模块标识、标题、说明、版本、源码包路径、大小和 SHA-256。
- `packages/`：按模块拆分并排除了构建产物的源码 ZIP。每个包只包含一个业务模块目录。
- `README.md`：本分支的用途和维护说明。

应用固定读取以下目录地址：

```text
https://raw.githubusercontent.com/xinge233/Orderly/module-distribution/catalog.json
```

`catalog.json` 中的 `packagePath` 是相对本分支根目录的路径。应用会从对应 `raw.githubusercontent.com` 地址下载源码包，不接受任意远端目录。

## 下载与安装

1. 在“设置 → 模组设置 → 模组下载”中查看本分支的模块目录。
2. 由本机用户选择模块并发起安装；AI 不能触发模块下载或安装。
3. 宿主校验来源、模块标识、包大小、SHA-256、ZIP 路径和模块契约。
4. 校验通过后，源码写入应用的 `Module/` 目录，由本地构建流程生成模块 JAR 并追加到当前宿主。

第一版只支持追加全新业务模块。已有模块的更新、替换和卸载仍要求重启，不通过本分支静默覆盖当前运行实例。

## 发布方式

本分支的 `catalog.json` 和 `packages/` 由 `framework-v3` 分支的发布流程生成，不应手工修改：

1. 在 `framework-v3` 的干净提交上通过完整门禁。
2. 运行 `scripts/export-module-distribution.ps1`，生成业务模块源码 ZIP 和 `catalog.json`。
3. 手动触发 GitHub Actions 工作流 `Publish module distribution`。
4. 工作流验证源码、重新生成目录并提交、推送到本分支。

发布脚本只包含普通业务模块，排除全部 `module_system_*` 受保护系统模块、`target/`、Git 元数据和运行时数据。`catalog.json` 的 `sourceCommit` 指向生成这些包的源提交，应用会据此展示分发版本来源。

## 安全边界

本分支只提供受控下载目录和源码包，不执行代码，也不保存 API Key、用户文件或运行数据。

模块源码包通过契约、路径、大小和 SHA-256 校验后，会在本机构建为可执行模块 JAR。模块代码属于本地受信任可执行代码，不是安全沙箱；当前也没有发布者签名或证书链。请只从官方 `xinge233/Orderly` 仓库的 `module-distribution` 分支安装自己信任的模块。
