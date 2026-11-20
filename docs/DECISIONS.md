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
