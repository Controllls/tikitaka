"""Pinned two-source separator. Only ambiguous short intervals enter this model."""
import numpy as np

REPOSITORY = "speechbrain/sepformer-whamr16k"
REVISION = "21a5b500c6f52fddc387c5d9e5fb13ffd6f039c5"


class Separator:
    def __init__(self, root, device):
        import torch
        from huggingface_hub import snapshot_download
        from speechbrain.lobes.models.dual_path import Encoder, Decoder, Dual_Path_Model, SBTransformerBlock
        if device == "cuda" and not torch.cuda.is_available():
            raise ValueError("음원 분리용 PyTorch CUDA 환경이 없습니다. setup-separation.ps1을 실행하거나 CPU 실행을 선택하세요.")
        self.torch, self.device = torch, device
        directory = root / "audio-lab/sepformer-whamr16k"
        files = ["encoder.ckpt", "decoder.ckpt", "masknet.ckpt"]
        if not all((directory / name).is_file() for name in files):
            snapshot_download(REPOSITORY, revision=REVISION, allow_patterns=files + ["README.md"],
                              local_dir=str(directory), token=False)
        def transformer():
            return SBTransformerBlock(num_layers=8, d_model=256, nhead=8, d_ffn=1024,
                                      dropout=0, use_positional_encoding=True, norm_before=True)
        self.encoder = Encoder(kernel_size=16, out_channels=256)
        self.masknet = Dual_Path_Model(num_spks=2, in_channels=256, out_channels=256,
            num_layers=2, K=250, intra_model=transformer(), inter_model=transformer(), norm="ln",
            linear_layer_after_inter_intra=False, skip_around_intra=True)
        self.decoder = Decoder(in_channels=256, out_channels=1, kernel_size=16, stride=8, bias=False)
        for name in ("encoder", "masknet", "decoder"):
            model = getattr(self, name)
            model.load_state_dict(torch.load(directory / (name + ".ckpt"), map_location="cpu", weights_only=True))
            model.eval().to(device)

    def separate(self, pcm):
        torch = self.torch
        with torch.inference_mode():
            wave = torch.from_numpy(np.array(pcm, dtype=np.float32, copy=True))[None].to(self.device)
            features = self.encoder(wave)
            masks = self.masknet(features)
            outputs = [self.decoder(features * masks[i]).flatten().cpu().numpy() for i in range(2)]
        outputs = np.stack([np.pad(x[:len(pcm)], (0, max(0, len(pcm) - len(x)))) for x in outputs])
        if not np.isfinite(outputs).all():
            raise ValueError("음원 분리에서 유효하지 않은 샘플이 반환되었습니다.")
        return outputs

    def close(self):
        del self.encoder, self.masknet, self.decoder
        if self.device == "cuda":
            self.torch.cuda.empty_cache()
