"""
Builds training/topshe_trainer.ipynb from plain Python so it's easy to maintain.
The weekly pipeline runs this before pushing to Kaggle; you can also import the
.ipynb into Kaggle by hand and press Run All.

    python training/build_notebook.py

Verified against the actual sources (Nov 2026):
  - unsloth 2026.11.9: Qwen 3.5 loads through FastModel (it's a vision-language model);
    needs transformers >= 5.2.0; 16-bit LoRA (Unsloth advises against 4-bit for Qwen 3.5)
  - the phone file is NOT built here any more: GitHub Actions builds it from lora/ with
    training/export_brain.py and checks the training is really inside it. (Unsloth's
    save_pretrained_merged wrote untouched base weights for Qwen 3.5 in the first run.)
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).parent
# Two brains (Nov 2026): the 4B was far too slow on the phone's 2 fast cores (~3 words/s).
#   command: Qwen 3.5 0.8B - every request goes through it, so it must be fast (~4x the 4B)
#   chat:    Qwen 3.5 2B   - answers questions and small talk (~2x the 4B, much smarter than 0.8B)
ROLE = sys.argv[1] if len(sys.argv) > 1 else "command"
BASES = {"command": "unsloth/Qwen3.5-0.8B", "chat": "unsloth/Qwen3.5-2B"}
BASE_MODEL = BASES[ROLE]
TEACHER = "unsloth/Qwen3.5-4B"    # wrote the cached chat answers (chat_distill.jsonl); small brains learn from it
EPOCHS = {"command": 2, "chat": 3}[ROLE]
LORA_R = {"command": 32, "chat": 16}[ROLE]
NOTEBOOK = {"command": "topshe_trainer.ipynb", "chat": "topshe_chat_trainer.ipynb"}[ROLE]
LLAMA_CPP_TAG = "b12602"          # must match android/app/src/main/cpp/CMakeLists.txt
UNSLOTH_VERSION = "2026.11.9"     # pinned: the version these steps were checked against

cells = []


def md(s):
    cells.append({"cell_type": "markdown", "metadata": {}, "source": s})


def code(s):
    cells.append({"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [], "source": s})


md(f"""# Topshe trainer - {ROLE} brain ({BASE_MODEL.split("/")[-1]})

Used for the first training run **and** every weekly retrain (the GitHub pipeline pushes it here automatically).

- Accelerator: **GPU T4 x2** (one GPU is used). Internet: **On**.
- If the `topshe-data` dataset is attached: your real usage, corrections, past messages and last week's scores are used.
- Outputs: `lora/` (the training), `prompt_template.json`, `eval.json` (test scores),
  `chat_distill.jsonl` (reused next week). GitHub then builds and tests the phone file from `lora/`.
""")

code(f"""# 1. Setup (pinned versions)
import os, time, json, glob, random, shutil, subprocess, hashlib, math
os.environ["CUDA_VISIBLE_DEVICES"] = "0"
os.environ["WANDB_DISABLED"] = "true"
T0 = time.time()
!pip install -q "unsloth=={UNSLOTH_VERSION}" "unsloth_zoo=={UNSLOTH_VERSION}" "transformers>=5.2.0,<=5.17.0"
!nvidia-smi --query-gpu=name,memory.total --format=csv
OUT = "/kaggle/working"
TMP = "/tmp/topshe"
os.makedirs(TMP, exist_ok=True)

def sh(cmd):
    print("$", cmd)
    subprocess.run(cmd, shell=True, check=True)
""")

code(f"""# 2. Settings
ROLE = "{ROLE}"                # command: turns your words into actions (+ basic chat); chat: answers questions
BASE_MODEL = "{BASE_MODEL}"
TEACHER = "{TEACHER}"
EPOCHS = {EPOCHS}
LORA_R = {LORA_R}
LLAMA_CPP_TAG = "{LLAMA_CPP_TAG}"
MAX_LEN = 1024
TRAIN_HOURS_LIMIT = 3.5        # training stops safely after this long
N_DISTILL = 600                # general chat examples (made once, reused every week)
N_STYLE = 1500                 # your past messages used per run
""")

gen_src = (HERE / "make_dataset_v4.py").read_text().split('if __name__ == "__main__":')[0]
code("# 3. Training data generator (training/make_dataset_v4.py: English + Bengali/Banglish)\n" + gen_src)

code("""# 4. Inputs from the topshe-data dataset (all optional)
def find(name):
    return next(iter(glob.glob(f"/kaggle/input/**/{name}", recursive=True)), None)

def read_jsonl(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()] if path else []

