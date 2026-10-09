# HAL 补丁

## 上游源码

MoKee 的 `android_hardware_qcom_audio`，分支 `mkq-mr1-caf-msm8994`，提交 `7f4cac748b6f62897294cdaece9d1aec27e1e927`。在 MoKee 源码树中的路径是 `hardware/qcom-caf/msm8994/audio`。

## 应用补丁

```sh
cd hardware/qcom-caf/msm8994/audio
for p in /path/to/leo-hifi/hal/patches/0*.patch; do git apply "$p"; done
```

四个补丁必须按编号顺序应用。按顺序打完后，得到的就是开发者设备上运行的候选 C 源码；这一点已逐字节核对过。只打到 02 是候选 A（固定 48 kHz），打到 03 是候选 B。

## 编译

需要 MoKee 10 的 `mokee_leo-userdebug` 构建环境，并在设备的 BoardConfig 中设置：

```make
AUDIO_FEATURE_ENABLED_LEO_HIFI := true
```

只编译音频模块即可：

```sh
m AUDIO_FEATURE_ENABLED_LEO_HIFI=true audio.primary.msm8994
```

不打开这个开关时，编译结果与上游相同；构建脚本中有防护条件，其他 msm8974 平台不会被改动。设备运行时加载的是 32 位模块 `system/vendor/lib/hw/audio.primary.msm8994.so`。

部署前，请先用 `tools/device/check-hal-elf-v2.py` 拿新产物对照手机上现有的 HAL 和依赖库做静态 ABI 检查。

## 主机测试

不需要 Android 环境，用 mock 的 tinyalsa 验证控制器的决策逻辑：

```sh
cd hal/host-tests
sh run-candidate-c.sh /path/to/patched/audio   # 包含 run.sh、run-lifecycle.sh、run-candidate-b.sh 的全部场景
CC="clang -fsanitize=address,undefined -g" sh run-candidate-c.sh /path/to/patched/audio
```

这些测试只能证明逻辑正确，**不能**代替 Android 编译或实机验证。

## 状态协议（schema 5）

`AudioManager.getParameters("leo_hifi_status")` 返回一个字段固定、以逗号分隔的状态串，例如：

```
schema:5,session:…,gen:…,supported:1,requested:hifi,effective:hifi_active,live:1,flow:1,
vol_ctl_l:205,vol_ctl_r:205,vol_db:-25.0,vol_user:35,backend:S24_LE/KHZ_44P1,fail:0,
permanent_fail:0,probes:1,ev:0xfe,bypass:0x2,vol_applied:1,restore_pending:0,
acdb:absent_expected,hardvol:1
```

可写的参数：

| 参数 | 作用 |
|---|---|
| `leo_hifi_mode=true\|false;leo_hifi_session=…;leo_hifi_gen=…` | 开关 HiFi |
| `leo_hifi_volume=0..51;leo_hifi_session=…;leo_hifi_gen=…` | DAC 音量，换算为 −60 + n dB |
| `leo_hifi_hardvol=1;leo_hifi_session=…` | 开启"硬音量保护"：开启期间拒绝普通耳机通道 |
| `leo_hifi_hardvol=0` | 解除保护，不需要会话身份 |

持久属性：`persist.vendor.leo.audio.hifi.dac`（DAC 音量），`persist.vendor.leo.audio.hifi.hardvol`（保护开关）。
