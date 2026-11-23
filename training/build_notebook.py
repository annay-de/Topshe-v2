"""
Builds training/topshe_trainer.ipynb from plain Python so it's easy to maintain.
The weekly pipeline runs this before pushing to Kaggle; you can also import the
.ipynb into Kaggle by hand and press Run All.

    python training/build_notebook.py
"""
import json
from pathlib import Path

HERE = Path(__file__).parent
BASE_MODEL = "unsloth/Qwen3.5-4B"
LLAMA_CPP_TAG = "b12602"   # must match android/app/src/main/cpp/CMakeLists.txt

cells = []


def md(s):
    cells.append({"cell_type": "markdown", "metadata": {}, "source": s})


def code(s):
    cells.append({"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [], "source": s})


md(f"""# Topshe trainer (Qwen 3.5 4B)

Used for the first training run **and** every weekly retrain (the GitHub pipeline pushes it here automatically).

- Accelerator: **GPU T4** (one GPU is used). Internet: **On**.
- If a dataset called `topshe-data` is attached, your real usage and corrections are added to training.
- Outputs: `topshe_brain.gguf` (the phone model), `prompt_template.json`, `eval.json` (test scores), `lora/` (backup).
""")

code("""# 1. Setup
import os, time, json, glob, random, shutil, subprocess, hashlib
os.environ["CUDA_VISIBLE_DEVICES"] = "0"
os.environ["WANDB_DISABLED"] = "true"
T0 = time.time()
!pip install -q --upgrade unsloth
!nvidia-smi --query-gpu=name,memory.total --format=csv
OUT = "/kaggle/working"
TMP = "/tmp/topshe"
os.makedirs(TMP, exist_ok=True)
""")

code(f"""# 2. Settings
BASE_MODEL = "{BASE_MODEL}"
LLAMA_CPP_TAG = "{LLAMA_CPP_TAG}"
MAX_LEN = 1024
TRAIN_HOURS_LIMIT = 3.5        # training stops safely after this long
USER_UPSAMPLE = 3              # your real examples count 3x
""")

gen_src = (HERE / "make_dataset_v3.py").read_text().split('if __name__ == "__main__":')[0]
code("# 3. Training data generator (training/make_dataset_v3.py)\n" + gen_src)

code("""# 4. Build the dataset: synthetic + your real usage (if attached) + general chat
from datasets import load_dataset

synthetic = make_rows(seed=int(time.time()) % 100000)   # fresh phrasings every week
random.seed(7)
eval_synth = [r for r in synthetic if r["kind"] == "command"][:200]
eval_keys = {r["user"] for r in eval_synth}
train = [r for r in synthetic if r["user"] not in eval_keys]

# Your data (from the phone, via the weekly pipeline)
user_dir = next(iter(glob.glob("/kaggle/input/**/user_interactions.jsonl", recursive=True)), None)
interactions, facts = [], []
if user_dir:
    interactions = [json.loads(l) for l in open(user_dir, encoding="utf-8") if l.strip()]
    fp = os.path.join(os.path.dirname(user_dir), "facts.json")
    if os.path.exists(fp):
        facts = json.load(open(fp, encoding="utf-8")).get("facts", [])
mine = user_rows(interactions, facts)
# Hold back ~15% of your examples to test on (chosen by a stable hash, so the split doesn't move)
def held(r): return int(hashlib.md5(r["user"].encode()).hexdigest(), 16) % 100 < 15
eval_user = [r for r in mine if held(r)]
train += [r for r in mine if not held(r)] * USER_UPSAMPLE

# Your own past messages from your Claude export (style only; Claude's replies are never used)
style_file = next(iter(glob.glob("/kaggle/input/**/claude_messages.jsonl", recursive=True)), None)
style = []
if style_file:
    msgs = [json.loads(l) for l in open(style_file, encoding="utf-8") if l.strip()]
    style = style_rows(msgs, facts, k=1500, seed=int(time.time()) % 1000)
    train += style

# General chat so Topshe can still talk normally (some with a memory block it should ignore)
alpaca = load_dataset("yahma/alpaca-cleaned", split="train").shuffle(seed=int(time.time()) % 1000)
n_chat = 0
for r in alpaca:
    if r["input"] or not (5 <= len(r["output"]) <= 350):
        continue
    sys_prompt = with_facts(CHAT_SYSTEM, random_facts(4) if random.random() < 0.3 else [])
    train.append(row(sys_prompt, r["instruction"], r["output"], "chat"))
    n_chat += 1
    if n_chat >= 2500:
        break
random.shuffle(train)
counts = {k: sum(1 for r in train if r["kind"] == k) for k in sorted({r["kind"] for r in train})}
print("train:", len(train), counts, "| eval synthetic:", len(eval_synth), "| eval yours:", len(eval_user),
      "| your interactions:", len(interactions), "| facts:", len(facts), "| your past messages:", len(style))
""")

code("""# 5. Load the base model (16-bit LoRA: Unsloth advises against 4-bit training for Qwen 3.5)
from unsloth import FastLanguageModel
import torch

kw = dict(model_name=BASE_MODEL, max_seq_length=MAX_LEN, load_in_4bit=False)
try:
    model, tokenizer = FastLanguageModel.from_pretrained(load_in_16bit=True, full_finetuning=False, **kw)
except TypeError:
    model, tokenizer = FastLanguageModel.from_pretrained(**kw)
model = FastLanguageModel.get_peft_model(
    model, r=16, lora_alpha=16, lora_dropout=0, bias="none",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    use_gradient_checkpointing="unsloth", random_state=3407,
)
tok = getattr(tokenizer, "tokenizer", tokenizer)   # multimodal models wrap the text tokenizer
""")

code("""# 6. One prompt format for training AND the phone (saved as prompt_template.json)
def render_prompt(system, user):
    msgs = [{"role": "system", "content": system}, {"role": "user", "content": user}]
    last = None
    for obj in (tok, tokenizer):            # text tokenizer first, then the multimodal processor
        for extra in ({"enable_thinking": False}, {}):
            try:
                out = obj.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, **extra)
                if isinstance(out, str) and ("{USER}" in out or user != "{USER}"):
                    return out
            except Exception as e:
                last = e
    raise RuntimeError(f"No chat template found: {last}")

TEMPLATE = render_prompt("{SYSTEM}", "{USER}")
assert TEMPLATE.count("{SYSTEM}") == 1 and TEMPLATE.count("{USER}") == 1, TEMPLATE
END = "<|im_end|>" if "<|im_end|>" in TEMPLATE else (tok.eos_token or "")
json.dump({"template": TEMPLATE, "end": END, "base_model": BASE_MODEL,
           "cmd_system": CMD_SYSTEM, "chat_system": CHAT_SYSTEM, "facts_header": FACTS_HEADER},
          open(f"{OUT}/prompt_template.json", "w"), ensure_ascii=False, indent=1)
print(repr(TEMPLATE))

def encode(r):
    p = TEMPLATE.replace("{SYSTEM}", r["system"]).replace("{USER}", r["user"])
    p_ids = tok(p, add_special_tokens=False)["input_ids"]
    a_ids = tok(r["assistant"] + END + "\\n", add_special_tokens=False)["input_ids"]
    ids = (p_ids + a_ids)[:MAX_LEN]
    labels = ([-100] * len(p_ids) + a_ids)[:MAX_LEN]   # learn only Topshe's replies
    return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": labels}

from datasets import Dataset
ds = Dataset.from_list([encode(r) for r in train])
print(ds)
""")

code("""# 7. Train (stops safely if it runs too long)
from transformers import Trainer, TrainingArguments, DataCollatorForSeq2Seq, TrainerCallback

class TimeLimit(TrainerCallback):
    def on_step_end(self, args, state, control, **kw):
        if time.time() - T0 > TRAIN_HOURS_LIMIT * 3600:
            print("Time limit reached - stopping training early (model is still saved)")
            control.should_training_stop = True

bf16 = torch.cuda.is_bf16_supported()
args = TrainingArguments(
    output_dir=f"{TMP}/ckpt", per_device_train_batch_size=4, gradient_accumulation_steps=4,
    num_train_epochs=1, learning_rate=2e-4, warmup_steps=20, lr_scheduler_type="linear",
    logging_steps=25, optim="adamw_8bit", weight_decay=0.01, seed=3407,
    fp16=not bf16, bf16=bf16, save_strategy="no", report_to="none",
    group_by_length=True, remove_unused_columns=False,
)
trainer = Trainer(model=model, args=args, train_dataset=ds,
                  data_collator=DataCollatorForSeq2Seq(tok, padding=True, label_pad_token_id=-100),
                  callbacks=[TimeLimit()])
stats = trainer.train()
model.save_pretrained(f"{OUT}/lora"); tok.save_pretrained(f"{OUT}/lora")
print("Training done in %.0f min" % ((time.time() - T0) / 60))
""")

code("""# 8. Test: held-back synthetic commands + held-back real commands of yours
FastLanguageModel.for_inference(model)

def generate(system, user, max_new=96):
    p = TEMPLATE.replace("{SYSTEM}", system).replace("{USER}", user)
    ids = tok(p, add_special_tokens=False, return_tensors="pt").input_ids.to("cuda")
    out = model.generate(input_ids=ids, max_new_tokens=max_new, do_sample=False,
                         pad_token_id=tok.pad_token_id or tok.eos_token_id)
    return tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True).strip()

def score(rows):
    valid = action = exact = 0
    misses = []
    for r in rows:
        truth = json.loads(r["assistant"])
        raw = generate(r["system"], r["user"])
        try:
            pred = json.loads(raw[raw.index("{"): raw.rindex("}") + 1]); valid += 1
        except Exception:
            misses.append((r["user"], raw)); continue
        action += pred.get("action") == truth.get("action")
        # chat queries may differ only in punctuation/case: count as exact
        same = pred == truth or (truth.get("action") == "chat" and pred.get("action") == "chat")
        exact += same
        if not same: misses.append((r["user"], raw))
    n = max(len(rows), 1)
    return {"n": len(rows), "valid": valid / n, "action": action / n, "exact": exact / n, "misses": misses[:20]}

ev = {"synthetic": score(eval_synth), "yours": score(eval_user) if eval_user else None}
ev["chat_sample"] = generate(with_facts(CHAT_SYSTEM, ["my shuttle leaves at 8:10 in the morning"]), "when is my shuttle?", 60)
ev["general_sample"] = generate(CHAT_SYSTEM, "who directed pather panchali?", 60)
ev["train_rows"] = len(train); ev["your_interactions"] = len(interactions); ev["base_model"] = BASE_MODEL
ev["minutes"] = round((time.time() - T0) / 60)
json.dump(ev, open(f"{OUT}/eval.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps({k: (v if k not in ("synthetic", "yours") or v is None else {x: v[x] for x in ("n", "valid", "action", "exact")}) for k, v in ev.items()}, indent=1))
""")

code("""# 9. Export for the phone: merge -> GGUF (same llama.cpp version as the app) -> 4-bit Q4_K_M
model.save_pretrained_merged(f"{TMP}/merged", tokenizer, save_method="merged_16bit")
del model, trainer; torch.cuda.empty_cache()

def sh(cmd):
    print("$", cmd); subprocess.run(cmd, shell=True, check=True)

sh(f"git clone --depth 1 --branch {LLAMA_CPP_TAG} https://github.com/ggml-org/llama.cpp {TMP}/llama.cpp")
sh(f"pip install -q -r {TMP}/llama.cpp/requirements/requirements-convert_hf_to_gguf.txt")
sh(f"python {TMP}/llama.cpp/convert_hf_to_gguf.py {TMP}/merged --outfile {TMP}/brain-f16.gguf --outtype f16")
sh(f"cmake -S {TMP}/llama.cpp -B {TMP}/build -DLLAMA_CURL=OFF -DGGML_CUDA=OFF -DLLAMA_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Release > /dev/null")
sh(f"cmake --build {TMP}/build --target llama-quantize -j 4 > /dev/null")
q = glob.glob(f"{TMP}/build/bin/llama-quantize")[0]
sh(f"{q} {TMP}/brain-f16.gguf {OUT}/topshe_brain.gguf Q4_K_M")
size = os.path.getsize(f"{OUT}/topshe_brain.gguf")
sha = hashlib.sha256(open(f"{OUT}/topshe_brain.gguf", "rb").read()).hexdigest()
ev.update({"gguf_bytes": size, "gguf_sha256": sha, "llama_cpp": LLAMA_CPP_TAG})
json.dump(ev, open(f"{OUT}/eval.json", "w"), ensure_ascii=False, indent=1)
print("topshe_brain.gguf: %.2f GB, sha256 %s" % (size / 1e9, sha[:16]))
""")

code("""# 10. Done
for p in sorted(glob.glob(f"{OUT}/*")):
    print(os.path.basename(p))
print("Total %.0f min" % ((time.time() - T0) / 60))
""")

nb = {"cells": cells, "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3", "language": "python"},
                                    "language_info": {"name": "python"}},
      "nbformat": 4, "nbformat_minor": 5}
out = HERE / "topshe_trainer.ipynb"
out.write_text(json.dumps(nb, indent=1))
print("wrote", out)
