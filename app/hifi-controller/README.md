# Leo HiFi 控制应用（0.6.5-hard-volume）

包名：`com.leoaudio.hifi.singlevolume`，目标 Android 10（API 29）。它是普通应用：不需要 platform 签名，也不需要特权权限。需要配合打了候选 C 补丁的 HAL 使用（状态协议 schema 5）；在 schema 4 的 HAL 上只显示状态，不提供 DAC 音量。

## 功能

- HiFi 开关：通过设置页、快速设置磁贴或通知进入。
- 已核实的工作状态：路由与播放证据都有效时，通知栏显示"ESS 播放中 · 44.1 kHz / 48 kHz"。证据超过 3 秒没有刷新，通知会自动消失。
- 熄屏时停止每秒一次的状态轮询，亮屏后立即读取一次。
- **DAC 硬音量**：通过无障碍服务"HiFi DAC 音量键"实现。只在 HiFi 实际工作时接管音量键，每按一次调 2 dB；其他情况下按键交还给系统。

## 硬音量的安全顺序

- 进入：先把 DAC 设到和当前响度等效的档位，再请求 HAL 开启保护，最后把媒体音量升到最大。
- 用户关闭 HiFi：先把媒体音量恢复到进入前的值，等待 700 ms，让缓冲区中按 0 dB 混好的样本播完，然后解除保护，再切换通道。
- 意外退出（驱动回退、绕过应用修改设置）：HAL 的保护会让普通耳机通道保持静音；应用在约 1 秒内恢复媒体音量并解除保护。
- 开机时的安全音量：Android 开机时会把耳机媒体音量限制在安全档位。HiFi 激活后，应用会把它重新设回最大值。

## 构建

不使用 Gradle，只需要 JDK、Android SDK build-tools 36.0.0 和 API 29 的 `android.jar`：

```sh
ANDROID_API_JAR=/path/to/android-29/android.jar sh build.sh
```

首次构建时会在本目录生成一把调试签名密钥 `.debug.keystore`，这个文件不在仓库里。**覆盖安装必须使用同一把密钥**：换了密钥的 APK 无法覆盖安装，只能先卸载旧版再安装。

安装后，在"设置 → 无障碍"中开启"HiFi DAC 音量键"。

## 测试

```sh
O=build/run.*              # build.sh 的输出目录
javac -d /tmp/t -cp $O/classes tests/GateTest.java tests/RateTest.java tests/ProtocolTest.java
java -cp $O/classes:/tmp/t com.leoaudio.hifi.singlevolume.GateTest tests/wire-transport.tsv
java -cp $O/classes:/tmp/t com.leoaudio.hifi.singlevolume.RateTest tests/wire-transport.tsv
java -cp $O/classes:/tmp/t com.leoaudio.hifi.singlevolume.ProtocolTest tests/protocol-wire.tsv
```

`SingleVolumeTest.java` 是 0.3 版的测试，它断言"不允许写 DAC 音量"。0.6 有意重新开放了 DAC 音量，所以这个测试按设计会在第 11 项断言失败。保留它是为了记录这次设计变更。
