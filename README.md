# Topshe v2

Annay's personal voice assistant for Android. Say **"Topshe"** and it wakes up, but only for Annay's voice.
It runs offline on the phone, using its own fine-tuned model.

- **Wakes only for you:** the wake word ("Topshe") plus a voice check. Both are free open-source models (sherpa-onnx), so no account or key is needed.
- **You decide when it listens:** a quick-settings tile, a notification with **Pause** and **Kill** buttons, or say "Topshe, go to sleep". It never turns itself on.
- **Offline:** speech-to-text (Vosk), the model (Qwen 2.5 1.5B, fine-tuned) and the voice all run on the phone. The internet is only used once, to download the speech model.
- **Can't go rogue:** it can only do actions from a fixed list. Calls and messages always ask "yes or no" first. If two contacts have the same name, it asks which one.

## What it can do

| Say | Does |
|---|---|
| "Topshe, play music" | Resumes Spotify (works with the phone locked) |
| "Topshe, play Blinding Lights" / "...on Spotify" | Spotify finds and plays it |
| "Topshe, play lofi beats on YouTube" | Opens the top YouTube result in Brave (keeps playing with the screen off) |
| "Topshe, pause" / "next" / "previous" / "volume up" | Media controls for whatever is playing |
| "Topshe, wake me up at 6:45" | Sets an alarm |
| "Topshe, timer 10 minutes" | Starts a timer |
| "Topshe, remind me to call Maa in 20 minutes" | Sets a reminder (notification) |
| "Topshe, open Spotify" | Opens the app |
| "Topshe, call Tanmay" | Asks which Tanmay (if needed), then "yes or no", then calls |
| "Topshe, message Rahul I'm on my way" | Confirms, then opens WhatsApp with the text typed (you tap send). Say "by SMS" to send an SMS directly |
| "Topshe, wifi off" | Runs the Tasker task named `wifi off` |
| "Topshe, who directed Pather Panchali?" | Answers |
| "Topshe, go to sleep" | Pauses listening |

## Setup (once)

1. **Install the app:** go to this repo's **Releases** on your phone, download the latest `topshe-v2-build-N.apk` and open it. Allow "install unknown apps" if asked.
2. Open **Topshe** and go through the steps on screen:
   1. **Permissions:** tap all three buttons.
   2. **Files:** pick `topshe_v2.gguf` (from the Kaggle notebook output), then tap **Download voice models** (about 75 MB, once, on Wi-Fi).
   3. **Teach it your voice:** tap and talk for 25 seconds.
3. Tap **Start listening**. Then add the **Topshe** tile to your quick settings: pull down twice, tap the pencil and drag Topshe in.

**Realme tip:** go to Settings, then Apps, then Topshe, then Battery, and choose **Allow background activity**. Also lock Topshe in Recent apps (pull its card down). Otherwise Realme UI may kill it.

## Tuning

The **Activity** log in the app shows every wake: what it heard and your voice score.
- If it ignores you, lower **Voice check strictness**.
- If it doesn't wake, lower **Wake word strictness**, or add another spelling of how you say it (e.g. `TOP SHAY`).
- If it wakes when nobody said Topshe, raise **Wake word strictness**.

## Layout

```
android/        the app (Kotlin + a small C++ bridge to llama.cpp)
training/       data generator + Kaggle notebook that made topshe_v2.gguf
docs/           roadmap and decisions
.github/        builds the APK automatically on every push
```

See [docs/DECISIONS.md](docs/DECISIONS.md) for what was kept from Topshe v1 and why.
