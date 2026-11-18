# PVZ 图片自动点击 APK（Android 原生）

## 功能
- 从相册导入多张图片模板
- 每条规则可独立启用/停用、设置匹配阈值、删除
- MediaProjection 截图 + OpenCV `matchTemplate`
- AccessibilityService 执行点击
- 前台服务持续运行，单条规则默认 1.5 秒点击冷却

## 环境
- Android Studio Ladybug 或更新版本
- JDK 17
- Android SDK 35
- Gradle 由 Android Studio 自动下载
- 真机 Android 8.0（API 26）及以上

## 导入与编译
1. 解压项目，在 Android Studio 选择 Open。
2. 等待 Gradle Sync 完成。
3. 手机开启开发者选项和 USB 调试（仅用于安装调试，不是运行必需）。
4. Build > Build Bundle(s) / APK(s) > Build APK(s)。
5. 将 `app/build/outputs/apk/debug/app-debug.apk` 安装到手机。

## 使用
1. 打开 App，点击“开启无障碍权限”，在系统设置中开启“PVZ 图片自动点击”。
2. 返回 App，点击“添加识别图片”，从相册选择事先裁剪好的按钮模板。
3. 勾选需要启用的规则，调整匹配阈值（建议先从 0.88 开始）。
4. 点击“开始识别”，在系统弹窗中允许录屏。
5. 切换到植物大战僵尸 2。停止时返回 App 点击“停止识别”。

## 图片模板制作建议
- 使用与游戏当前画面相同方向、相同分辨率比例的截图。
- 模板尽量紧贴按钮，包含有辨识度的边缘/文字/图标，不要包含大面积动态背景。
- 不要把“绿色按钮”和“灰色按钮”做成同一个模板；分别添加绿色、灰色模板，并只启用需要点击的规则。
- 匹配阈值越高越严格；误点时提高阈值，漏识别时适当降低。

## 当前版本边界与重要说明
这是可编译的 MVP 源码骨架，提供图片导入、多规则管理、屏幕匹配与点击链路。尚未实现悬浮窗、规则优先级/互斥、区域限定、横竖屏动态重建、点击次数限制、暂停手势等高级功能。
- Android 14/15 对 MediaProjection 前台服务有严格限制；每次开始都需要系统授权。
- 部分厂商系统可能限制后台截图/无障碍手势，需要允许后台运行。
- 当前匹配使用灰度模板匹配，对缩放、动画、透明遮罩和游戏分辨率变化不具备鲁棒性。
- 请先在非关键界面测试，避免误点击造成游戏内操作损失。
- 该工具仅用于用户本机屏幕自动化；遵守游戏服务条款。


## GitHub Actions 云端编译（无需本地配置 Android SDK）
1. 在 GitHub 新建一个空仓库，例如 `PVZAutoClicker`。
2. 解压本项目，将 `PVZAutoClicker` 文件夹内的全部内容（包括隐藏的 `.github` 文件夹）上传到仓库根目录。
3. 推送到 `main` 或 `master` 分支后，会自动触发 Actions；也可以在仓库 Actions 页面手动运行 `Build PVZ AutoClicker APK`。
4. 打开成功的 workflow run，在 Artifacts 区域下载 `PVZAutoClicker-debug-apk`，解压得到 `app-debug.apk`。
5. 将 APK 传到安卓手机安装。首次运行仍需手动开启无障碍服务，并在开始识别时允许屏幕录制。

注意：GitHub 云端编译需要你自己的 GitHub 账号和仓库；本项目不会自动上传到你的账号。当前产物为 debug APK，未签名为可发布的 release 包。
