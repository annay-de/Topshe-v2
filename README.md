# Topshe v2

Annay's personal voice assistant for Android. Say **"Topshe"** and it wakes up, but only for Annay's voice. It runs on the phone, learns from everything you say, and **retrains itself every week**.

## What it does

| Say | Does |
|---|---|
| "Topshe, play music" / "play Blinding Lights" | Spotify resumes, or finds and plays the song (works with the phone locked) |
| "Topshe, play lofi beats on YouTube" | Opens the top result in Brave (keeps playing with the screen off) |
| "Topshe, pause" / "next" / "volume up" | Media controls |
| "Topshe, wake me up at 6:45" / "timer 10 minutes" | Alarm or timer |
| "Topshe, remind me to call Maa in 20 minutes" | Reminder |
| "Topshe, call Tanmay" / "message Rahul I'm on my way" | Asks which person if several match, then "yes or no", then calls or sends |
| "Topshe, open Zerodha" / "wifi off" | Opens an app, or runs a Tasker task of that name |
| "Topshe, remember that my shuttle leaves at 8:10" | Saves it. Topshe uses it from then on ("when's my shuttle?") |
| "Topshe, forget that ..." | Removes it |
| "No, I meant ..." (after a mistake) | Fixes it, and never makes that mistake again |
| "Topshe, who directed Pather Panchali?" | Answers |
| "Topshe, go to sleep" | Stops listening |
| "টপসে, মা-কে ফোন কর" / "গানটা বন্ধ কর" / "কাল সকাল সাড়ে সাতটায় তুলে দিস" | The same in Bengali or Banglish, informal or formal |

## How it learns

```
you talk -> phone log -> (daily, Wi-Fi) private `data` branch
                                   |
               Sunday 01:00 IST: GitHub starts training on Kaggle (Qwen 3.5 4B + your data)
                                   |
                 tests: is it at least as good as the current brain?
                     no -> thrown away        yes -> published as model-N
                                   |
                 phone downloads it on Wi-Fi and switches (old one kept for 1-tap rollback)
```

- **Instant, no retraining:**
  - facts you ask it to remember, which go into every prompt
  - your corrections
  - which contact you meant
- **Weekly:** your real commands and corrections are added to training (counted 3x), so it learns your phrasing.
- **Safety:**
  - Only trusted examples are trained on: commands you didn't cancel, and your corrections. Never its own chat answers.
  - Each new brain must pass held-back tests, including your own past commands, and must not score lower than the current brain.

## Privacy and safety

- The wake word, voice check, speech-to-text (Whisper for English plus a Bengali model) and brain all run **on the phone**.
- Your interaction log goes only to this private repo's `data` branch and to a private Kaggle dataset, for training.
- It can only do actions from a fixed list. Calls and messages always ask "yes or no". It ignores voices that aren't yours.
- **Kill switch:** the notification's **Kill** button or the quick-settings tile turns everything off. It never turns itself on.

## Setup

See **[docs/SETUP.md](docs/SETUP.md)**.

## Layout

```
android/              the app (Kotlin + C++ bridge to llama.cpp b12602)
training/             data generator, trainer notebook (Qwen 3.5 4B), weekly pipeline
.github/workflows/    build-apk.yml (app), retrain.yml (weekly learning)
docs/                 setup, decisions, roadmap
data branch           your phone's uploads + training status (private)
```
