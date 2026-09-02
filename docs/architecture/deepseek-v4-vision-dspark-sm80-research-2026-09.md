# DeepSeek V4 Vision + DSpark + SM80 可复现性调研（2026-09）

## 结论

截至 **2026-09-02 13:28 CST**，在公开、可核验的主要来源中，**尚未找到同一个运行时、同一次部署同时满足以下三项且给出完整复现证据的案例**：

1. `deepseek-ai/DeepSeek-V4-Flash-Vision-Exp` 的真实图像输入经过 processor、ViT、aligner 和视觉路由，而不是仅加载该 checkpoint 的文本主干；
2. `method=dspark` 成功启动，并有接受率或吞吐实测；
3. 在 NVIDIA SM80（A100/A800/GA100 同代路径）上完成实测。

公开证据目前分成两个不相交的集合：

- **真视觉 + DSpark：已证实，但硬件是 GB200/SM100。** vLLM PR [#54566](https://github.com/vllm-project/vllm/pull/54566) 在 4×GB200 上完成 1000 样本 OCRBench，并给出 DSpark 接受率；官方 recipe 明确写明 GB200 是唯一有公开视觉运行记录的硬件。
- **SM80：已分别证实文本 DSpark 和真视觉，但不是同一路径。** `wtdcode/vllm-backport`/PixelML 的 vLLM 路径可在 SM80 上运行 DSpark，却不向模型提供图像；PixelML 的参考 TP4 fallback 路径能完成真实图像推理，却明确没有 speculative decoding。

因此，不能据现有 issue、PR、镜像标签或部署模板断言“已有可直接替换当前 A800 镜像的 SM80 + Vision + DSpark 成品”。最接近的候选是 Hugging Face Inference Endpoints 的 8×A100 模板，但它只有配置声明、`numDeployments: 0`，没有启动日志、图像响应、DSpark 接受率或吞吐证据。

## 判定口径

本文不把下列情况计为“支持 Vision + DSpark + SM80”：

- checkpoint 名称含 `Vision`，但视觉权重被跳过，HTTP 图像请求被拒绝；
- ViT/aligner 单元测试与参考实现一致，但尚未接入 OpenAI 多模态 processor、prompt expansion、图像 token 和语言模型视觉路由；
- `--speculative-config` 出现在命令或模板中，但没有引擎启动成功、实际生成或接受率证据；
- 镜像构建包含 `sm_80` cubin/PTX，但没有该模型在 SM80 上的端到端实测；
- 同一 checkpoint 在两个不同运行时中分别完成文本 DSpark 和图像推理；
- A100 被列为可选实例，但模板从未实际部署。

“可复现实例”至少应公开：不可变代码提交或镜像摘要、完整启动参数、GPU 型号/计算能力、真实图像请求成功证据，以及 DSpark 启动和接受率/吞吐数据。

## 证据矩阵

| 路径 | Vision checkpoint 可加载 | 真视觉输入 | DSpark 启动 | DSpark 接受率/吞吐 | SM80 实测 | 判定 |
|---|---:|---:|---:|---:|---:|---|
| vLLM PR #54566 / 官方 vision 镜像 | 是 | 是 | 是 | 是 | 否，公开验证为 4×GB200 | 满足前两项，不满足 SM80 |
| `tacos8me` / vLLM #54561 | 是 | 是 | 有作者声明/SM120 后续测试 | SM120 有吞吐数据 | 否，SM120 | 非 SM80 |
| `wtdcode/vllm-backport` master / `latest-sm80` | 是，文本方式 | 否 | 是 | 文本模型有实测 | 是 | 仅 SM80 文本 DSpark |
| `wtdcode/vllm-backport:dsv4-vision-exp` | 是 | 否，ViT/aligner 尚未接入服务链路 | 无 Vision 联合证据 | 无 | ViT 对比测试在 4×A6000/SM86 | 不构成端到端视觉 |
| PixelML 4×CMP 170HX vLLM PP4 | 是 | 否，image rejected/not served | 是，`k=6` | 有文本吞吐，部分结果仍为占位/早期结果被标为 superseded | 是，GA100/SM80 | 同一路径缺真视觉 |
| PixelML 4×CMP 170HX reference TP4 fallback | 是 | 是，真实图像与对照均通过 | 否 | 无 | 是，GA100/SM80 | 同一路径缺 DSpark |
| Hugging Face Endpoint catalog：8×A100 | 模板声明是 | 模板声明是 | 模板参数含 DSpark `k=3` | 无 | 无实际部署记录 | 最接近候选，但不是成功案例 |
| `allover326/deepseek-v4-cmp170hx#13` | 尝试 | 否，首个 forward 前/中失败 | 仅分析参数约束 | 无 | 是，SM80 尝试 | 明确失败案例 |

## 1. vLLM 官方路径：Vision + DSpark 已工作，但仅验证 GB200

### PR 状态与不可变版本

- vLLM PR [#54566](https://github.com/vllm-project/vllm/pull/54566) 于 2026-08-31 创建；截至本次检索仍为 **Open**，不是稳定版能力。
- 截止时 PR head 为 [`3e3c938ebb837efcf3535e8644d21a413ed08c0d`](https://github.com/vllm-project/vllm/commit/3e3c938ebb837efcf3535e8644d21a413ed08c0d)（2026-09-02）。PR 期间多次合并 `main`，因此必须按提交或镜像摘要固定，不能只依赖浮动分支。
- 官方 Docker 标签 `vllm/vllm-openai:deepseekv4-flash-vision` 于 2026-09-01 更新。Docker Hub manifest-list 摘要为 `sha256:0075fd82e3b6d943b0aa91e35da8dbca63d88516c607745131055e9d81f37ebb`；linux/amd64 镜像摘要为 `sha256:65f80b25dfb7b6a419fa8054a5da22a35bec798ef82e625354d1f708448c9b6c`。可在 [Docker Hub 标签页](https://hub.docker.com/r/vllm/vllm-openai/tags?name=deepseekv4-flash-vision) 复核。
- 镜像配置声明 `TORCH_CUDA_ARCH_LIST=7.5 8.0 8.6 8.9 9.0 10.0 11.0 12.0`，但 `VLLM_BUILD_COMMIT=unknown`。包含 `8.0` 仅证明构建目标包含 SM80，**不证明 DeepSeek V4 的整套稀疏注意力、FP4/FP8、MoE、Vision 和 DSpark 内核已在 SM80 端到端可用**。

### 真视觉证据

PR 的测试计划使用 4×GB200、TP4 + expert parallel，真实运行完整 1000 样本 OCRBench，结果为 835/1000、0 request error、312.6 秒。该结果要求图像 processor、视觉 tower、aligner、图像 token、`bias_vl` 路由和多模态 attention 链路实际参与，不能由文本模式伪造。证据见 [PR 描述的 Test Plan/Test Result](https://github.com/vllm-project/vllm/pull/54566)。

独立实现 issue [#54561](https://github.com/vllm-project/vllm/issues/54561) 还解释了仅“加载 vision checkpoint”为何不等于真视觉：该模型需要 OOV/sentinel 处理、hash-routing 越界保护、`bias_vl` 模态路由，以及位置相关的图像 prompt expansion。只跳过 316 个视觉相关 tensor 会失去这些能力。

### DSpark 联合证据

同一 GB200 OCRBench 运行使用：

```text
--speculative-config {
  "method":"dspark",
  "model":"deepseek-ai/DeepSeek-V4-Flash-Vision-Exp",
  "num_speculative_tokens":3,
  "draft_sample_method":"probabilistic",
  "enable_adaptive_verification":true
}
```

PR 报告混合文本+图像流量下：

- 平均 acceptance length：2.99 tokens/forward；
- 总接受率：66.3%（266,788 / 402,660）；
- 第 1/2/3 draft position 平均接受率：83.9% / 66.5% / 50.7%。

官方 [DeepSeek-V4-Flash-Vision-Exp recipe](https://recipes.vllm.ai/deepseek-ai/DeepSeek-V4-Flash-Vision-Exp) 复述了相同配置和数字，并明确说明 drafter 通过 target hidden states 读取图像内容。

### 为什么不能外推到 SM80

官方 recipe 的限制部分明确写明：**GB200 是唯一有公开 vision run 的硬件**，其他硬件选项不是测试声明。该页列出的 verified configuration 也是单个 GB200 NVL4 tray，而不是 A100/A800。

PR 评论中的其他成功运行是 2×RTX PRO 6000 Blackwell（SM120），不是 SM80。例如 [SM120 测试评论](https://github.com/vllm-project/vllm/pull/54566#issuecomment-5492736382) 报告真视觉、DSpark `k=3` 和 169/434/1087 tok/s（并发 1/4/16），但该路径还需要 FlashInfer 的 SM120 top-k 512 修复；对应 FlashInfer PR [#4850](https://github.com/flashinfer-ai/flashinfer/pull/4850) 同样只针对 SM120/SM121，不能当作 SM80 证据。

## 2. SM80 backport：文本 DSpark 成熟，Vision 仍未接入端到端

### master 与 Docker 镜像

[`wtdcode/vllm-backport`](https://github.com/wtdcode/vllm-backport) 的目标是为旧 GPU 回退 DeepSeek V4 Flash，README 给出 A100 使用 `latest-sm80`，并推荐文本版 `DeepSeek-V4-Flash-0731` 配合 DSpark。截止时：

- master：[`3bec275739c6f4cc7c2ff403d0556d477e4d0f33`](https://github.com/wtdcode/vllm-backport/commit/3bec275739c6f4cc7c2ff403d0556d477e4d0f33)，2026-09-01；
- `lazymio/vllm-backport:latest-sm80`：`sha256:3cc9b255350c308519bdd39dbdd59270180f6c64165db972207ce857b1ce4e42`，2026-09-01；
- `v0.11.3-sm80`：`sha256:c5fd3a113a6786acca2ee745cd5c5ec7e80289bdba4590676520480eadcf4919`，2026-09-01；
- `v0.11.2-sm80`：`sha256:a45d5e2e82cac6e6d68930d078001e4ececf2cafeb5512b7fc76c6f227187a7e`，2026-08-31。

这些标签可在 [lazymio/vllm-backport tags](https://hub.docker.com/r/lazymio/vllm-backport/tags) 复核。标签列表中没有 `vision`/`dsv4-vision` 镜像。

### `dsv4-vision-exp` 分支的实际能力边界

issue [#48](https://github.com/wtdcode/vllm-backport/issues/48) 仍为 Open。维护者先明确回复“不会直接工作，需要新支持”，随后称实验分支只有 initial support。

分支 head 为 [`f8e055927261bf412451fc7b8842aac70d387e71`](https://github.com/wtdcode/vllm-backport/commit/f8e055927261bf412451fc7b8842aac70d387e71)，2026-08-31，相对当时 merge-base 仅有两个提交且已落后 master：

1. [`7a41df2f`](https://github.com/wtdcode/vllm-backport/commit/7a41df2f52835038abf3564189694d3f34e24487) 的提交标题就是“load the Vision-Exp checkpoint text-only”。它跳过 ViT、aligner、image sentinel 和 `bias_vl`，只在 4×A6000 上验证文本生成。
2. [`f8e05592`](https://github.com/wtdcode/vllm-backport/commit/f8e055927261bf412451fc7b8842aac70d387e71) 加入 ViT 和 aligner，并在 `carrots.jpeg` 上与参考实现做 bitwise 对比，证明独立视觉编码模块正确。

但分支的服务模型仍是 `DeepseekV4ForCausalLM`。其 `load_weights` 仍构造 `vision_skip`，映射 `.ffn.gate.bias_vl`、`vision.`、`aligner.` 和 `image_` 到 `None`；代码中的 TODO 也写明“Until the vision path exists”。分支没有新增多模态 conditional-generation class、processor 注册、OpenAI 图像输入处理、图像 embedding splice 或视觉 attention metadata。可直接查看 [该提交的 `model.py`](https://github.com/wtdcode/vllm-backport/blob/f8e055927261bf412451fc7b8842aac70d387e71/vllm/models/deepseek_v4/nvidia/model.py#L1861-L1881)。

所以该分支证明的是“ViT/aligner 算子移植正确”，不是“vLLM 能接收图像”。它也没有给出 Vision 与 DSpark 联合启动、接受率或吞吐数据。

## 3. SM80 社区实测：两条路径各缺一项

### PixelML：vLLM PP4 + DSpark `k=6`，但不提供图像

PixelML 的 draft PR [club-170hx#14](https://github.com/PixelML/club-170hx/pull/14) 记录了 4×CMP 170HX（GA100/SM80）运行同一 Vision-Exp checkpoint（revision `86f746b36186f0e567729a5c06a8c918caba82a9`）。其 vLLM PP4 路径使用 FP8 KV cache 和 DSpark `k=6`，但文档明确标记：

- `Text passed; image not served on this path`；
- 早期运行是 `image rejected (HTTP 400)`；
- 新的标准化吞吐仍在进行，PR 中的 `{{C1}}`、`{{C4}}`、`{{C16}}` 等是占位符；
- 早期 101.21/169.65 tok/s 等结果被标记为来源未完全固定、已 superseded。

因此它是“SM80 + DSpark + Vision checkpoint 的文本主干”证据，不是真视觉证据。

### PixelML：reference TP4 fallback 真视觉，但没有 DSpark

同一 PR 的另一条 reference TP4 + SM80 fallback 路径完成真实 64×64 gradient image，并执行 no-image 与 wrong-image 对照；记录约 0.9 tok/s decode。这足以表明 Vision tower 真正参与推理。

但该文档明确把它与 vLLM 文本路径分开，并说明 reference path **没有 speculative decoding**。所以不能把“一个运行时的 DSpark”与“另一个运行时的 Vision”拼成同一个成功案例。

该 PR 仍为 Draft，详细 receipts 所在仓库在检索时仍为 private；因此即使分别看两条路径，复现完整性也弱于上游 PR。

### 失败适配记录

[`allover326/deepseek-v4-cmp170hx#13`](https://github.com/allover326/deepseek-v4-cmp170hx/issues/13) 记录了 4×CMP 170HX/SM80 的失败尝试：旧 SM80 stack 在 aligner、gate bias/`bias_vl` 处失败；另一 fork 缺少 SM80 sparse attention fallback；官方 reference 在首个 forward 的 FP8 路径触发 device assert。该记录还指出 Vision checkpoint 的 `num_nextn_predict_layers=3`，因此旧 0731 的 DSpark `k=5` 不兼容，应考虑 3 或 6，但这只是参数约束，不是联合成功证据。

## 4. Hugging Face 8×A100 模板：最接近，但尚未形成实测

Hugging Face 官方 [Inference Endpoints catalog](https://endpoints.huggingface.co/catalog) 在 2026-09-01 新增了 `deepseek-ai/DeepSeek-V4-Flash-Vision-Exp` 项目，界面显示：

- task：Image-Text-to-Text；
- engine：vLLM；
- hardware：8×NVIDIA A100；
- image：`vllm/vllm-openai:deepseekv4-flash-vision`；
- server args 包含 TP8、expert parallel、FP8 KV 和与官方 PR 相同的 DSpark `k=3` probabilistic/adaptive 配置。

这是目前唯一公开把 **A100 + 官方 Vision 镜像 + DSpark 参数** 放在同一个配置对象里的主要来源，值得优先试验。

但同一 catalog 数据同时显示 `numDeployments: 0`。没有公开的：

- 引擎 ready 日志；
- A100 上真实图像 HTTP 200 与视觉结果；
- DSpark proposer 启动日志；
- 接受率或吞吐；
- 成功部署所用镜像摘要。

而 vLLM 官方 recipe 明确说 GB200 是唯一有公开视觉运行的硬件。因此该 catalog 条目只能判为**部署建议模板**，不能判为已验证案例。它与“镜像包含 `sm_80` 构建目标”一样，是可行性线索，不是端到端证明。

## 5. 其他相关路线为何不满足

- FlashInfer PR [#4850](https://github.com/flashinfer-ai/flashinfer/pull/4850) 解决的是 DeepSeek V4 Vision 在 SM120/SM121 的 dual-cache prefill top-k 512；没有 SM80 实测。
- `tacos8me` 在 vLLM [#54561](https://github.com/vllm-project/vllm/issues/54561) 和 #54566 评论中的成功环境是 2×RTX PRO 6000 Blackwell（SM120），不是 A100/A800。
- GGUF/llama.cpp 的 Vision 实现即使能在 A100 上看图，也不等于 vLLM DSpark；DSpark 是该 vLLM 路径的专用 speculative proposer，不能用普通 speculative decoding 或 llama.cpp draft 功能替代。
- DeepSeek 官方 API 能处理图像，但没有公开后端硬件和 DSpark 运行信息，不能作为 SM80 自部署案例。

## 6. 对当前 A800 部署的含义

当前 `v0.11.2-sm80-dsv4vision` 类部署与公开 backport 的能力边界一致：能够利用 SM80 fallback 跑 Vision checkpoint 的文本主干，但缺少完整的多模态注册/processor/路由链路。仅给现有命令加 `method=dspark` 不能自动获得“Vision + DSpark”：

- 旧 backport 的 Vision 权重加载仍显式 skip；
- Vision checkpoint 的 draft 深度为 3，不能照搬 0731 的 `k=5`；
- 即使把 DSpark loader 的 `bias_vl`/hash-layer权重问题修掉，也只解决加载，不代表 image token、visual bias routing 和 multimodal attention 已接通。

目前风险最低的验证顺序是：

1. 保留现有已知可回滚的 SM80 服务；
2. 用不可变摘要 `vllm/vllm-openai@sha256:65f80b25dfb7b6a419fa8054a5da22a35bec798ef82e625354d1f708448c9b6c` 在隔离端口尝试 Hugging Face catalog 的 TP8/A100 配置；
3. 必须依次验证 engine ready、文本 baseline、真实图像正确性、DSpark metrics，再做同口径 A/B 吞吐；
4. 若官方镜像在 A800 的 SM80 kernel/backend 处失败，正确工程路线是把 vLLM #54566 的完整多模态改动移植到 `wtdcode/vllm-backport` 最新 master，而不是只修 `dspark.py` 的几行 weight skip；
5. 只有同一进程同时产生图像正确结果和 DSpark 接受率，才可宣布三项能力成立。

本次调研不包含任何服务器部署或配置修改。

## 来源索引

- [vLLM PR #54566：官方多模态实现、GB200 OCRBench、DSpark 接受率](https://github.com/vllm-project/vllm/pull/54566)
- [vLLM issue #54561：独立实现与多模态耦合说明](https://github.com/vllm-project/vllm/issues/54561)
- [vLLM 官方 Vision-Exp recipe](https://recipes.vllm.ai/deepseek-ai/DeepSeek-V4-Flash-Vision-Exp)
- [官方 Vision Docker 标签](https://hub.docker.com/r/vllm/vllm-openai/tags?name=deepseekv4-flash-vision)
- [`wtdcode/vllm-backport`](https://github.com/wtdcode/vllm-backport)
- [`wtdcode/vllm-backport` issue #48](https://github.com/wtdcode/vllm-backport/issues/48)
- [`dsv4-vision-exp` head](https://github.com/wtdcode/vllm-backport/commit/f8e055927261bf412451fc7b8842aac70d387e71)
- [lazymio SM80 镜像标签](https://hub.docker.com/r/lazymio/vllm-backport/tags)
- [PixelML/club-170hx PR #14](https://github.com/PixelML/club-170hx/pull/14)
- [SM80 失败适配记录 #13](https://github.com/allover326/deepseek-v4-cmp170hx/issues/13)
- [Hugging Face Inference Endpoints catalog](https://endpoints.huggingface.co/catalog)
- [FlashInfer PR #4850](https://github.com/flashinfer-ai/flashinfer/pull/4850)
- [NVIDIA CUDA Compute Capability：A100 为 8.0](https://developer.nvidia.com/cuda/gpus)
- [NVIDIA A800：Ampere 架构](https://www.nvidia.com/en-us/products/workstations/a800/)
