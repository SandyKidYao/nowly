# 开发指南

## 环境与构建

Android Studio 打开根目录，使用 JDK 21、Android SDK 36 和 Build Tools 36.0.0。最低 Android 8.0（API 26），字节码目标 Java 17。`local.properties` 只存本机 SDK 路径，不提交。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

安装包为 `app/build/outputs/apk/debug/app-debug.apk`。Debug 包名 `com.nowly.app.debug`，Release 为 `com.nowly.app`；二者独立存储，数据不互通。更新同一包名须保持签名，避免卸载造成数据丢失。

```sh
# 连接专用模拟器后运行；不要用装有个人记录的设备
./gradlew :app:connectedDebugAndroidTest
```

设备测试使用独立测试数据库。真实 AlarmManager 测试需要测试包的精确闹钟权限，未授权会跳过。模拟器验证不能替代厂商真机的锁屏、休眠、重启与覆盖升级验证。

## 结构与约束

Kotlin 路径前缀：`app/src/main/kotlin/com/nowly/app/`。

| 目录 | 职责 |
| --- | --- |
| `ui/` | Navigation 3 导航、共享页面状态、组件和 Compose 主题 |
| `projects/` | 首页、编辑草稿和校验 |
| `recording/` | 回答页面与通知专用 Activity |
| `statistics/` | 平均、趋势、马赛克、CSV 与提示词 |
| `data/` | Room 实体、DAO、仓库、事务与迁移 |
| `platform/reminders/` | 统一提醒状态入口、AlarmManager、通知与 Receiver |
| `app/schemas/` | 数据库 schema，随变更提交并增加迁移测试 |
| `site/` | 无构建依赖的 GitHub Pages 产品页 |

一个 app 模块，手动 AppContainer 组装依赖。界面通过 ViewModel/Flow 读取数据，ViewModel 和 Receiver 共用仓库及提醒引擎，避免复制状态规则。系统提醒使用 AlarmManager，不依赖页面或进程持续运行。图表用 Compose/Canvas。

修改状态流时优先验证重复事件、回答/跳过/替换竞争、旧通知回调与提交后返回。修改日期或统计时验证跨日、时区、空区间、停用选项和比较区间。修改数据库时提交 schema 与保留数据的迁移测试。

依赖集中固定在 `gradle/libs.versions.toml`。Lint 将警告视为错误，关闭在线依赖新版本提示及 `OldTargetApi`（目标 SDK 固定为 36，不因 runner 安装了更高 SDK 就改变结果）。升级目标 SDK 需单独验证行为变化，代码和资源检查保持开启。Reorderable 的 Apache-2.0 License/NOTICE 随包保留在 `app/src/main/assets/licenses/`。

## 持续集成

推送和 PR 运行 `.github/workflows/android.yml`，验证 Debug 构建、单元测试、Lint、设备测试编译和站点文件。正式发布流程见[发布指南](releasing.md)。

文档描述当前契约；已被替代的版本决策仅供追溯。截图与测试数据使用合成数据；设备数据库、日志、密钥与个人路径不进入公开仓库。
