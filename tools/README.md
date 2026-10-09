# 工具

所有脚本都通过 adb 操作手机。使用前先设置手机的序列号：

```sh
export LEO_SERIAL=<adb devices 显示的序列号>
export ADB=adb            # 可选，默认就是 adb
```

手机需要 root，并且 `adb shell` 默认以 root 身份运行（MoKee userdebug 版本满足这一条件）。

## device/deploy_hal.py：部署或回滚 HAL

它只接受一个**审阅过的 manifest**。manifest 中写明：候选 `.so` 的路径和 SHA-256、作为回滚目标的现有 HAL 的 SHA-256、不允许被改动的 lib64 HAL 的 SHA-256、ABI 报告及其 SHA-256、构建核对是否通过。

```sh
python3 deploy_hal.py manifest.json                 # 只读预检
python3 deploy_hal.py manifest.json --deploy        # 备份、替换、恢复只读、重启服务、确认新 HAL 已被加载
python3 deploy_hal.py manifest.json --rollback deployment-<时间戳>
```

部署时会把替换前的 HAL 备份到 Mac 和手机两处。如果系统分区无法重新挂载为只读，脚本会重启手机。部署失败时会自动回滚；如果手机在过程中断开连接，重新连上后需要手动执行回滚。

manifest 示例：

```json
{"candidate": "C-dac-hard-volume", "device": "leo", "serial": "<序列号>",
 "target": "/system/vendor/lib/hw/audio.primary.msm8994.so",
 "rollback_sha256": "<现有 HAL>", "untouched_lib64_sha256": "<现有 lib64 HAL>",
 "candidate_path": "/path/audio.primary.msm8994.so", "candidate_sha256": "…",
 "abi_report": "/path/loader-check.json", "abi_report_sha256": "…",
 "formal_build_verified": true, "actual_compile_flags_reviewed": true, "static_abi_passed": true}
```

## device/check-hal-elf-v2.py：静态 ABI 检查

```sh
python3 check-hal-elf-v2.py <候选.so> <手机上现有的HAL.so> <依赖库目录> <输出报告.json>
```

依赖库目录里放从手机拉下来的依赖库，以及一份 `manifest.json`，记录每个库的 SHA-256。检查项包括：每个强符号导入都能在依赖链中找到、SONAME、ARM 浮点调用约定和架构属性、BIND_NOW/RELRO、不可执行栈、导出的 HMI 结构。通过这项检查只说明加载器层面兼容，**不证明**能正常播放。

## device/policy_b.sh：临时覆盖策略文件

用 bind mount 把打过补丁的 `audio_policy_configuration.xml` 覆盖到 `/vendor/etc/audio_policy_configuration.xml` 上，然后重启 audioserver。手机重启后自动失效。

```sh
export LEO_POLICY_XML=/path/patched/audio_policy_configuration.xml
sh policy_b.sh apply | revert | status
```

脚本内置的哈希对应 MoKee `MK100.0-leo-221019-RELEASE`。其他固件请用 `LEO_POLICY_NEW_SHA16` 和 `LEO_POLICY_STOCK_SHA16` 覆盖。

## device/persist_policy.sh：永久写入策略文件

把打过补丁的 XML 写进系统分区，写入前在 Mac 和手机两处备份原文件。

```sh
sh persist_policy.sh install | rollback
```

## device/rate.sh：测量真实时钟

```sh
sh rate.sh pcm0p 30
```

用内核给 PCM 硬件指针打的时间戳，计算播放端每秒实际消耗的帧数。ES9018 是 I2S 时钟主设备，所以这个数字反映 DAC 实际运行的时钟族：44.1 kHz 应接近 44100，48 kHz 应接近 48000。若声称跑在 44.1 kHz，却测到约 48000，说明时钟没有切换过去，音高会偏高约 8.8%。窗口越长越精确，30 秒窗口的分辨率约为 0.07%。

## device/capture.py：采集证据快照

只读地保存 audio_flinger、audio_policy、mixer、PCM、HAL 日志和测试音源的状态，用于对照修复前后的差异。

## fixture-app：测试音源

包名 `com.leoaudio.fixture`。用 adb 驱动，可以分别播放 −42 dBFS 的音乐流（deep buffer）和提示音流（low latency）：

```sh
adb shell am start-foreground-service -n com.leoaudio.fixture/.FixtureService --es command music --ei rate 44100
adb shell am start-foreground-service -n com.leoaudio.fixture/.FixtureService --es command aux --ei rate 48000
adb shell am start-foreground-service -n com.leoaudio.fixture/.FixtureService --es command stop-all
adb shell dumpsys activity service com.leoaudio.fixture/.FixtureService   # 帧计数与 HAL 状态
```

`raw-hifi-off` 命令会绕过 HiFi 应用，直接让 HAL 关闭 HiFi，用来验证意外退出时声音是否保持静音。**仅限测试使用。**
