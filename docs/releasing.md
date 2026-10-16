# 发布指南

## 发布一个版本

1. 修改 `app/build.gradle.kts`：递增 `versionCode`（当前为 7），同步默认 `versionName`。每次正式更新必须增加 versionCode，保持正式包名 `com.nowly.app` 和签名不变。
2. 更新产品规则、版本决策与需要变化的产品页。推送后等待 Android CI 通过。
3. 在 GitHub 创建并发布 Release，选择已经验证的提交，标签如 `v0.2.5`。CI 会用去掉 `v` 的标签作为 APK 的版本名称。标签仅用 `vX.Y.Z` 或 `vX.Y.Z-suffix` 格式。
4. `Release` workflow 运行 单元测试、Lint、R8 构建与签名验证，将 `nowly.apk` 和 `nowly.apk.sha256` 上传到该 Release。正式版随后把该标签下的 `site/` 部署到 GitHub Pages；预发布仅上传 APK，不替换正式页面。

```sh
gh release create v0.2.6 --target main --title '此刻 0.2.6' --notes-file /path/to/release-notes.md
```

只有发布 Release 才触发这条流程，普通推送和 PR 只运行验证。失败后可以在 Actions 重跑；也可手动运行 `Release` 并传入已有已发布标签。上传会替换该版本同名资产，勿移动已经发布的标签。重跑旧版也会部署旧版页面，请只对期望上线的版本执行。

若 APK 成功而 Pages 失败，APK 已可下载，修复页面设置后重跑失败的 job 即可。

## 签名与仓库设置

仓库 Actions secrets：

| Secret | 内容 |
| --- | --- |
| `NOWLY_KEYSTORE_BASE64` | 固定 JKS 签名库的 base64 |
| `NOWLY_KEYSTORE_PASSWORD` | 签名库密码 |
| `NOWLY_KEY_ALIAS` | 密钥别名 |
| `NOWLY_KEY_PASSWORD` | 私钥密码 |

首次配置时已生成专用发布密钥并通过本地 `gh secret set` 写入仓库；本机备份在被 Git 忽略的 `.local/release-signing/`。请将整个目录另行安全备份；GitHub secrets 不能读回，丢失私钥无法给已安装的正式版做同签名升级。不要提交密钥、密码或 `.env` 文件。

Pages 的构建来源须为 **GitHub Actions**。`github-pages` environment 应允许发布标签部署（不限于默认分支）；workflow 使用 `pages: write` 和 `id-token: write` 完成部署。产品页地址为 <https://sandykidyao.github.io/nowly/>。

## 本地验证

```sh
python3 scripts/check-site.py
node --check site/app.js
python3 -m http.server 8765 --directory site --bind 127.0.0.1
```

浏览器打开 <http://127.0.0.1:8765>，检查截图切换、FAQ、窄屏和下载入口。发布 CI 会生成 `site/release.json`，使页面下载链接指向本次发布的确切版本；本地无该文件时回退到最新 Release。

本地正式构建设置四个环境变量：`NOWLY_KEYSTORE_PATH`（绝对路径）、`NOWLY_KEYSTORE_PASSWORD`、`NOWLY_KEY_ALIAS`、`NOWLY_KEY_PASSWORD`，再运行 `./gradlew :app:assembleRelease`。未提供签名时只生成未签名构建，不回退到调试密钥。正式版与已有 Debug 版独立安装，当前没有导入迁移功能。
