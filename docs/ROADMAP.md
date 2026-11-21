# Roadmap

1. **Phone (done):** wake word, voice check, Whisper speech-to-text, one Qwen 3.5 4B brain, memory, corrections, weekly self-retraining, music, kill switch.
2. **Next, once tested on the phone:**
   - Tune speed with the speed-test results.
   - If commands feel slow, add a small Qwen 3.5 0.8B/2B just for commands.
   - Compare against Gemma 4 E4B on the phone, and keep the better one.
3. **Laptop:**
   - A small app with the same wake word, voice check and brain, plus v1's work tools: run a Stata do-file, compile LaTeX, git commit.
   - The phone can hand those commands to the laptop when it's on.
   - When the laptop is on, hard questions can go to a bigger model running there.
4. **iPad:** Apple doesn't allow background wake words or replacing Siri. Plan: a Siri Shortcut ("Hey Siri, Topshe...") that sends the request to the phone or laptop.
5. **Later:**
   - Spotify library connection (picks *your* version of a song).
   - Calendar.
   - A cloned voice.
   - Training on chat answers you rate as good.
