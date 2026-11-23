# Setup (once)

## A. Let GitHub train Topshe on Kaggle (laptop, about 5 min)

1. On **kaggle.com**, go to your profile picture → **Settings** → **API** → **Create New Token**. Copy the token. If it downloads a `kaggle.json` file, open it: the key is the long string in it.
2. On **github.com/annay-de/Topshe-v2**, go to **Settings** → **Secrets and variables** → **Actions** → **New repository secret**, and add:
   - `KAGGLE_USERNAME`: your Kaggle username (lowercase, as it appears in your profile URL)
   - `KAGGLE_API_TOKEN`: the token. If you have an older-style `kaggle.json`, add its key as `KAGGLE_KEY` instead.
3. Your Kaggle account must be **phone-verified**, which is needed for GPUs.
4. Start the first training: on GitHub, go to **Actions** → **Topshe weekly retrain** → **Run workflow** → mode `start`. It takes about 2–4 hours on Kaggle and uses about 3 of your 30 weekly GPU hours. After that it runs by itself every Sunday at 01:00 IST.

## B. Phone

1. Uninstall any old Topshe **once**. From now on, updates install over it, because builds are signed with your permanent key.
2. Go to **Releases** → install the newest `topshe-v2-build-N.apk`. Play Protect blocks apps downloaded in a browser that can call or text. Turn **Play Store → profile → Play Protect → ⚙ → Scan apps** off, install, then turn it back on. You only need this once: later builds install from inside the app (**App updates → Update the app**).
3. In the app:
   1. **Permissions:** tap all three buttons.
   2. **Download voice models:** about 330 MB, use Wi-Fi. There are four: wake word, voice check, Whisper (English) and the Bengali model. Progress shows under the button.
   3. **Record my voice:** talk for 25 seconds.
   4. **Brain and learning:** paste a GitHub token and tap **Save token and sync now**. To make the token:
      1. github.com → **Settings** → **Developer settings** → **Fine-grained tokens** → **Generate new token**.
      2. Repository access: **Only select repositories** → `Topshe-v2`.
      3. Permissions: **Contents → Read and write**.
      4. Expiration: the longest allowed. GitHub will email you before it expires.
   5. Once the first weekly brain is ready, it downloads on Wi-Fi by itself. That's about 2.7 GB once, then the same size each week. Until then, you can pick the older `topshe_v2.gguf`.
   6. Run **Speed test** once. It picks the fastest setting for your phone.
   7. Tap **Start listening**, and add the **Topshe** tile to quick settings.
4. On Realme, go to Settings → Apps → Topshe → Battery → **Allow background activity**, and lock Topshe in Recent apps.

## Tuning

The **Activity** log shows every wake: what it heard, your voice score, and what it did.
- Ignores you → lower **Voice check strictness**.
- Doesn't wake → lower **Wake word strictness**, or add a spelling like `TOP SHAY`.
- Wakes by itself → raise **Wake word strictness**.
- Bengali: Topshe always listens with both the English and the Bengali model, and the brain picks the one that makes sense. Leave **English speech model** on "English only".
- For Bengali spoken answers, install a Bengali voice: Settings → Text-to-speech → Google → Install voice data → Bengali (India).

## Checking on the learning

- **GitHub → Actions → Topshe weekly retrain** shows each week's run.
- The `data` branch's `retrain/history.jsonl` shows every result: published, or rejected and why.
- **Releases** → `model-N` lists each brain with its test scores.
- In the app, **Go back to the previous brain** undoes an update.

## App icon

Put any picture at `branding/icon.png` in GitHub (see `branding/README.md`). The next build uses it as the icon. Install it with **Update the app**.
