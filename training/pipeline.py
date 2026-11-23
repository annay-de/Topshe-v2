"""
Topshe's weekly self-retraining, run by GitHub Actions (.github/workflows/retrain.yml).

  python training/pipeline.py start [command|chat]     # pack your phone data, start training on Kaggle
  python training/pipeline.py collect                  # every few hours: test finished runs and publish
  python training/pipeline.py reexport [command|chat]  # rebuild + test the phone file from the latest Kaggle output

Two brains: "command" (Qwen 3.5 0.8B, every request, retrained weekly on your usage; releases model-N)
and "chat" (Qwen 3.5 2B, answers questions; trained when needed; releases chat-N).

The phone uploads its interaction log + facts to the `data` branch (checked out at ./databranch).
A new brain is only published if it passes the quality gate - it must not be worse than the
current one - so a bad week of data can never make Topshe dumber.

Kaggle trains and tests the model (GPU). The phone file is built here, on GitHub's machine
(training/export_brain.py): merge the LoRA, convert, 4-bit, then prove the training is inside it.
"""
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "databranch"
BUILD = ROOT / "build"
HISTORY = DATA / "retrain" / "history.jsonl"
USER = os.environ.get("KAGGLE_USERNAME", "").strip().lower()
DATASET = f"{USER}/topshe-data"
ROLES = ("command", "chat")
ROLE = "command"                  # set from the command line
KERNELS = {"command": f"{USER}/topshe-trainer", "chat": f"{USER}/topshe-chat-trainer"}
NOTEBOOKS = {"command": "topshe_trainer.ipynb", "chat": "topshe_chat_trainer.ipynb"}
TAGS = {"command": "model", "chat": "chat"}            # release names: model-3, chat-1
FILES = {"command": "topshe_brain", "chat": "topshe_chat"}


def kernel():
    return KERNELS[ROLE]


def pending_file():
    return DATA / "retrain" / ("pending.json" if ROLE == "command" else f"pending_{ROLE}.json")
REPO = os.environ.get("GITHUB_REPOSITORY", "annay-de/topshe-v2")
PART_BYTES = 1900 * 1024 * 1024          # GitHub release files must be < 2 GB

# Quality gate
MIN_VALID = 0.98            # share of replies that are valid JSON
MIN_EXACT = 0.90            # share of held-back commands fully right
MAX_DROP = 0.02             # allowed drop vs the current brain (noise margin)
MIN_SMOKE = 0.85            # share of phone-file test commands right
MIN_BENGALI = 0.85          # share of held-back Bengali / Banglish commands fully right
MIN_HEARD = 0.70            # share of real two-model hearings (held-back pool) fully right


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def run(cmd, check=True, capture=False):
    print("$", " ".join(cmd) if isinstance(cmd, list) else cmd, flush=True)
    r = subprocess.run(cmd, check=False, text=True, capture_output=True)
    if r.stdout:
        print(r.stdout[-3000:])
    if r.stderr:
        print(r.stderr[-3000:], file=sys.stderr)
    if check and r.returncode != 0:
        raise SystemExit(f"command failed ({r.returncode}): {cmd}")
    return r


def kaggle(*args, check=True):
    return run(["kaggle", *args], check=check)


def load_pending():
    f = pending_file()
    return json.loads(f.read_text()) if f.exists() else None


def save_pending(p):
    f = pending_file()
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps({**p, "role": ROLE}, indent=1))


def log_history(entry):
    HISTORY.parent.mkdir(parents=True, exist_ok=True)
    with open(HISTORY, "a") as f:
        f.write(json.dumps({**entry, "role": ROLE}) + "\n")


# ---------------------------------------------------------------- start

