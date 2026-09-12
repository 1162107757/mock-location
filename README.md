# Drift Location

Drift Location 是一个面向 Android 开发和测试场景的固定位置工具，提供普通 Mock Location 与 Root 增强两种模式。用户可以从地图选择坐标，并持续保持在该位置。

## 当前功能

- OpenStreetMap 地图拖动选点、双指缩放和按钮缩放
- 一键定位到设备真实当前位置，并自动放大到街区级别
- 地点搜索，以及离线手动输入经纬度
- Android 官方 Mock Location API，无需 Root
- 前台服务持续模拟 GPS、网络与 fused 融合位置
- Root 模式自动授予 Mock Location AppOps，无需手动选择模拟位置应用
- 固定向 GPS、网络与 fused 通道提交同一个坐标，速度保持为 0
- 自动保存最近 30 个成功模拟的位置，可再次选择或删除
- Root 模式隐藏 `Location.isMock()` 与 `isFromMockProvider()` 标记
- 坐标和运行状态本地保存
- 深色单手操作界面与基础无障碍标签

## Root 固定位置模式

1. 设备需要已经 Root。
2. 安装 Drift Location APK。
3. 打开 Drift Location，点击左下角“Root 模式”，授予 Root 权限。
4. 选择目标坐标并点击“开始模拟”。

如果虚拟机安装了 LSPosed，可以额外启用 Drift Location 模块，并把系统框架与测试应用加入作用域，增强对自定义定位 SDK 的兼容性。

Root 模式不需要在开发者选项中选择模拟位置应用。点击开始后，应用通过 `su` 为自身授予 Mock Location AppOps，再由前台服务持续向 GPS、网络与 fused 通道提交固定坐标。LSPosed 钩子仅作为目标应用的额外兼容路径。

## 普通模式

1. 安装应用并打开。
2. 点击“系统设置”。
3. 在 Android 开发者选项的“选择模拟位置信息应用”中选择 **Drift Location**。
4. 返回应用，拖动地图或搜索地点。
5. 点击“开始模拟”。

如果设备还没有显示开发者选项，请先进入“关于手机”，连续点击版本号以启用。

## 构建

项目需要 JDK 17+、Android SDK 36 和 Gradle 8.7+，APK 最低支持 Android 7.1.2（API 25）：

```powershell
.\gradlew.bat assembleDebug
```

如果 Windows 的 `JAVA_HOME` 仍指向旧版 JDK，可在当前机器上使用：

```powershell
.\gradlew.bat '-Dorg.gradle.java.home=C:\Program Files\Java\latest\jdk-21' assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

Android 7.1.2 使用兼容分支启动普通前台服务、构建旧版通知、注册广播和提交测试定位；Android 8.0 及以上使用通知渠道与新版前台服务 API。

Root 模式点击“申请 Root”后会执行 `su -c id`，只有确认返回 `uid=0` 才会启用 Root 固定位置模式。首次点击“开始模拟”时，应用会通过 AppOps 自动配置模拟位置权限。

## 技术说明

普通模式通过 `LocationManager.addTestProvider()` 和 `setTestProviderLocation()` 提交模拟坐标，并在设备支持时覆盖 GPS、网络与 fused 融合定位通道。Android 会将这些坐标标记为 mock。

Root 模式先通过 Root 配置系统模拟位置权限，再由前台服务创建 Android Test Provider 并持续提交同一个 `Location`。位置速度固定为 0；LSPosed 模块仍可在 Android 7.1.2 的系统分发层和目标进程中替换完整位置对象。当前版本不会修改 IP、Wi-Fi、蜂窝基站或原始 GNSS 数据。

地图瓦片来自 OpenStreetMap 德国社区服务器，地点搜索使用 Photon。两者运行时均需要网络，地图界面会显示 OpenStreetMap 署名。
