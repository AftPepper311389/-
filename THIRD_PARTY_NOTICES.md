# 第三方组件声明（THIRD PARTY NOTICES）

本项目中使用了以下第三方组件，按其许可证要求在此保留版权与许可声明。

## ShadowHook

- 组件：[bytedance/android-inline-hook (ShadowHook)](https://github.com/bytedance/android-inline-hook)，版本 1.0.9（`com.bytedance.android:shadowhook`）
- 用途：原生 inline hook 库，用于钩住 libEGL 的 `eglSwapBuffers`，在每帧上屏前完成模糊与玻璃合成（随 APK 分发 `libshadowhook.so`）
- 许可证：MIT

```
MIT License

Copyright (c) 2020-present, ByteDance Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 终末地灵动岛样式（EndfieldTimeline / EndfieldIcons）

- 组件：移植自 [GlacierGlimmer/zmd-charge-plus-for-android](https://github.com/GlacierGlimmer/zmd-charge-plus-for-android)
  （该上游项目自身衍生自 [QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge)）
- 涉及文件：`app/src/main/java/com/nemoclient/app/ui/endfield/EndfieldIcons.kt`、
  `app/src/main/java/com/nemoclient/app/ui/endfield/EndfieldTimeline.kt`
  （仅重命名包、移除动画驱动 / AppConfig 依赖，宿主视图用自己的时钟采样时间线）
- 许可证：MIT

```
MIT License

Copyright (c) 2026 GlacierGlimmer_冰川雪貓

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## AndroidX 与 Kotlin 运行时

- 组件：`androidx.recyclerview:recyclerview:1.3.2`、Kotlin 标准库及其随 APK 分发的运行时组件
- 许可证：Apache License, Version 2.0（全文见 <https://www.apache.org/licenses/LICENSE-2.0>）

```
Copyright 2018 The Android Open Source Project
Copyright 2010-2024 JetBrains s.r.o.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 运行时依赖（不随本项目分发）

- **Xposed 旧版 API 82**（`de.robv.android.xposed:api:82`）仅编译期可见，运行时由 LSPosed 等框架动态提供，不包含在本项目的 APK 内；致谢 rovo89 的 Xposed 与 LSPosed 项目。
- 音乐播放器调用的 Jamendo / Internet Archive / LRCLIB / 自定义 API 等为在线服务，不属于代码分发；其内容可用性与合规性由使用者自行负责。
- 彩蛋视频 `bangyou.mp4` 版权归原作者所有，见 README「致谢与声明」。
