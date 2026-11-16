<p align="center"><img src="../assets/brand/logo-pixel.png" width="96" alt="红果 TV 标识" /></p>

# 红果 TV 原生版

Kotlin + Android 原生 View + Media3，单 APK，默认 Android 6.0 / API 23；Android 5 使用兼容包。包名 `com.hongguotv.nativeapp`；界面功能见 [项目说明](../README.md)，合并策略与验证见 [集成记录](MAIN_INTEGRATION.md)。

## 使用

手机竖屏使用，播放页切横屏。底部栏目：首页（短剧／漫剧、接着看、热门）、找剧（分类筛选）、排行、我的（本机收藏与观看历史，长按删除）；顶部搜索框用系统输入法。不提供账号登录，收藏与进度只保存在本机。

播放页轻点显示或隐藏控制层，双击左右快退快进 10 秒，双击中间暂停或继续；控制层提供进度拖动、选集、倍速、清晰度、弹幕、评论、收藏和更多播放设置。「我的」页右上角检查更新，更新来自 `N3urda/hongguoTV`，由系统确认安装。

## 构建

需要 JDK 17、Android SDK 36 和 Build Tools 36.0.0。配置 `ANDROID_HOME` 或本机 `local.properties` 的 `sdk.dir`。

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

签名包使用 `scripts/build-kotlin.sh`。签名配置来自未跟踪的 `kotlin-tv/keystore.properties`（storeFile/storePassword/keyAlias/keyPassword），或 HONGGUOTV_KEYSTORE、HONGGUOTV_STORE_PASSWORD、HONGGUOTV_KEY_ALIAS、HONGGUOTV_KEY_PASSWORD 环境变量。不要提交签名材料。正式包必须沿用原证书；debug 包不能覆盖原签名包。

兼容包继续保留 API 21 目标，因此 Media3 使用 1.8.0，新版预加载 API 已适配。Android 5/6/7 实体电视的解码、安装及登录加密存储还需实机验收；不能把 JVM 测试等同于硬件验证。

## 发布

[发布流程](AUTO_UPDATES.md)沿用主线单仓库 Release。源代码推送或 PR 本身不会发布 APK；正式标签才触发发布。可先在本地完成构建、签名与发布材料校验，用户合并后再发布。

[第三方声明](NOTICE.md) · [GPL-3.0](LICENSE) · [原功能分支验证](UPSTREAM_SYNC.md)

默认构建不带 FFmpeg；添加 `-PlegacyAudio=true` 构建 Android 5 兼容版。四种正式 APK（Android 5 通用、Android 6+ ARM32、ARM64 和通用） 与更新迁移见 [发布说明](AUTO_UPDATES.md)。
