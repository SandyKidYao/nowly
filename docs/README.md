# 开发文档

这些文档描述当前已实现的行为。修改功能时同步更新对应约定；依赖版本以 `gradle/libs.versions.toml` 为准，构建参数以 Gradle 文件为准。

| 文档 | 何时查阅 |
| --- | --- |
| [产品规则](product.md) | 修改记录、提醒、统计和导出行为前 |
| [开发指南](development.md) | 搭建环境、了解代码结构和运行验证 |
| [UI 与品牌](nowly-ui-brand.md) / [设计变量](nowly-design-tokens.json) | 修改界面、图表或品牌素材时 |
| [后台提醒排查](background-reminders.md) | 排查厂商后台限制、权限与提醒延迟时 |
| [版本决策记录](changelog.md) | 了解行为改变的原因；旧条目描述当时版本 |
| [发布指南](releasing.md) | 发布 APK、维护签名和 GitHub Pages 时 |

过时的初始化需求、选型比较与首版 HTML 原型已移除；其中仍有效的约束合并进产品规则、开发指南和品牌规范。当前实现取代旧方案，Git 历史保留完整决策过程。
