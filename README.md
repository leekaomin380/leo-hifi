# leo-hifi：小米 Note Pro（leo）ES9018 HiFi 音频驱动补丁

为 MoKee Android 10 上的小米 Note Pro（顶配版，代号 `leo`，骁龙 810，ESS ES9018 DAC）维护的音频 HAL 补丁、HiFi 控制应用和配套工具。

本项目只在**一台手机、一个固件**上实机验证过：MoKee `MK100.0-leo-221019-RELEASE`。其他固件、其他机型都**没有**测试过。

## 做了什么

| 阶段 | 解决的问题 | 补丁 |
|---|---|---|
| 基础 | 新增 `hifi-headphones` 设备，把有线耳机输出接到 QUAT_MI2S，经 ES9018 送到 OPA1612 运放，不再走 WCD9330 耳放；带证据门禁的状态机；`leo_hifi_status` 状态协议 | `hal/patches/01-leo-hifi-base.patch` |
| A | 多个音频流共享 QUAT 后端时：提示音退出不再把仍在播放的音乐误报为空闲，也不会在播放中改写后端时钟 | `02-candidate-a-shared-backend.patch` |
| B | 44.1 kHz 同率：deep-buffer 前端接受 44100 Hz；后端采样率跟随第一个进入的流，活动中从不改时钟，最后一路退出后恢复为 48 kHz | `03-candidate-b-44k1.patch` 和 `policy/deep-buffer-44100-only.patch` |
| C | DAC 硬件音量：音量键直接调 ES9018（−60～−9 dB，每档 2 dB），HiFi 时 Android 增益固定 0 dB；任何未按顺序的退出都会静音，而不是变响 | `04-candidate-c-dac-hard-volume.patch` 和 `app/hifi-controller` |

全部启用后，44.1 kHz / 16 bit 的音乐（例如 Spotify 解码后的输出）从 Android 混音器到 DAC，全程采样率不变，增益为 0 dB。详见 `docs/design.md`。

## 实测结果（实测于开发者的设备）

- 44.1 kHz 时钟：用 PCM 硬件指针的时间戳测量，30 秒内每秒消耗 44,106 帧；48 kHz 测得 47,999.6 帧。
- 并发、拔插耳机、HiFi 开关、服务重启、整机重启：全部通过。
- 主机测试：HAL 控制器 251 项场景（包括 ASan/UBSan 下运行）；应用端 26 项闸门检查、5 项采样率检查、57 项协议检查。

**没有测量过的**：DAC 和 I2S 上的数字样本没有采集比对，所以"比特完整"只能说是按设计实现，没有经过测量证明；耳机口的实际输出电平也没有测量。

## ⚠️ 风险

- 部署需要 root，并要以读写方式重新挂载 `/system`。部署脚本会先备份、再替换，失败时自动回滚，但不能排除变砖或无声的可能。
- **听力安全**：候选 C 让 Android 媒体音量固定在最大值，响度完全由 DAC 决定。DAC 上限 −9 dB 只是保守取值，**并非根据测量得出**。灵敏的耳机请先从低音量开始。
- 回滚到旧驱动或旧版应用之前，**必须先在应用里关闭 HiFi**。否则媒体音量会停在最大值，而 DAC 会回到固定的 −25 dB，响度会突然变大。

## 目录

```
hal/patches/        相对 MoKee android_hardware_qcom_audio（mkq-mr1-caf-msm8994，7f4cac74）的四个补丁，按顺序应用
hal/host-tests/     不依赖 Android 的控制器故障注入测试（mock tinyalsa）
policy/             音频策略 XML 补丁：deep_buffer 只声明 44100
app/hifi-controller HiFi 控制应用：开关、状态、DAC 音量键（无障碍服务）
tools/device/       部署、回滚、策略覆盖与固化、时钟测量、ABI 静态检查脚本
tools/fixture-app/  测试音源：可控的 44.1/48 kHz 两路播放，以及一条模拟故障退出的测试命令
docs/design.md      设计、安全规则、测量方法、已知问题
```

构建和部署步骤分别见 `hal/README.md`、`app/hifi-controller/README.md`、`tools/README.md`。

## 来源与许可

- 补丁所改的上游文件来自 AOSP 和 The Linux Foundation（CAF），许可证为 Apache-2.0，原有版权声明全部保留。完整说明见 `NOTICE`。
- 本项目新增的代码、测试、脚本和文档均以 Apache-2.0 发布，见 `LICENSE`。
- 本项目的大量代码和文档由 AI 编程助手（OpenAI Codex、Anthropic Claude）生成，由仓库作者审阅，并在实机上验收。
- 本项目与小米、MoKee、ESS、Spotify 均无关联。