interactions = read_jsonl(find("user_interactions.jsonl"))
facts = json.load(open(find("facts.json"))).get("facts", []) if find("facts.json") else []
past_messages = read_jsonl(find("claude_messages.jsonl"))
distill_cached = [r for r in read_jsonl(find("chat_distill.jsonl")) if r.get("base_model") in (BASE_MODEL, TEACHER)]
prev_eval = json.load(open(find("prev_eval.json"))) if find("prev_eval.json") else {}
print(f"interactions {len(interactions)} | facts {len(facts)} | past messages {len(past_messages)} | "
      f"cached chat {len(distill_cached)} | last week's scores: {bool(prev_eval)}")
""")

code("""# 5. Build the dataset (with automatic re-weighting)
seed = int(time.time()) % 100000
pool_file = find("hearing_pool.jsonl")                 # real hearings by both phone speech models
if pool_file:
    set_pool([json.loads(l) for l in open(pool_file, encoding="utf-8") if l.strip()])
print("hearing pool:", len(POOL), "items")
synthetic = make_rows(seed=seed)                      # fresh phrasings every week
eval_synth = [r for r in synthetic if r["kind"] == "command"][:200]
eval_bn = [r for r in synthetic if r["kind"] == "command_bn"][:150]      # Bengali / Banglish test set
eval_keys = {r["user"] for r in eval_synth + eval_bn}
train = [r for r in synthetic if r["user"] not in eval_keys]

# Re-weighting 1: action types the current brain got wrong more often get extra examples
per_action = ((prev_eval.get("synthetic") or {}).get("per_action") or {})
extra = []
for act, acc in per_action.items():
    copies = 2 if acc < 0.85 else (1 if acc < 0.95 else 0)
    pool = [r for r in train if r["kind"] == "command" and json.loads(r["assistant"]).get("action") == act]
    extra += pool * copies
    if copies:
        print(f"re-weighting: {act} was {acc:.0%} correct -> {copies} extra copies")
train += extra
bn_prev = (prev_eval.get("bengali") or {}).get("exact")
if bn_prev is not None and bn_prev < 0.9:                       # Bengali weaker last week: one extra copy
    train += [r for r in train if r["kind"] == "command_bn"]
    print(f"re-weighting: Bengali was {bn_prev:.0%} correct -> 1 extra copy")

# Re-weighting 2: your own commands (corrections 5x, confirmed 3x, ok 2x, recent 1.5x)
mine = user_rows(interactions, facts)
def held(r): return int(hashlib.md5(r["user"].encode()).hexdigest(), 16) % 100 < 15   # stable 15% for testing
eval_user = [r for r in mine if held(r)]
for r in mine:
    if not held(r):
        train += [r] * r["weight"]

# Your past messages (your words only): how you talk, so normal talk isn't mistaken for a command
style = style_rows(past_messages, facts, k=N_STYLE + 100, seed=seed)
eval_style, style = style[:100], style[100:]
train += style

eval_mem_chat, eval_mem_cmd = eval_memory_rows()
if ROLE == "chat":     # the chat brain only answers; the command brain decides what you want
    train = [r for r in train if r["kind"] in ("identity", "memory_chat")]
eval_heard = eval_heard_rows()                        # held-back real hearings (never trained on)
print("synthetic", len(synthetic), "| re-weighted extra", len(extra), "| yours", len(mine),
      "| style", len(style), "| eval: synth", len(eval_synth), "bengali", len(eval_bn), "yours", len(eval_user),
      "style", len(eval_style))
""")

code("""# 6. Load the base model (16-bit LoRA, language layers only)
from unsloth import FastModel
import torch

model, processor = FastModel.from_pretrained(
    model_name=BASE_MODEL, max_seq_length=MAX_LEN,
    load_in_4bit=False, load_in_16bit=True, full_finetuning=False,
)
model = FastModel.get_peft_model(
    model, r=LORA_R, lora_alpha=LORA_R, lora_dropout=0, bias="none",
    finetune_vision_layers=False, finetune_language_layers=True,
    finetune_attention_modules=True, finetune_mlp_modules=True,
    use_gradient_checkpointing="unsloth", random_state=3407,
)
tok = getattr(processor, "tokenizer", processor)   # the text tokenizer inside the processor
""")

code("""# 7. ONE prompt format for training AND the phone (saved as prompt_template.json)
def render_prompt(system, user):
    msgs = [{"role": "system", "content": system}, {"role": "user", "content": user}]
    last = None
    for obj in (tok, processor):
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
json.dump({"template": TEMPLATE, "end": END, "base_model": BASE_MODEL, "hearing": HEARING,
           "cmd_system": CMD_SYSTEM, "chat_system": CHAT_SYSTEM, "facts_header": FACTS_HEADER},
          open(f"{OUT}/prompt_template.json", "w"), ensure_ascii=False, indent=1)
