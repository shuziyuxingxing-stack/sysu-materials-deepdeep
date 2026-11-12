# sysu-materials-deepdeep
这里是deepdeepAPP的插件代码仓库。本插以接口调用方式从SYSU freshman materials、arxiv.jaison.ink两大数据源获得习题、真题等相关数据，用户以自然语言向deepdeep提问即可获得真题链接。

## 安装与数据来源

- 版本 1.0.2 使用插件 API 5，要求 DeepDeep 1.0.1 或更高版本。运行权限为普通档。通过 DeepDeep 的「插件 → 导入插件」可以选择已签名的 `.ddp`。安装包可从 [GitHub Releases](https://github.com/shuziyuxingxing-stack/sysu-materials-deepdeep/releases) 下载。
- [SYSU freshman materials](https://github.com/thinktraveller/SYSU_freshman_materials) 提供文件目录和源文件链接。目录在设备上缓存，文件由用户从 GitHub 下载。
- [中大资料社区](https://arxiv.jaison.ink/) 提供资料及课程包搜索。社区下载在原站登录后完成。
- 查询时仅向所选资料源发请求。插件在自己的数据目录缓存索引、检索结果和用户主动下载的文件。发布包仅包含程序、清单和图标。

## 从源码构建

使用 JDK 17、Android SDK 36 和作者的公开 Maven SDK 0.8.1。Gradle wrapper 随源码提供。把 Android SDK 路径设在 `ANDROID_HOME`，或用本地 `local.properties` 指定 `sdk.dir`。

```sh
./gradlew assemblePlugin
./gradlew -PpluginSigningKey=/path/to/key-prefix verifyPlugin
```

Windows 使用 `gradlew.bat`。`verifyPlugin` 需要发布者私钥 `.pem` 和公钥 `.pub`，配置值为不带扩展名的路径。首次自行构建可用 `./gradlew generateSigningKey` 生成个人密钥。市场更新必须沿用原发布者密钥。

构建与签名任务基于 [DeepDeep 外部插件模板](https://github.com/Sadami3066/DeepDeep-Plugins/tree/master/template)，本项目使用 Apache-2.0，见 `LICENSE`。两处资料的权利归各自权利人。