def start():
    if not USER:
        raise SystemExit("KAGGLE_USERNAME secret is missing")
    p = load_pending()
    if p and p.get("status") == "training":
        age_h = (time.time() - p.get("started_ts", 0)) / 3600
        if age_h < 16:
            print(f"A training run is already in progress ({age_h:.1f} h). Nothing to do.")
            return
    # 1) pack phone data
    ds = BUILD / "dataset"
    shutil.rmtree(ds, ignore_errors=True)
    ds.mkdir(parents=True)
    lines = []
    for f in sorted(glob.glob(str(DATA / "data" / "interactions" / "*.jsonl"))):
        lines += [l for l in Path(f).read_text(encoding="utf-8").splitlines() if l.strip()]
    (ds / "user_interactions.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    past = DATA / "data" / "claude_import" / "claude_messages.jsonl"
    if past.exists():
        shutil.copy(past, ds / "claude_messages.jsonl")
    distill = DATA / "data" / "chat_distill.jsonl"          # general chat made once by the base model
    if distill.exists():
        shutil.copy(distill, ds / "chat_distill.jsonl")
    pool = DATA / "data" / "hearing_pool.jsonl"              # what both phone speech models really hear
    if pool.exists():
        shutil.copy(pool, ds / "hearing_pool.jsonl")
    prev = previous_manifest()                                # last week's scores -> automatic re-weighting
    if prev and prev.get("eval"):
        (ds / "prev_eval.json").write_text(json.dumps(prev["eval"]))
    facts = DATA / "data" / "facts.json"
    (ds / "facts.json").write_text(facts.read_text(encoding="utf-8") if facts.exists() else '{"facts": []}', encoding="utf-8")
    (ds / "dataset-metadata.json").write_text(json.dumps(
        {"title": "topshe-data", "id": DATASET, "licenses": [{"name": "CC0-1.0"}]}))
    print(f"Packed {len(lines)} interactions")

    # 2) upload as a private Kaggle dataset (new version each week)
    r = kaggle("datasets", "version", "-p", str(ds), "-m", f"week of {now()[:10]}", check=False)
    if r.returncode != 0:
        kaggle("datasets", "create", "-p", str(ds))          # first time (private by default)
    for _ in range(40):
        s = kaggle("datasets", "status", DATASET, check=False).stdout.lower()
        if "ready" in s:
            break
        time.sleep(15)

    # 3) push the trainer notebook; pushing starts a run on a Kaggle GPU
    run([sys.executable, str(ROOT / "training" / "build_notebook.py"), ROLE])
    kd = BUILD / "kernel"
    shutil.rmtree(kd, ignore_errors=True)
    kd.mkdir(parents=True)
    slug = kernel().split("/")[1]
    shutil.copy(ROOT / "training" / NOTEBOOKS[ROLE], kd / f"{slug}.ipynb")
    (kd / "kernel-metadata.json").write_text(json.dumps({
        "id": kernel(), "title": slug, "code_file": f"{slug}.ipynb",
        "language": "python", "kernel_type": "notebook", "is_private": True,
        "enable_gpu": True, "enable_internet": True, "enable_tpu": False,
        "dataset_sources": [DATASET], "competition_sources": [], "kernel_sources": [], "model_sources": [],
    }, indent=1))
    r = kaggle("kernels", "push", "-p", str(kd), "--accelerator", "NvidiaTeslaT4", check=False)
    if r.returncode != 0:
        kaggle("kernels", "push", "-p", str(kd))
    save_pending({"status": "training", "started": now(), "started_ts": time.time(),
                  "kernel": kernel(), "interactions": len(lines)})
    print(f"Training the {ROLE} brain started on Kaggle")


# ---------------------------------------------------------------- collect

def kernel_status():
    out = kaggle("kernels", "status", kernel(), check=False).stdout.lower()
    for s in ("complete", "error", "cancel", "running", "queued"):
        if s in out:
            return s
    return "unknown"


def save_log():
    """Keeps the latest Kaggle log in the data branch (retrain/last_run.log) for debugging."""
    r = kaggle("kernels", "logs", kernel(), check=False)
    text = (r.stdout or "") + (r.stderr or "")
    (DATA / "retrain").mkdir(parents=True, exist_ok=True)
    (DATA / "retrain" / ("last_run.log" if ROLE == "command" else f"last_run_{ROLE}.log")).write_text(text[-200_000:])


def previous_manifest():
    r = run(["gh", "release", "list", "-R", REPO, "--limit", "100", "--json", "tagName"], check=False)
    tags = [t["tagName"] for t in json.loads(r.stdout or "[]") if t["tagName"].startswith(TAGS[ROLE] + "-")]
    if not tags:
        return None
    latest = max(tags, key=lambda t: int(t.split("-")[1]))
    d = BUILD / "prev"
    shutil.rmtree(d, ignore_errors=True)
    run(["gh", "release", "download", latest, "-R", REPO, "-p", "manifest.json", "-D", str(d)], check=False)
    f = d / "manifest.json"
    return json.loads(f.read_text()) if f.exists() else None


def gate(ev, prev):
    """Returns the reasons to reject a new brain (empty list = publish)."""
    if prev and (prev.get("eval") or {}).get("base_model") != ev.get("base_model"):
        prev = None            # a different brain size: judged on the fixed bars only, not against the old one
    if ROLE == "chat":
        return gate_chat(ev, prev)
    s = ev.get("synthetic") or {}
    reasons = []
    if s.get("valid", 0) < MIN_VALID:
        reasons.append(f"valid JSON {s.get('valid', 0):.2f} < {MIN_VALID}")
    if s.get("exact", 0) < MIN_EXACT:
        reasons.append(f"command accuracy {s.get('exact', 0):.2f} < {MIN_EXACT}")
    if "gguf_smoke" in ev and not ev.get("weight_check", False):
        reasons.append("the phone file doesn't contain the training (weight check failed)")
    if "gguf_smoke" in ev and ev["gguf_smoke"] < MIN_SMOKE:
        reasons.append(f"the phone file failed its smoke test ({ev['gguf_smoke']:.2f})")
    if not ev.get("chat_ok", False):
        reasons.append("chat answers looked broken")
    if ev.get("style_routing") is not None and ev["style_routing"] < 0.85:
        reasons.append(f"mistook your normal talk for commands ({ev['style_routing']:.2f})")
    hd = ev.get("heard") or {}
    if hd and hd.get("exact", 0) < MIN_HEARD:
        reasons.append(f"accuracy on real speech-model hearings {hd.get('exact', 0):.2f} < {MIN_HEARD}")
    bn = ev.get("bengali") or {}
    if bn and bn.get("exact", 0) < MIN_BENGALI:
        reasons.append(f"Bengali command accuracy {bn.get('exact', 0):.2f} < {MIN_BENGALI}")
    if ev.get("memory_chat", 0) < 0.5:
        reasons.append(f"didn't use what it was told to remember ({ev.get('memory_chat', 0):.2f})")
    if prev:
        pe = prev.get("eval", {})
        if s.get("exact", 0) < (pe.get("synthetic") or {}).get("exact", 0) - MAX_DROP:
            reasons.append("worse than the current brain on test commands")
        y, py = ev.get("yours"), pe.get("yours")
        if y and py and y.get("n", 0) >= 10 and y.get("exact", 0) < py.get("exact", 0) - MAX_DROP:
            reasons.append("worse than the current brain on your own commands")
        if ev.get("memory_chat", 0) < pe.get("memory_chat", 0) - 0.25:
            reasons.append("worse than the current brain at using memory")
    return reasons


def gate_chat(ev, prev):
    reasons = []
    if "gguf_smoke" in ev and not ev.get("weight_check", False):
        reasons.append("the phone file doesn't contain the training (weight check failed)")
    if "gguf_smoke" in ev and ev["gguf_smoke"] < 0.66:
        reasons.append(f"the phone file failed its chat test ({ev['gguf_smoke']:.2f})")
    if not ev.get("chat_ok", False):
        reasons.append("chat answers looked broken")
    if not ev.get("chat_identity", False):
        reasons.append("didn't say it's Topshe")
    if not ev.get("chat_bengali", False):
        reasons.append("didn't answer a Bengali question in Bengali")
    if ev.get("memory_chat", 0) < 0.5:
        reasons.append(f"didn't use what it was told to remember ({ev.get('memory_chat', 0):.2f})")
    if prev and ev.get("memory_chat", 0) < (prev.get("eval") or {}).get("memory_chat", 0) - 0.25:
        reasons.append("worse than the current chat brain at using memory")
    return reasons


KEEP = ("role", "chat_identity", "chat_bengali", "synthetic", "bengali", "heard", "yours", "style_routing", "memory_commands", "memory_chat", "chat_samples", "chat_ok",
        "gguf_smoke", "weight_check", "train_rows", "your_interactions", "base_model", "llama_cpp", "quant",
        "loss_last")


def summarise(ev):
    """eval.json -> what goes in the release manifest (drops the long lists of misses)."""
    out = {}
    for k in KEEP:
        v = ev.get(k)
        if isinstance(v, dict) and "exact" in v:
            v = {x: v[x] for x in ("n", "valid", "action", "exact", "per_action") if x in v}
        out[k] = v
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def collect():
    p = load_pending()
    if not p or p.get("status") != "training":
        print("No training in progress.")
        return
    st = kernel_status()
    print("Kaggle status:", st)
    if st in ("running", "queued", "unknown"):
        save_log()
        if time.time() - p["started_ts"] > 16 * 3600:
            p.update(status="failed", reason="timed out", ended=now())
            save_pending(p); log_history(p)
        return
    if st in ("error", "cancel"):
        save_log()
        p.update(status="failed", reason=f"Kaggle run {st} (see retrain/last_run.log)", ended=now())
        save_pending(p); log_history(p)
        return

    finish(p)


def reexport():
    """Rebuilds and re-tests the phone file from the latest finished Kaggle run (no new training)."""
    p = load_pending() or {"started": now(), "started_ts": time.time()}
    st = kernel_status()
    print("Kaggle status:", st)
    if st != "complete":
        print("The latest Kaggle run isn't complete; nothing to re-export.")
        return
    p.update(status="training", reexport=now())
    finish(p)


def fail(p, reason):
    print("FAILED:", reason)
    p.update(status="failed", reason=reason, ended=now())
    save_pending(p); log_history(p)


def finish(p):
    out = BUILD / "out"
    shutil.rmtree(out, ignore_errors=True)
    kaggle("kernels", "output", kernel(), "-p", str(out))
    save_log()
    evf = next(iter(glob.glob(str(out / "**" / "eval.json"), recursive=True)), None)
    tpl = next(iter(glob.glob(str(out / "**" / "prompt_template.json"), recursive=True)), None)
    ad = next(iter(glob.glob(str(out / "**" / "adapter_config.json"), recursive=True)), None)
    if not (evf and tpl and ad):
        return fail(p, "training finished but files are missing")
    ev = json.loads(Path(evf).read_text())
    for k in ("gguf_smoke", "weight_check", "gguf_bytes", "gguf_sha256"):   # never trust an old phone-file score
        ev.pop(k, None)
    distill = next(iter(glob.glob(str(out / "**" / "chat_distill.jsonl"), recursive=True)), None)
    if distill:                                   # keep it for next week (saves ~45 GPU minutes)
        (DATA / "data").mkdir(parents=True, exist_ok=True)
        shutil.copy(distill, DATA / "data" / "chat_distill.jsonl")
    prev = previous_manifest()

    def scores():
        sm = {k: (ev.get(k) or {}).get("exact") for k in ("synthetic", "bengali", "heard", "yours")}
        sm.update({k: ev.get(k) for k in ("style_routing", "memory_chat", "gguf_smoke", "weight_check")})
        return sm

    reasons = gate(ev, prev)                      # the model's own test scores first (cheap)
    if not reasons:                               # then build the phone file and test that
        work = BUILD / "export"
        shutil.rmtree(work, ignore_errors=True)
        r = subprocess.run([sys.executable, str(ROOT / "training" / "export_brain.py"), "build", "--adapter",
                            str(Path(ad).parent), "--template", tpl, "--work", str(work), "--role", ROLE])   # streams its log
        rep = work / "export.json"
        if r.returncode != 0 or not rep.exists():
            p.update(scores=scores())
            return fail(p, "building the phone file failed (see the GitHub Actions log)")
        ev.update(json.loads(rep.read_text()))
        reasons = gate(ev, prev)
    if reasons:
        print("Quality gate FAILED:", reasons)
        p.update(status="rejected", reasons=reasons, scores=scores(), ended=now())
        save_pending(p); log_history(p)
        return
    gguf = str(BUILD / "export" / "topshe_brain.gguf")
    summary = scores()

    version = (prev or {}).get("version", 0) + 1
    rel = BUILD / "release"
    shutil.rmtree(rel, ignore_errors=True)
    rel.mkdir(parents=True)
    full_sha = sha256(gguf)
    parts = []
    with open(gguf, "rb") as f:
        i = 0
        while True:
            chunk = f.read(PART_BYTES)
            if not chunk:
                break
            name = f"{FILES[ROLE]}_v{version}.gguf.part{i:02d}"
            (rel / name).write_bytes(chunk)
            parts.append({"name": name, "bytes": len(chunk), "sha256": hashlib.sha256(chunk).hexdigest()})
            i += 1
    manifest = {
        "version": version, "role": ROLE, "created": now(), "sha256": full_sha, "bytes": os.path.getsize(gguf),
        "parts": parts, "prompt": json.loads(Path(tpl).read_text()), "eval": summarise(ev),
    }
    (rel / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    size = ev.get("base_model", "").split("-")[-1]
    if ROLE == "chat":
        notes = f"Chat brain v{version} ({size}). Uses memory: {ev.get('memory_chat', 0):.0%}."
        title = f"Topshe chat brain v{version}"
    else:
        notes = (f"Brain v{version} ({size}). Command accuracy: {summary['synthetic']:.1%}"
                 + (f", your commands: {summary['yours']:.1%}" if summary.get("yours") is not None else "")
                 + f". Trained on {ev.get('your_interactions', 0)} of your interactions.")
        title = f"Topshe brain v{version}"
    tag = f"{TAGS[ROLE]}-{version}"
    run(["gh", "release", "create", tag, "-R", REPO, "--title", title,
         "--notes", notes, "--latest=false", *[str(x) for x in sorted(rel.iterdir())]])
    p.update(status="published", version=version, scores=summary, ended=now())
    save_pending(p); log_history(p)
    print("Published", tag)


if __name__ == "__main__":
    if sys.argv[1] == "collect" and len(sys.argv) < 3:       # the schedule: check both brains
        for ROLE in ROLES:
            print(f"===== {ROLE} brain =====")
            collect()
    else:
        ROLE = sys.argv[2] if len(sys.argv) > 2 else "command"
        assert ROLE in ROLES, ROLE
        {"start": start, "collect": collect, "reexport": reexport}[sys.argv[1]]()
