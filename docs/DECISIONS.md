# Decisions

## What came from Topshe v1 (annay-de/topshe)

v1 (July 2026) was a large design: Stata/LaTeX/git tools, a laptop link over Tailscale + gRPC, a cloned Bengali voice, a 3B model and a home-made voice check. It was never built for a phone (no native libraries, no APK). v2 keeps its good ideas in a smaller app that actually builds.

**Kept**
- **Trust gate:** nothing happens unless the wake word fires *and* the voice matches. Rejected voices get silence, not a reply (v1 D11).
- **Command capture:** keep recording after the wake word until you stop talking, with a hard time cap (v1 D-P2b).
- **One JSON action per command** and a lenient JSON parser (v1 D6).
- **Fixed list of tools:** the model can only pick from known actions (v1 D16).
- **Foreground mic service** with a notification (v1 D12).
- **Honesty:** if something can't be done, Topshe says so instead of pretending (v1 D-P2a).

**Changed or dropped**
- **Home-made voice check:** replaced with a real speaker-embedding model (WeSpeaker, via sherpa-onnx). v1's check compared simple sound averages and was only tested on synthetic voices.
- **Picovoice (v1's wake word):** dropped. In Nov 2026 its sign-up only accepted company emails with a 7-day trial, so the key would expire. Replaced with the sherpa-onnx open-vocabulary keyword spotter: you type the wake word, and no training or account is needed.
- **Wake-word loop:** v1 ran its wake-word engine on a 3-second window every 0.5 s, doing 6x the work and triggering repeatedly on the same audio. v2 sends each frame through once (better battery, no double triggers).
- **3B model:** replaced with the fine-tuned 1.5B model (`training/`), which scored 200/200 on the right action in testing and is faster on the Realme 16.
- **Whisper and Piper native libraries:** replaced with Vosk (offline speech-to-text, ready-made Android library) and Android's built-in text-to-speech. v1 never got these built.
- **Laptop link, gRPC memory, Stata/LaTeX/git tools:** moved to the roadmap (laptop phase).

## v2 choices
- **Confirm calls and messages, and ask which contact if a name matches several:** Annay asked for this after Siri once called the wrong person with the same name.
- **WhatsApp:** opens the chat with the message typed in. WhatsApp doesn't let other apps send for you; SMS can be sent directly.
- **Never turns itself on:** no auto-start on boot. Only the app, the tile or the notification can start it. Kill unloads everything.
- **Tone:** the chat prompt tells Topshe to talk plainly, like to an adult, with no lectures. The base model's built-in limits are unchanged.
- **APK built by GitHub Actions:** no Android Studio needed. llama.cpp is pinned to release `b6100` so upstream changes can't break the build.
- **Music is handled by simple rules before the model:** anything starting with "play", plus pause, next, previous and volume. This is instant, works without retraining, and uses Android's standard media buttons and Spotify's "play from search" (the same route Google Assistant uses), so it works with the phone locked. YouTube finds the top result without an API key and opens it in Brave (or the YouTube app if Brave isn't installed). Android won't show a page over the lock screen, so YouTube starts once you unlock.

## v2.1: self-learning brain (Nov 2026)
- **One brain, Qwen 3.5 4B (Mar 2026), for commands and chat.** It replaces Qwen 2.5 (2024). One model means less memory (Android kills big apps), one retrain, and one model learning from everything. Prompt caching keeps commands fast: the fixed instructions are processed once and restored each time. If the speed test shows commands are too slow, a small command-only model can be added later.
- **16-bit LoRA training, not 4-bit:** Unsloth advises against 4-bit training for Qwen 3.5.
- **Same llama.cpp version (b12602) in the app and in the trainer's GGUF export**, so the phone can always read the model. The trainer also saves `prompt_template.json` next to the model, so the phone uses exactly the training format.
- **Memory instead of retraining for facts:** small models trained on facts mix them up. Facts go into the prompt, while retraining teaches phrasing and corrections.
- **Train only on trusted data:** commands that weren't cancelled, and corrections. Never Topshe's own chat answers, because training on its own output slowly makes a model worse.
- **Quality gate:** a new brain is published only if it passes the tests and is no worse than the current one, both on synthetic tests and on your own held-back commands. The phone keeps the previous brain for a one-tap rollback.
- **Each week trains from the base model** on all data so far, not on top of last week's brain, so errors can't pile up.
- **Whisper base (multilingual, offline) replaces Vosk** for better accuracy on Indian English. An Auto setting handles Hindi and Bengali.
- **Pipeline cost:** the GitHub Actions checks run only on Sunday and Monday, to keep within the free minutes for private repos. Kaggle uses about 3 GPU hours a week.

## v2.2: re-verified training pipeline (Nov 2026)
These were checked against the real sources (unsloth 2026.11.9, kaggle CLI 2.2.5, llama.cpp b12602), and the whole notebook was dry-run end to end.
- **Qwen 3.5 loads through Unsloth's `FastModel`** (it's a vision-language model), with vision layers frozen. It **needs transformers ≥ 5.2**, so the notebook pins `unsloth==2026.11.9` and `transformers>=5.2,<=5.17`.
- **Conversion uses `--no-mtp`:** Qwen 3.5's extra multi-token-prediction head isn't needed on the phone and may be missing after merging. The converter runs in its own venv, because it pins transformers 4.57.6. It uses the base model's original tokenizer files.
- **Phone-file smoke test:** the exact GGUF the phone will run is tested with llama.cpp's `llama-simple` and the exact prompt format before it can be published.
- **General chat comes from the base model itself (self-distillation),** not old Alpaca answers. This keeps Qwen 3.5's own knowledge and adds the short style. It's made once and reused every week (saves about 45 GPU minutes).
- **Automatic re-weighting each week:**
  - action types the current brain got wrong get extra examples (below 95% correct: 2x; below 85%: 3x)
  - your corrections count 5x, confirmed commands 3x, ok commands 2x
  - anything from the last 14 days counts 1.5x
- **Your past Claude messages (your words only)** teach how you talk, labelled as conversation so normal talk isn't mistaken for a command. Anything that sounds like a phone command is skipped.
- **The gate now also checks:**
  - the phone-file smoke test (at least 5 of 6)
  - chat sanity
  - your normal talk recognised as chat (at least 85%)
  - using remembered facts on held-out facts it never trained on (at least 50%)
  - no drop versus the current brain
- **Fixes:** the Kaggle CLI needs `six`, which it doesn't declare. Empty Kaggle login variables are removed. Kaggle run logs are saved to `retrain/last_run.log`. Collection runs every 3 hours, every day.

## v2.3: the phone file is built on GitHub, and checked weight by weight (Nov 2026)
- **What went wrong in run 1:** training scored perfectly on Kaggle, but the phone file was the untouched base model. Unsloth's `save_pretrained_merged` (2026.11.9, Qwen 3.5) silently wrote the original weights. The phone file's weights had zero correlation (0.000) with the training.
- **Fix:** Kaggle now only trains and tests, then saves `lora/` (the training itself, about 130 MB). GitHub Actions builds the phone file with `training/export_brain.py`:
  1. downloads the original base model
  2. adds the LoRA to every targeted weight in plain PyTorch (strict name and shape checks; changed weights stored as float16)
  3. converts to GGUF with llama.cpp b12602, then quantizes to Q4_K_M
- **Proof the training is inside the file:**
  - the 16-bit file must hold exactly base + LoRA on sample weights
  - the 4-bit file must still correlate clearly with the LoRA delta
  - 12 test prompts with the exact phone prompt (at least 85% right)
  - the gate rejects any file that fails these
- **Side benefits:** less Kaggle GPU time per week (no conversion there). A finished run can be rebuilt without retraining (`retrain.yml`, mode `reexport`).