print(repr(TEMPLATE))
tok.padding_side = "right"

def prompt_ids(system, user):
    return tok(TEMPLATE.replace("{SYSTEM}", system).replace("{USER}", user), add_special_tokens=False)["input_ids"]

def generate(system, user, max_new=96, model_=None):
    m = model_ or model
    ids = torch.tensor([prompt_ids(system, user)], device="cuda")
    out = m.generate(input_ids=ids, attention_mask=torch.ones_like(ids), max_new_tokens=max_new,
                     do_sample=False, pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
    text = tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)
    return text.split("</think>")[-1].strip()
""")

code("""# 8. General chat answers (made once by Qwen 3.5 4B, reused every week; the small brains learn from them)
# If there's no cache yet, the base model writes them itself (the LoRA starts as a no-op).
if len(distill_cached) >= N_DISTILL // 2:
    distill = distill_cached
    print("Reusing", len(distill), "cached chat examples")
    shutil.copy(find("chat_distill.jsonl"), f"{OUT}/chat_distill.jsonl")
else:
    from datasets import load_dataset
    rnd = random.Random(1)
    alpaca = [r["instruction"] for r in load_dataset("yahma/alpaca-cleaned", split="train")
              if not r["input"] and 10 <= len(r["instruction"]) <= 200]
    rnd.shuffle(alpaca)
    yours = [m["text"] for m in past_messages if m["text"].rstrip().endswith("?") and 15 <= len(m["text"]) <= 200
             and not COMMAND_ANYWHERE.search(m["text"])]
    rnd.shuffle(yours)
    questions = alpaca[: N_DISTILL * 2 // 3] + yours[: N_DISTILL // 3]
    FastModel.for_inference(model)
    distill = []
    for i, q in enumerate(questions):
        a = generate(CHAT_SYSTEM, q, max_new=110)
        if 5 <= len(a) <= 500 and "<think>" not in a and not a.startswith("{"):
            distill.append({"question": q, "answer": a, "base_model": BASE_MODEL})
        if i % 100 == 0:
            print(i, "|", q[:60], "->", a[:80])
    FastModel.for_training(model)
    with open(f"{OUT}/chat_distill.jsonl", "w", encoding="utf-8") as f:
        for d in distill:
            f.write(json.dumps(d, ensure_ascii=False) + "\\n")
    print("Made", len(distill), "chat examples")
# Bengali questions: answered in Bengali by the base model, once (added to the cache)
have = {d["question"] for d in distill}
todo = [q for q in BN_CHAT_DISTILL if q not in have]
if todo and not any(r["kind"] == "chat" for r in train):   # still before training: the LoRA is a no-op
    FastModel.for_inference(model)
    for q in todo:
        a = generate(CHAT_SYSTEM, q, max_new=140)
        if 5 <= len(a) <= 600 and "<think>" not in a and not a.startswith("{"):
            distill.append({"question": q, "answer": a, "base_model": BASE_MODEL})
    FastModel.for_training(model)
    with open(f"{OUT}/chat_distill.jsonl", "w", encoding="utf-8") as f:
        for d in distill:
            f.write(json.dumps(d, ensure_ascii=False) + "\\n")
    print("Bengali chat examples added:", len(todo), "| e.g.", todo[0], "->", distill[-1]["answer"][:100])
for d in distill:
    sys_prompt = with_facts(CHAT_SYSTEM, random_facts(4) if random.random() < 0.3 else [])
    train.append(row(sys_prompt, d["question"], d["answer"], "chat"))
random.shuffle(train)
counts = {k: sum(1 for r in train if r["kind"] == k) for k in sorted({r["kind"] for r in train})}
print("train rows:", len(train), counts)
""")

code("""# 9. Tokenise (learn only Topshe's replies, not the prompts)
from datasets import Dataset

def encode(r):
    p_ids = prompt_ids(r["system"], r["user"])
    a_ids = tok(r["assistant"] + END + "\\n", add_special_tokens=False)["input_ids"]
    ids = (p_ids + a_ids)[:MAX_LEN]
    labels = ([-100] * len(p_ids) + a_ids)[:MAX_LEN]
    return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": labels}

ds = Dataset.from_list([encode(r) for r in train])
lens = sorted(len(x) for x in ds["input_ids"])
print(ds, "| tokens: median", lens[len(lens) // 2], "max", lens[-1])
""")

code("""# 10. Train (stops safely if it runs too long)
from transformers import Trainer, TrainingArguments, DataCollatorForSeq2Seq, TrainerCallback

class TimeLimit(TrainerCallback):
    def on_step_end(self, args, state, control, **kw):
        if time.time() - T0 > TRAIN_HOURS_LIMIT * 3600:
            print("Time limit reached - stopping training early")
            control.should_training_stop = True

bf16 = torch.cuda.is_bf16_supported()
args = TrainingArguments(
    output_dir=f"{TMP}/ckpt", per_device_train_batch_size=2, gradient_accumulation_steps=8,
    num_train_epochs=EPOCHS, learning_rate=2e-4, warmup_steps=20, lr_scheduler_type="linear",
    logging_steps=25, optim="adamw_8bit", weight_decay=0.01, seed=3407,
    fp16=not bf16, bf16=bf16, save_strategy="no", report_to="none", remove_unused_columns=False,
)
trainer = Trainer(model=model, args=args, train_dataset=ds,
                  data_collator=DataCollatorForSeq2Seq(tok, padding=True, label_pad_token_id=-100),
                  callbacks=[TimeLimit()])
trainer.train()
losses = [h["loss"] for h in trainer.state.log_history if "loss" in h]
print("loss: first", losses[:3], "last", losses[-3:])
assert losses and all(math.isfinite(x) for x in losses), "Training loss became NaN/inf - stopping (nothing will be published)"
model.save_pretrained(f"{OUT}/lora"); processor.save_pretrained(f"{OUT}/lora")
print("Training done in %.0f min" % ((time.time() - T0) / 60))
""")

code("""# 11. Test (the weekly quality gate uses these numbers)
FastModel.for_inference(model)

def score(rows):
    valid = action = exact = 0
    per, misses = {}, []
    for r in rows:
        truth = json.loads(r["assistant"])
        raw = generate(r["system"], r["user"])
        ok_json, same = False, False
        try:
            pred = json.loads(raw[raw.index("{"): raw.rindex("}") + 1]); ok_json = True
            action += pred.get("action") == truth.get("action")
            same = pred == truth or (truth.get("action") == "chat" and pred.get("action") == "chat")
        except Exception:
            pass
        valid += ok_json; exact += same
        a = truth.get("action"); c = per.setdefault(a, [0, 0]); c[0] += same; c[1] += 1
        if not same: misses.append((r["user"], raw))
    n = max(len(rows), 1)
    return {"n": len(rows), "valid": valid / n, "action": action / n, "exact": exact / n,
            "per_action": {k: v[0] / v[1] for k, v in per.items()}, "misses": misses[:25]}

ev = {"role": ROLE}
if ROLE == "command":
    ev["synthetic"] = score(eval_synth)
    ev["bengali"] = score(eval_bn) if eval_bn else None
    ev["heard"] = score(eval_heard) if eval_heard else None
    ev["yours"] = score(eval_user) if eval_user else None
    ev["style_routing"] = score(eval_style)["action"] if eval_style else None      # your normal talk -> chat
    ev["memory_commands"] = score(eval_mem_cmd)["exact"]
hits = 0
for r in eval_mem_chat:
    hits += r["assistant"].lower() in generate(r["system"], r["user"], 60).lower()
ev["memory_chat"] = hits / len(eval_mem_chat)
samples = {q: generate(with_facts(CHAT_SYSTEM, facts), q, 80) for q in
           ["who directed pather panchali?", "what is an instrumental variable, simply?", "who are you?",
            "পথের পাঁচালী কে বানিয়েছিল?"]}
ev["chat_samples"] = samples
ev["chat_ok"] = all(5 <= len(a) <= 600 and not a.startswith("{") for a in samples.values())
if ROLE == "chat":     # knows who it is, and answers Bengali in Bengali
    who = generate(CHAT_SYSTEM, "who are you?", 60).lower()
    bn = samples["পথের পাঁচালী কে বানিয়েছিল?"]
    ev["chat_identity"] = "topshe" in who
    ev["chat_bengali"] = sum("\u0980" <= ch <= "\u09ff" for ch in bn) > len(bn) // 3
ev.update({"train_rows": len(train), "your_interactions": len(interactions), "base_model": BASE_MODEL,
           "loss_last": losses[-1], "minutes_to_eval": round((time.time() - T0) / 60)})
json.dump(ev, open(f"{OUT}/eval.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps({k: (v if not isinstance(v, dict) or "n" not in v else {x: v[x] for x in ("n", "valid", "action", "exact")})
                  for k, v in ev.items()}, indent=1, ensure_ascii=False))
""")

code("""# 12. Done
for p in sorted(glob.glob(f"{OUT}/*")):
    print(os.path.basename(p))
print("Total %.0f min" % ((time.time() - T0) / 60))
""")

nb = {"cells": cells, "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3", "language": "python"},
                                    "language_info": {"name": "python"}},
      "nbformat": 4, "nbformat_minor": 5}
out = HERE / NOTEBOOK
out.write_text(json.dumps(nb, indent=1))
print("wrote", out)
