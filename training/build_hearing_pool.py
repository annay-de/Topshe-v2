"""
Builds data/hearing_pool.jsonl: training commands (English and Bengali/Banglish) spoken by Indian
voices (edge-tts) and transcribed by BOTH speech models the phone runs:
  - Whisper base, English        (good at English, garbles Bengali)
  - Zipformer, Bengali (Vosk)    (good at Bengali, spells English sounds in Bengali letters)
The brain is then trained on both hearings at once, so it learns from the real mistakes of each
model which line makes sense. Only made-up commands are spoken (never your own messages).

    python training/build_hearing_pool.py <models_dir> <out.jsonl> [n_en] [n_bn]
"""
import asyncio
import glob
import json
import multiprocessing as mp
import os
import random
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_dataset_v4 as D  # noqa: E402

VOICES = {"en": ["en-IN-PrabhatNeural", "en-IN-NeerjaNeural"], "bn": ["bn-IN-BashkarNeural", "bn-IN-TanishaaNeural"]}
POOL_SEED = 7


def clean_text(user, lang):
    """The request itself, without the two-hearing wrapper (make_dataset_v4.as_heard)."""
    if not user.startswith("english: "):
        return user
    return user.split("\nbangla: ", 1)[1] if lang == "bn" else user.split("\nbangla: ", 1)[0].removeprefix("english: ")


def commands(n_en, n_bn):
    rows = D.make_rows(seed=POOL_SEED)
    en = [r for r in rows if r["kind"] == "command"][:n_en]
    bn = [r for r in rows if r["kind"] == "command_bn"][:n_bn]
    rnd = random.Random(POOL_SEED)
    items = [{"text": clean_text(r["user"], "en"), "assistant": r["assistant"], "lang": "en"} for r in en]
    items += [{"text": clean_text(r["user"], "bn"), "assistant": r["assistant"], "lang": "bn"} for r in bn]
    for i, it in enumerate(items):
        it["id"] = i
        it["voice"] = rnd.choice(VOICES[it["lang"]])
    return items


async def speak_all(items, folder, conc=6):
    import edge_tts
    os.makedirs(folder, exist_ok=True)
    sem = asyncio.Semaphore(conc)
    done = [0]

    async def one(it):
        mp3 = f"{folder}/{it['id']}.mp3"
        if os.path.exists(mp3) and os.path.getsize(mp3) > 1000:
            return
        async with sem:
            for attempt in range(5):
                try:
                    await edge_tts.Communicate(it["text"], it["voice"]).save(mp3)
                    break
                except Exception as e:  # throttled or dropped: back off and retry
                    await asyncio.sleep(2 + 3 * attempt)
                    if attempt == 4:
                        print("TTS failed:", it["id"], e)
        done[0] += 1
        if done[0] % 250 == 0:
            print(f"spoken {done[0]}/{len(items)}", flush=True)

    await asyncio.gather(*(one(it) for it in items))


def to_wav(mp3):
    wav = mp3[:-4] + ".wav"
    if os.path.exists(mp3) and not os.path.exists(wav):
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ar", "16000", "-ac", "1", wav])
    return wav


def pick(d, part):
    fs = sorted(glob.glob(f"{d}/*{part}*.onnx"))
    q = [f for f in fs if "int8" in f]
    return (q or fs)[0]


_R = {}


def _init(models):
    import sherpa_onnx
    w = glob.glob(f"{models}/sherpa-onnx-whisper-base")[0]
    _R["en"] = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=pick(w, "encoder"), decoder=pick(w, "decoder"), tokens=glob.glob(f"{w}/*tokens.txt")[0],
        language="en", task="transcribe", num_threads=1)
    z = glob.glob(f"{models}/sherpa-onnx-streaming-zipformer-bn-vosk*")[0]
    _R["bn"] = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=f"{z}/tokens.txt", encoder=pick(z, "encoder"), decoder=pick(z, "decoder"), joiner=pick(z, "joiner"),
        num_threads=1, sample_rate=16000, feature_dim=80, decoding_method="greedy_search")


def _hear(wav):
    import numpy as np
    import soundfile as sf
    if not os.path.exists(wav):
        return None
    x, sr = sf.read(wav, dtype="float32")
    s = _R["en"].create_stream()
    s.accept_waveform(16000, x)
    _R["en"].decode_stream(s)
    en = s.result.text.strip()
    z = _R["bn"].create_stream()
    z.accept_waveform(16000, x)
    z.accept_waveform(16000, np.zeros(int(0.6 * 16000), dtype=np.float32))
    z.input_finished()
    while _R["bn"].is_ready(z):
        _R["bn"].decode_stream(z)
    bn = _R["bn"].get_result(z).strip()
    return en, bn


def main(models, out, n_en=1500, n_bn=1500):
    t0 = time.time()
    items = commands(int(n_en), int(n_bn))
    folder = os.path.join(os.path.dirname(os.path.abspath(out)) or ".", "pool_audio")
    asyncio.run(speak_all(items, folder))
    print(f"TTS done in {time.time() - t0:.0f}s", flush=True)
    with mp.Pool(4) as p:
        wavs = p.map(to_wav, [f"{folder}/{it['id']}.mp3" for it in items], chunksize=50)
    print(f"converted in {time.time() - t0:.0f}s", flush=True)
    with mp.Pool(2, initializer=_init, initargs=(models,)) as p:
        heard = p.map(_hear, wavs, chunksize=25)
    kept = 0
    with open(out, "w", encoding="utf-8") as f:
        for it, h in zip(items, heard):
            if not h or not (h[0] or h[1]):
                continue
            it["en"], it["bn"] = h
            f.write(json.dumps(it, ensure_ascii=False) + "\n")
            kept += 1
    print(f"wrote {kept}/{len(items)} hearings to {out} in {time.time() - t0:.0f}s")
    for it in [x for x in items if "en" in x][:6] + [x for x in items if "en" in x and x["lang"] == "bn"][:6]:
        print(f"[{it['lang']}] {it['text']}\n     EN: {it['en']}\n     BN: {it['bn']}\n     => {it['assistant']}")


if __name__ == "__main__":
    main(*sys.argv[1:])
