# DynamicDeploymentApk
DynamicDeploymentApk Android插件化框架；
实现真正意义上的插件化，无需修改任何插件apk代码，指定插件apk路径即可启动。

1. 在Host Application中初始化：
DynamicApkManager.init(this);//使用Application Context较好

2. 在Host APK任意位置调用如下代码，最好在非UI线程中
其中File路径可为.apk路径或者包含多个apk文件的目录；
File apkFile = new File("/data/local/tmp/dynamicapk");
DynamicApkManager.getInstance().install(apkFile);

3. install完成后
即可通过如下代码获取所有插件APK中的Launcher Activity
DynamicApkManager.getInstance().getMainActivities();
通过调用其他方法可以获取插件apk中的任意信息。

##DynamicApk/app/:
该目录下为宿主apk代码,在DynamicApplication中初始化框架，在MainActivity中安装框架并启动插件主Activity。

##DynamicApk/dynamic/:
该目录下为DynamicDeploymentApk插件化框架代码，完全实现从非安装apk中，非代理模式启动Activity，Service，BroadcastReceiver，ContentProvider
代码为最初版本，暂时仅实现功能，后续还要进行大量重构，优化；
由于测试机有限，且不同Android版本API存在部分差异，暂时只在Android 4.4, 5.0, 6.0上测试。
有兴趣的同学可以和我一起来交流，维护。

##Plugin-simple/:
该目录下为插件apk Demo,其中包括Activity，Service，BroadcastReceiver，ContentProvider等。

##docs/:
该目录下为部分Android源码时序图，想研究的同学可以先对照着时序图看一下源码，这样对深入了解框架代码有很好的帮助作用。
其中.mdj文件可用starUML打开编辑。

##Demo:
完全实现非代理模式启动Activity，Service，BroadcastReceiver，ContentProvider，插件apk分别为百度语音识别Demo.apk，github上miui note.apk，以及自己写的Demo.apk

![Sample Gif](https://github.com/ximsfei/DynamicApk/blob/master/Demo.gif)

##以下是我在csdn上对插件化原理做的同步讲解，希望大家多多支持吧：

Android动态部署一：Google原生Split APK浅析
http://blog.csdn.net/ximsfei/article/details/50884862

Android动态部署二：APK安装及AndroidManifest.xml解析流程分析
http://blog.csdn.net/ximsfei/article/details/50886134

Android动态部署三：如何从插件apk中启动Activity（－）
http://blog.csdn.net/ximsfei/article/details/50926406

## License

The MIT License (MIT)

Copyright (c) 2016 pengfeng wang

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
