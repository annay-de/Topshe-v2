"""
Imports YOUR messages from a Claude data export (conversations.json) for Topshe's training.

    python training/import_claude_export.py conversations.json out/claude_messages.jsonl

- Keeps only your own messages (sender == human). Claude's replies are never used.
- Keeps short, natural messages (how you actually talk/type); skips long pastes and code.
- Skips chats that weren't yours (e.g. someone else's application written on your account).
- Removes emails, phone numbers, long numbers (IDs, accounts), links and keys.
"""
import json
import re
import sys

NOT_MINE = re.compile(r"ahmedabad|\bsop\b|bits pilani|phd application inquiry|winter school", re.I)
SCRUB = [
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), "[email]"),
    (re.compile(r"https?://\S+"), "[link]"),
    (re.compile(r"\b(?:sk|ghp|github_pat|kgat|hf)_[A-Za-z0-9_\-]{10,}\b", re.I), "[key]"),
    (re.compile(r"(?<!\d)(?:\+?91[\s-]?)?[6-9]\d{9}(?!\d)"), "[phone]"),
    (re.compile(r"\b\d{9,}\b"), "[number]"),
]


def clean(t):
    for rx, rep in SCRUB:
        t = rx.sub(rep, t)
    return " ".join(t.split())


def looks_pasted(t):
    return (t.startswith("Q: ") or "```" in t or t.count("\n") > 4 or len(re.findall(r"[{};=<>]", t)) > 6
            or len(t) > 300 or len(t) < 3)


def main(src, dst):
    convs = json.load(open(src, encoding="utf-8"))
    kept, skipped_convs = [], 0
    for c in convs:
        title = f"{c.get('name') or ''} {c.get('summary') or ''}"
        if NOT_MINE.search(title):
            skipped_convs += 1
            continue
        for m in c.get("chat_messages") or []:
            if m.get("sender") != "human":
                continue
            t = (m.get("text") or "").strip()
            if looks_pasted(t):
                continue
            kept.append({"text": clean(t), "ts": m.get("created_at", "")[:10]})
    seen, out = set(), []
    for k in kept:
        key = k["text"].lower()
        if key not in seen:
            seen.add(key)
            out.append(k)
    with open(dst, "w", encoding="utf-8") as f:
        for k in out:
            f.write(json.dumps(k, ensure_ascii=False) + "\n")
    print(f"kept {len(out)} of your messages; skipped {skipped_convs} chats that weren't yours")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
