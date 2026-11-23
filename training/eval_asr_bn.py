"""
Compares offline speech-to-text models on Bengali, Banglish and English voice commands.
Runs on GitHub Actions (.github/workflows/eval-asr.yml). Test audio is made with Microsoft's
Indian Bengali / Indian English voices (edge-tts), then every model transcribes every clip.

Models (all run by sherpa-onnx, the engine in the app):
  whisper-base  - the current model (English), also tried with Bengali / auto language
  shruti-bn     - Shruti Bengali (Moonshine, 54 MB): Bengali + English + mixed, English in Latin script
  zipformer-bn  - sherpa-onnx streaming zipformer, Bengali (Vosk)
"""
import asyncio
import glob
import json
import os
import subprocess
import sys
import time

import numpy as np
import sherpa_onnx
import soundfile as sf

CLIPS = [  # (voice, text, kind)
    ("bn", "টপসে, মা-কে ফোন কর", "bengali informal"),
    ("bn", "মাকে একবার ফোন করো", "bengali formal"),
    ("bn", "বাবাকে মেসেজ পাঠা যে আমি দেরি করে ফিরব", "bengali"),
    ("bn", "কাল সকাল ছ'টা পঁয়তাল্লিশে অ্যালার্ম দে", "bengali"),
    ("bn", "দশ মিনিটের টাইমার লাগা", "bengali"),
    ("bn", "Spotify-তে তুম হি হো চালা", "banglish"),
    ("bn", "গানটা বন্ধ কর", "bengali informal"),
    ("bn", "পরের গানটা চালা", "bengali informal"),
    ("bn", "গান চালাও তো", "bengali formal"),
    ("bn", "আওয়াজ একটু বাড়া", "bengali informal"),
    ("bn", "YouTube খোল", "banglish"),
    ("bn", "মনে রাখিস আমার জিম সন্ধে সাতটায়", "bengali informal"),
    ("bn", "শুক্রবার পাঁচটায় RA meeting-এর কথা মনে করিয়ে দিস", "banglish"),
    ("bn", "রিয়াকে WhatsApp-এ লেখ যে আমি আসছি", "banglish"),
    ("bn", "অরিজিৎ সিংয়ের গান চালাও", "bengali formal"),
    ("bn", "পথের পাঁচালী কে বানিয়েছিল?", "bengali question"),
    ("bn", "আজকে বাইরে কেমন ঠান্ডা?", "bengali question"),
    ("bn", "হ্যাঁ, ওইটাই", "bengali yes"),
    ("bn", "না না, অন্যটা", "bengali no"),
    ("bn", "এখন চুপ কর", "bengali stop"),
    ("en", "Topshe, play Tum Hi Ho on Spotify", "english"),
    ("en", "Wake me up at six forty five tomorrow", "english"),
    ("en", "Call Maa", "english"),
    ("en", "Set a timer for ten minutes", "english"),
    ("en", "Remind me to call Baba at eight pm", "english"),
    ("en", "Open YouTube and play something by Arijit Singh", "english"),
    ("en", "Go to sleep", "english"),
    ("en", "No, I meant the other one", "english"),
]
VOICES = {"bn": ["bn-IN-BashkarNeural", "bn-IN-TanishaaNeural"], "en": ["en-IN-PrabhatNeural", "en-IN-NeerjaNeural"]}


async def make_audio(out):
    import edge_tts
    os.makedirs(out, exist_ok=True)
    items = []
    for i, (lang, text, kind) in enumerate(CLIPS):
        for v in VOICES[lang]:
            mp3, wav = f"{out}/{i:02d}_{v}.mp3", f"{out}/{i:02d}_{v}.wav"
            if not os.path.exists(wav):
                for attempt in range(3):
                    try:
                        await edge_tts.Communicate(text, v).save(mp3)
                        break
                    except Exception as e:
                        print("tts retry", v, text, e)
                        await asyncio.sleep(3)
                subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ar", "16000", "-ac", "1", wav], check=True)
            items.append({"i": i, "voice": v, "text": text, "kind": kind, "wav": wav})
    return items


def pick(d, part):
    fs = sorted(glob.glob(f"{d}/*{part}*.onnx"))
    q = [f for f in fs if "int8" in f]
    return (q or fs)[0]


def offline(rec, samples):
    s = rec.create_stream()
    s.accept_waveform(16000, samples)
    rec.decode_stream(s)
    return s.result.text, getattr(s.result, "lang", "")


def online(rec, samples):
    s = rec.create_stream()
    s.accept_waveform(16000, samples)
    s.accept_waveform(16000, np.zeros(int(0.6 * 16000), dtype=np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    return rec.get_result(s), ""


def models(root):
    w = f"{root}/sherpa-onnx-whisper-base"
    out = {}
    for lang in ("en", "bn", ""):
        out[f"whisper-base[{lang or 'auto'}]"] = (offline, sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=pick(w, "encoder"), decoder=pick(w, "decoder"), tokens=glob.glob(f"{w}/*tokens.txt")[0],
            language=lang, task="transcribe", num_threads=2))
    sh = glob.glob(f"{root}/shruti/bn/sherpa-onnx")[0]
    out["shruti-bn"] = (offline, sherpa_onnx.OfflineRecognizer.from_moonshine(
        preprocessor=f"{sh}/preprocess.onnx", encoder=f"{sh}/encode.onnx",
        uncached_decoder=f"{sh}/uncached_decode.onnx", cached_decoder=f"{sh}/cached_decode.onnx",
        tokens=f"{sh}/tokens.txt", num_threads=2))
    z = glob.glob(f"{root}/sherpa-onnx-streaming-zipformer-bn-vosk*")[0]
    out["zipformer-bn"] = (online, sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=f"{z}/tokens.txt", encoder=pick(z, "encoder"), decoder=pick(z, "decoder"), joiner=pick(z, "joiner"),
        num_threads=2, sample_rate=16000, feature_dim=80, decoding_method="greedy_search"))
    return out


def cer(ref, hyp):
    """Character error rate, ignoring spaces and punctuation."""
    keep = lambda s: [c for c in s.lower() if c.isalnum() or "ঀ" <= c <= "৿"]
    r, h = keep(ref), keep(hyp)
    if not r:
        return 0.0
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
    return d[len(h)] / len(r)


def main(root):
    items = asyncio.run(make_audio(f"{root}/clips"))
    ms = models(root)
    results = {name: [] for name in ms}
    for it in items:
        samples, sr = sf.read(it["wav"], dtype="float32")
        print(f"\n[{it['kind']}] {it['text']}  ({it['voice']}, {len(samples) / 16000:.1f}s)")
        for name, (fn, rec) in ms.items():
            t0 = time.time()
            text, lang = fn(rec, samples)
            dt = time.time() - t0
            c = cer(it["text"], text)
            results[name].append({**{k: it[k] for k in ("i", "voice", "text", "kind")}, "heard": text, "lang": lang,
                                  "cer": round(c, 3), "secs": round(dt, 3)})
            print(f"   {name:20} {dt:5.2f}s  cer {c:4.2f}  {lang:3} | {text}")
    print("\n===== SUMMARY (mean character error; lower is better) =====")
    for name, rows in results.items():
        bn = [r["cer"] for r in rows if r["kind"] != "english"]
        en = [r["cer"] for r in rows if r["kind"] == "english"]
        secs = [r["secs"] for r in rows]
        print(f"{name:20} bengali/banglish {np.mean(bn):.2f}   english {np.mean(en):.2f}   avg time {np.mean(secs):.2f}s")
    json.dump(results, open(f"{root}/asr_results.json", "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "asr_eval")
