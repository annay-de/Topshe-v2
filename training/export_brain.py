"""
Builds the phone file (topshe_brain.gguf) from the trained LoRA adapter, and proves the training is in it.
Runs on GitHub Actions (CPU) after Kaggle has trained and tested the model:

    python training/export_brain.py build --adapter build/out/lora \
        --template build/out/prompt_template.json --work build/export

Output: <work>/topshe_brain.gguf and <work>/export.json (weight check, smoke test, size, sha256).

Why the merge is done here and not with Unsloth's save_pretrained_merged:
the first run (Nov 2026, unsloth 2026.11.9, Qwen 3.5 4B) produced a phone file identical to the
untouched base model - the training was silently lost (correlation with the LoRA delta: 0.000).
So the merge is plain PyTorch with strict checks, and the finished file is checked weight by weight:
  1. every LoRA module must land on exactly one base weight of the right shape
  2. the 16-bit phone file must equal base + LoRA delta on sample weights
  3. the 4-bit phone file must still carry the delta (clear correlation, closer to base+delta than base)
  4. the 4-bit file must answer test commands correctly with the exact phone prompt
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

LLAMA_CPP_TAG = "b12602"            # must match android/app/src/main/cpp/CMakeLists.txt
QUANT = "Q4_K_M"
# sample weights for the weight check: (layer, module, phone-file tensor)
CHECKS = [(3, "mlp.down_proj", "blk.3.ffn_down.weight"), (3, "mlp.gate_proj", "blk.3.ffn_gate.weight"),
          (17, "mlp.up_proj", "blk.17.ffn_up.weight"), (30, "mlp.down_proj", "blk.30.ffn_down.weight")]
SMOKE_CMD = [("wake me up at 6:45 tomorrow", "set_alarm"), ("play tum hi ho", "media"), ("call maa", "call"),
             ("remember that my gym is at 7", "remember"), ("go to sleep", "stop"),
             ("why is the sky blue", "chat"), ("set a timer for 10 minutes", "set_timer"),
             ("remind me to call baba at 8 pm", "set_reminder"), ("open spotify", "open_app"),
             ("pause the music", "media"), ("text riya that i'm running late", "message")]
SMOKE_CHAT = [("who directed pather panchali?", "ray")]


def sh(cmd, **kw):
    print("$", cmd if isinstance(cmd, str) else " ".join(map(str, cmd)), flush=True)
    return subprocess.run(cmd, shell=isinstance(cmd, str), check=True, **kw)


def adapter_info(adapter):
    cfg = json.loads((Path(adapter) / "adapter_config.json").read_text())
    if cfg.get("use_dora"):
        raise SystemExit("DoRA adapters are not supported by this merge")
    if cfg.get("rank_pattern") or cfg.get("alpha_pattern"):
        raise SystemExit("per-module rank/alpha patterns are not supported by this merge")
    if cfg.get("modules_to_save"):
        raise SystemExit(f"modules_to_save is not supported by this merge: {cfg['modules_to_save']}")
    r, alpha = cfg["r"], cfg["lora_alpha"]
    scale = alpha / (r ** 0.5) if cfg.get("use_rslora") else alpha / r
    return cfg, r, scale


# ---------------------------------------------------------------- steps that need torch (run in the venv)

def load_adapter(adapter):
    from safetensors.torch import load_file
    _, r, scale = adapter_info(adapter)
    raw = load_file(str(Path(adapter) / "adapter_model.safetensors"))
    mods = {}
    for k, v in raw.items():
        m = re.fullmatch(r"base_model\.model\.(.+)\.lora_([AB])\.weight", k)
        if not m:
            raise SystemExit(f"unexpected adapter tensor {k} (only plain LoRA A/B weights are supported)")
        mods.setdefault(m.group(1), {})[m.group(2)] = v
    for name, ab in mods.items():
        if set(ab) != {"A", "B"}:
            raise SystemExit(f"{name}: missing lora_A or lora_B")
        if ab["A"].shape[0] != r or ab["B"].shape[1] != r:
            raise SystemExit(f"{name}: rank mismatch {tuple(ab['A'].shape)} {tuple(ab['B'].shape)} (r={r})")
    return mods, scale


def weight_map(model_dir):
    idx = Path(model_dir) / "model.safetensors.index.json"
    if idx.exists():
        return json.loads(idx.read_text())["weight_map"]
    single = Path(model_dir) / "model.safetensors"
    from safetensors import safe_open
    with safe_open(str(single), "pt") as f:
        return {k: single.name for k in f.keys()}


def rewrite_shard(path, new_tensors):
    """Writes a safetensors shard with some tensors replaced, one tensor at a time (low memory).
    new_tensors: {key: function() -> float16 torch tensor}. Everything else is copied byte for byte."""
    import struct
    import torch
    with open(path, "rb") as fh:
        n = struct.unpack("<Q", fh.read(8))[0]
        header = json.loads(fh.read(n))
    meta = header.pop("__metadata__", None)
    order = sorted(header, key=lambda k: header[k]["data_offsets"][0])
    out_header, pos = {}, 0
    for k in order:
        h = header[k]
        if k in new_tensors:
            numel = 1
            for d in h["shape"]:
                numel *= d
            size, dtype = numel * 2, "F16"
        else:
            size, dtype = h["data_offsets"][1] - h["data_offsets"][0], h["dtype"]
        out_header[k] = {"dtype": dtype, "shape": h["shape"], "data_offsets": [pos, pos + size]}
        pos += size
    if meta is not None:
        out_header["__metadata__"] = meta
    hb = json.dumps(out_header, separators=(",", ":")).encode()
    hb += b" " * (-(8 + len(hb)) % 8)                 # pad so the data starts 8-byte aligned
    tmp = str(path) + ".tmp"
    with open(path, "rb") as src, open(tmp, "wb") as dst:
        dst.write(struct.pack("<Q", len(hb)))
        dst.write(hb)
        for k in order:
            if k in new_tensors:
                t = new_tensors[k]()
                data = t.contiguous().view(-1).numpy().tobytes()
                want = out_header[k]["data_offsets"][1] - out_header[k]["data_offsets"][0]
                if t.dtype != torch.float16 or len(data) != want:
                    raise SystemExit(f"{k}: wrote {len(data)} bytes as {t.dtype}, expected {want} float16")
                dst.write(data)
            else:
                s0, s1 = header[k]["data_offsets"]
                src.seek(8 + n + s0)
                left = s1 - s0
                while left:
                    chunk = src.read(min(left, 64 << 20))
                    if not chunk:
                        raise SystemExit(f"{path}: truncated at {k}")
                    dst.write(chunk)
                    left -= len(chunk)
    os.replace(tmp, path)


def cmd_merge(a):
    """Adds the LoRA delta to the base weights in place. Changed weights are stored as float16
    (finer than bfloat16, and the phone file is converted to float16 anyway)."""
    import torch
    from safetensors import safe_open
    from safetensors.torch import save_file
    mods, scale = load_adapter(a.adapter)
    wmap = weight_map(a.model)
    targets = {}
    for name in mods:
        key = name + ".weight"
        if key not in wmap:
            tail = ".".join(name.split(".")[-3:])
            near = [k for k in wmap if tail in k][:5]
            raise SystemExit(f"LoRA module {name} has no base weight {key}. Similar base weights: {near}")
        targets[name] = key
    if len(set(targets.values())) != len(targets):
        raise SystemExit("two LoRA modules point at the same base weight")
    print(f"LoRA modules: {len(mods)} (scale {scale}); all matched a base weight")

    check_keys = {}
    for layer, part, _ in CHECKS:
        hit = [n for n in mods if n.endswith(f".layers.{layer}.{part}")]
        if len(hit) != 1:
            raise SystemExit(f"weight check: expected one LoRA module for layer {layer} {part}, found {hit}")
        check_keys[targets[hit[0]]] = hit[0]
    keep, done = {}, []
    by_shard = {}
    for name, key in targets.items():
        by_shard.setdefault(wmap[key], []).append(name)
    for shard, names in sorted(by_shard.items()):
        path = Path(a.model) / shard
        f = safe_open(str(path), "pt")

        def merged(name, key, f=f):
            W = f.get_tensor(key)
            A, B = mods[name]["A"].float(), mods[name]["B"].float()
            if W.dim() != 2 or (B.shape[0], A.shape[1]) != tuple(W.shape):
                raise SystemExit(f"{key}: shape {tuple(W.shape)} vs LoRA {(B.shape[0], A.shape[1])}")
            Wn = W.float() + (B @ A) * scale
            if not torch.isfinite(Wn).all() or Wn.abs().max() > 60000:
                raise SystemExit(f"{key}: merged weight is not finite or too large for float16")
            if key in check_keys:
                keep["base." + key] = W.clone()
            done.append(key)
            return Wn.to(torch.float16)

        rewrite_shard(path, {targets[nm]: (lambda nm=nm: merged(nm, targets[nm])) for nm in names})
        del f
        print(f"  {shard}: merged {len(names)} modules", flush=True)
    if len(done) != len(mods) or set(done) != set(targets.values()):
        raise SystemExit(f"merged {len(done)} of {len(mods)} LoRA modules")
    save_file(keep, a.keep)
    json.dump({"lora_modules": len(mods), "merged": len(done), "scale": scale}, open(a.report, "w"))
    print(f"merged {len(done)}/{len(mods)} LoRA modules")


def cmd_check(a):
    """Compares phone-file weights with base and base+delta."""
    import numpy as np
    import torch
    import gguf
    from gguf.quants import dequantize
    from safetensors.torch import load_file
    mods, scale = load_adapter(a.adapter)
    keep = load_file(a.keep)
    rd = gguf.GGUFReader(a.gguf)
    gt = {t.name: t for t in rd.tensors}
    rows, ok = [], True
    for layer, part, gname in CHECKS:
        name = [n for n in mods if n.endswith(f".layers.{layer}.{part}")][0]
        W = keep["base." + name + ".weight"].float()
        delta = (mods[name]["B"].float() @ mods[name]["A"].float()) * scale
        if gname not in gt:
            raise SystemExit(f"phone file has no tensor {gname}")
        t = gt[gname]
        G = torch.from_numpy(np.array(dequantize(t.data, t.tensor_type), dtype=np.float32)).reshape(W.shape)
        res = G - W
        cos = torch.nn.functional.cosine_similarity(res.flatten(), delta.flatten(), dim=0).item()
        d_base, d_merged = res.norm().item(), (res - delta).norm().item()
        if a.exact:      # 16-bit file: must hold exactly the merged float16 values
            expected = (W + delta).to(torch.float16).float()
            good = cos > 0.99 and (G - expected).norm().item() <= 1e-3 * delta.norm().item()
        else:            # 4-bit file: quantization noise is larger than the delta, but uncorrelated with it
            good = cos > 0.05 and d_merged < d_base
        ok &= good
        rows.append({"tensor": gname, "type": t.tensor_type.name, "delta": round(delta.norm().item(), 4),
                     "dist_base": round(d_base, 4), "dist_trained": round(d_merged, 4), "cos": round(cos, 4),
                     "ok": good})
        print(rows[-1], flush=True)
    json.dump({"ok": ok, "rows": rows}, open(a.report, "w"))
    print("weight check:", "PASS" if ok else "FAIL")


# ---------------------------------------------------------------- the build (plain Python, runs the rest)

def first_json(text):
    """The first complete JSON object in text (handles nested braces), or None."""
    i = text.find("{")
    while i != -1:
        try:
            obj, _ = json.JSONDecoder().raw_decode(text[i:])
            if isinstance(obj, dict):
                return obj
        except ValueError:
            pass
        i = text.find("{", i + 1)
    return None


def smoke(simple, gguf_path, tpl):
    T, CMD, CHAT = tpl["template"], tpl["cmd_system"], tpl["chat_system"]
    results, ok = [], 0
    tests = [(False, u, w) for u, w in SMOKE_CMD] + [(True, u, w) for u, w in SMOKE_CHAT]
    for is_chat, user, want in tests:
        p = T.replace("{SYSTEM}", CHAT if is_chat else CMD).replace("{USER}", user)
        r = subprocess.run([simple, "-m", gguf_path, "-n", "120", p], capture_output=True, text=True, timeout=900)
        gen = r.stdout[len(p):] if r.stdout.startswith(p) else r.stdout.split("</think>")[-1]
        if is_chat:
            good = r.returncode == 0 and first_json(gen) is None and want in gen.lower()
            got = "chat text"
        else:
            obj = first_json(gen)
            got = obj.get("action") if obj else None
            good = r.returncode == 0 and got == want
        ok += good
        results.append({"user": user, "want": want, "got": got, "ok": bool(good), "raw": gen.strip()[:300]})
        print(f"{'ok ' if good else 'BAD'} {user!r:40} -> {got} | {gen.strip()[:120]!r}", flush=True)
    return ok / len(tests), results


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def free_disk():
    if os.environ.get("GITHUB_ACTIONS") == "true":
        subprocess.run("sudo rm -rf /usr/share/dotnet /usr/local/lib/android /opt/ghc /opt/hostedtoolcache/CodeQL",
                       shell=True)
    print("free disk: %.0f GB" % (shutil.disk_usage(".").free / 1e9))


def cmd_build(a):
    work = Path(a.work).resolve()
    work.mkdir(parents=True, exist_ok=True)
    free_disk()
    me = str(Path(__file__).resolve())
    cfg, _, _ = adapter_info(a.adapter)
    base = a.base or cfg["base_model_name_or_path"]
    tpl = json.loads(Path(a.template).read_text())

    # Tools: llama.cpp (same version as the app) and a venv with its converter's pinned packages
    lc = work / "llama.cpp"
    if not lc.exists():
        sh(["git", "clone", "-q", "--depth", "1", "--branch", LLAMA_CPP_TAG,
            "https://github.com/ggml-org/llama.cpp", str(lc)])
    sh(f"cmake -S {lc} -B {lc}/build -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=OFF -DLLAMA_BUILD_TESTS=OFF "
       f"-DLLAMA_BUILD_EXAMPLES=ON -DLLAMA_CURL=OFF > /dev/null")
    sh(f"cmake --build {lc}/build --target llama-quantize llama-simple -j {os.cpu_count() or 4} > /dev/null")
    venv = work / "venv"
    py = str(venv / "bin" / "python")
    if not Path(py).exists():
        sh([sys.executable, "-m", "venv", str(venv)])
        sh([py, "-m", "pip", "install", "-q", "--upgrade", "pip"])
        sh([py, "-m", "pip", "install", "-q", "-r", f"{lc}/requirements/requirements-convert_hf_to_gguf.txt"])
        sh([py, "-m", "pip", "install", "-q", f"{lc}/gguf-py"])    # this llama.cpp's own reader, not PyPI's

    # 1. Base model (original 16-bit weights and tokenizer) -> merge the LoRA into it
    model = work / "merged"
    shutil.rmtree(model, ignore_errors=True)
    sh([py, "-c", f"from huggingface_hub import snapshot_download as s; s({base!r}, local_dir={str(model)!r})"])
    keep, merge_rep = work / "check_base.safetensors", work / "merge.json"
    sh([py, me, "merge", "--adapter", a.adapter, "--model", str(model), "--keep", str(keep),
        "--report", str(merge_rep)])

    # 2. Convert to a 16-bit phone file and prove it holds base + delta
    f16 = work / "brain-f16.gguf"
    sh([py, f"{lc}/convert_hf_to_gguf.py", str(model), "--outfile", str(f16), "--outtype", "f16", "--no-mtp"])
    shutil.rmtree(model, ignore_errors=True)
    chk16 = work / "check_f16.json"
    sh([py, me, "check", "--adapter", a.adapter, "--keep", str(keep), "--gguf", str(f16), "--exact",
        "--report", str(chk16)])

    # 3. 4-bit for the phone, check again, then a smoke test with the exact phone prompt
    out = work / "topshe_brain.gguf"
    sh([f"{lc}/build/bin/llama-quantize", str(f16), str(out), QUANT])
    f16.unlink()
    chk4 = work / "check_q4.json"
    sh([py, me, "check", "--adapter", a.adapter, "--keep", str(keep), "--gguf", str(out), "--report", str(chk4)])
    w16, w4 = json.loads(chk16.read_text()), json.loads(chk4.read_text())
    score, results = smoke(f"{lc}/build/bin/llama-simple", str(out), tpl)
    report = {
        "weight_check": bool(w16["ok"] and w4["ok"]), "weight_check_f16": w16, "weight_check_q4": w4,
        "merge": json.loads(merge_rep.read_text()), "gguf_smoke": score, "gguf_smoke_detail": results,
        "gguf_bytes": out.stat().st_size, "gguf_sha256": sha256(out), "llama_cpp": LLAMA_CPP_TAG,
        "quant": QUANT, "base_model": base,
    }
    (work / "export.json").write_text(json.dumps(report, indent=1, ensure_ascii=False))
    print(f"phone file {out.stat().st_size / 1e9:.2f} GB | weight check "
          f"{'PASS' if report['weight_check'] else 'FAIL'} | smoke {score:.2f}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--adapter", required=True)
    b.add_argument("--template", required=True)
    b.add_argument("--work", required=True)
    b.add_argument("--base", default=None)
    m = sub.add_parser("merge")
    for x in ("--adapter", "--model", "--keep", "--report"):
        m.add_argument(x, required=True)
    c = sub.add_parser("check")
    for x in ("--adapter", "--keep", "--gguf", "--report"):
        c.add_argument(x, required=True)
    c.add_argument("--exact", action="store_true")
    args = ap.parse_args()
    {"build": cmd_build, "merge": cmd_merge, "check": cmd_check}[args.cmd](args)
