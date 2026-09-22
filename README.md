# 此刻 · Nowly

Android 本地状态采样 App。当前版本为 **0.2.4-mvp**，采用 Kotlin、Jetpack Compose、Navigation 3、Room 和 AlarmManager。

## MVP 功能

- 首次直接进入首页，预置「心情」项目；可创建多个自定义单问题项目。
- 一张表单编辑名称、问题、有序单选选项、每天的提醒时间和提醒开关；选项支持左侧手柄拖动排序，所有修改随“保存”统一提交。时间轴可拖动，也能用系统时间选择器精确到分钟。
- 首页卡片只有两个入口：点击项目名进入设置，右上角统计图标进入「统计」。提醒关闭的项目在名称下显示「已暂停」。表单底部的红色按钮用于删除项目，保留二次确认。
- 点击通知进入回答页，选择即保存并返回来源。通知支持跳过，清除回调记为已跳过；同项目新提醒替换旧提醒，旧记录记为未处理。
- 待处理没有自动过期。暂停只停止后续提醒，保留当前待处理记录。
- 「统计」一页展示：已回答 / 已跳过 / 未处理计数、日期趋势 / 时段分布 / 按选项统计图表，以及只含已填写记录的列表，按日期分组，每行显示填写时间和选项。统计和记录共用一个日期范围，点击日期范围调整筛选。
- 选项使用稳定 ID；改名更新历史显示，移除只停用，不破坏历史引用。答案按实际填写时间归组。
- 使用已确认的「苔灰」浅色视觉。没有账号、云同步、网络权限或导出功能。

原需求的未定细节及本次实现选择见 [MVP 实现约定](docs/mvp-implementation.md)。

每个版本确认的界面与行为决策见 [版本决策记录](docs/changelog.md)。

## 打开与安装

Android Studio 打开本目录，等待 Gradle Sync 完成，选择 `app` 和 Android 8.0 以上的设备，点击 Run。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

开发安装包：`app/build/outputs/apk/debug/app-debug.apk`。包名为 `com.nowly.app.debug`，与初始化骨架使用相同调试签名，可覆盖升级并保留本机数据。不要为了更新而卸载旧版本。

首次打开时允许系统通知。未开启精确提醒权限也可以正常使用，但提醒可能延迟；编辑项目时可进入系统设置允许准时提醒。通知渠道被关闭时也需在系统通知设置恢复。

如果划掉应用后收不到提醒，还需检查手机的后台管理。vivo / OriginOS 真机已确认需要为「此刻」开启**自启动**，并在应用信息 → 电量 → 后台耗电管理中选择**允许后台耗电**。调整后重新打开应用，让系统重新登记提醒。设置位置和验证结果见 [后台提醒真机排查](docs/background-reminders.md)。

重启、时区/系统时间变化和返回应用时会重新安排未来提醒；强制停止后需要重新打开应用。Android 休眠配额与厂商后台限制仍可能影响送达，实际手机需要连续使用验证。

## 构建基线

| 配置 | 当前值 |
| --- | --- |
| namespace / 正式 applicationId | `com.nowly.app` |
| Debug applicationId | `com.nowly.app.debug` |
| minSdk / compileSdk / targetSdk | 26 / 36 / 36 |
| Gradle / AGP | 9.3.1 / 9.1.1 |
| 构建 JDK / 字节码目标 | 21 / 17 |
| Kotlin / Compose 编译插件 | 2.2.10，使用 AGP 内置 Kotlin |
| Compose BOM / Navigation 3 | 2026.02.01 / 1.0.1 |
| Room / KSP | 2.8.5 / 2.3.6 |
| 数据库 | v2；提供从初始化 v1 保留数据的迁移 |

版本集中在 [libs.versions.toml](gradle/libs.versions.toml)。`local.properties` 是本机 SDK 路径，不提交 Git。所有依赖采用固定版本；Lint 仅关闭在线“有新版本”的提示，保留代码和资源检查并将警告视为错误。

正式 release 构建不包含硬编码签名密钥；生产签名、正式包名及商店分发需另行配置。当前提供的 Debug APK 用于 MVP 验收与本人试用。

## 验证

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

设备测试使用专用模拟器和独立测试数据库，不清空 `nowly.db`。实际 AlarmManager 触发测试需要测试包的“闹钟和提醒”权限，未授权时该项会明确跳过。测试包含创建/编辑/删除、配置重建保留草稿、通知回答后返回、通知清除/跳过回调、真实定时触发、稳定选项、终态竞争、跨日统计、预置幂等及 v1 数据迁移。

0.2.4 已通过构建、Lint、11 个单元测试和 28 个隔离模拟器设备场景（其中 1 个为需精确闹钟权限的跳过项），并在 vivo 真机上覆盖升级验证。各版本的验收基线与仍需真机验证的边界见 [版本决策记录](docs/changelog.md)；执行日志在本机 `.local/`，不入库。不能将模拟器结果视为所有厂商手机的送达保证。

CI 配置位于 `.github/workflows/android.yml`，执行构建、单元测试、Lint 并编译设备测试 APK；当前没有创建远程仓库或触发远程 CI。

## 代码结构

| 位置 | 作用 |
| --- | --- |
| `app/…/ui/` | 应用导航、共享 ViewModel、页面组件、主题 |
| `app/…/projects/` | 首页、单页表单（含提醒开关）、输入校验 |
| `app/…/recording/` | 回答页面及通知专用 Activity |
| `app/…/statistics/` | 统计页：计数、图表、已填写记录列表和日期筛选 |
| `app/…/data/` | Room 实体、DAO、事务与仓库、迁移 |
| `app/…/platform/reminders/` | 计划计算、统一状态处理入口、AlarmManager 与通知适配、系统 Receiver |
| `app/schemas/` | 持续保留的数据库 schema |
| `app/src/test/`、`app/src/androidTest/` | 单元测试和 Android 集成测试 |

表中 Kotlin 路径前缀为 `app/src/main/kotlin/com/nowly/app/`。数据库升级不得使用清空旧库的破坏性迁移。没有导出/恢复的首版，在卸载、清除数据或丢失手机后可能无法恢复记录；备份配置也需在目标机型验证。

## 产品与设计资料

- [文档索引](docs/README.md)
- [产品需求](docs/nowly-initial-requirements.md)
- [定稿线框](docs/2026-09-17-wireframes-nowly.html)
- [苔灰视觉与品牌](docs/nowly-ui-brand.md)
- [苔灰 UI 预览](docs/2026-09-18-ui-nowly-moss.html)
- [技术选型](docs/nowly-technical-selection.md)
- [版本决策记录](docs/changelog.md)
