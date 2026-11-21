import json
import random

random.seed(42)

"""
Topshe training data v3.

Shared by the first training run and every weekly retrain. The Android app uses the
SAME system prompts (Brain.kt), so keep them in sync:
  - CMD_SYSTEM  + optional facts block  -> one compact JSON action
  - CHAT_SYSTEM + optional facts block  -> a short spoken answer
"""

CMD_SYSTEM = (
    "You are Topshe, a phone assistant. Turn the user's request into ONE JSON object. "
    "Actions: set_alarm, set_timer, set_reminder, open_app, call, message, tasker, media, "
    "remember, forget, stop, chat. Use chat for questions or small talk. Reply with JSON only."
)
CHAT_SYSTEM = (
    "You are Topshe, a friendly assistant. Answer in 1-3 short, simple sentences. "
    "Talk to Annay plainly, like an adult. No lectures or disclaimers."
)
FACTS_HEADER = "What you know about Annay:"


def with_facts(system, facts):
    """System prompt plus the memory block, exactly as the app builds it."""
    if not facts:
        return system
    return system + "\n\n" + FACTS_HEADER + "\n" + "\n".join("- " + f for f in facts)


def dumps(obj):
    """Compact JSON (fewer tokens = faster replies on the phone)."""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))

# Add your own apps and contacts here, then re-run
APPS = ["WhatsApp", "YouTube", "Spotify", "Chrome", "Gmail", "Maps", "Camera", "Instagram",
        "Telegram", "Settings", "Calculator", "Calendar", "Kindle", "Zomato", "Swiggy", "Uber",
        "Ola", "Google Pay", "PhonePe", "Notes", "Files", "Clock", "Photos", "Zerodha Kite",
        "Netflix", "Hotstar", "Drive", "Meet", "Zoom", "Slack", "LinkedIn", "Twitter",
        "Blinkit", "Zepto", "Amazon", "Flipkart", "Paytm", "Outlook", "Teams", "Discord"]
CONTACTS = ["Maa", "Baba", "Tanmay", "Rahul", "Priya", "Didi", "Dada", "Arjun", "Sneha",
            "Professor Sen", "Ankit", "Riya", "Mama", "Kaku", "Shreya", "Sourav", "Ritwik",
            "Pishi", "Jethu", "Anirban", "Debjani", "Rohan", "Ishita", "Sayan", "Moumita"]
MSG_TEXTS = ["I'll be late", "reached home", "call me when free", "on my way",
             "running 10 minutes late", "can we meet tomorrow", "sent the file",
             "happy birthday", "I'm in class right now", "dinner at 9?", "the draft is ready",
             "I'm taking the shuttle", "will call you later", "check your email",
             "ok see you soon", "I'm in a meeting"]
TASKS = ["wifi", "bluetooth", "silent mode", "dnd", "torch", "battery saver", "hotspot",
         "dark mode", "study mode", "mobile data", "location", "airplane mode"]
REMIND_TEXTS = ["submit the assignment", "call Maa", "take the shuttle", "RA meeting",
                "buy milk", "pay the electricity bill", "check email", "drink water",
                "start the Stata run", "read the paper", "take my medicine", "book the cab",
                "charge the laptop", "reply to the professor", "go to the gym"]
CHAT_Q = ["What is the capital of Japan?", "Who wrote Sonar Kella?", "How far is the moon?",
          "Tell me a joke", "How are you?", "What is 17 times 23?", "Who is Feluda?",
          "What should I cook tonight?", "How many litres in a gallon?", "Give me a fun fact",
          "Translate thank you to Bengali", "Thank you Topshe", "Suggest a good book",
          "Why is the sky blue?", "What's a p-value?", "Good morning", "What can you do?",
          "Who are you?", "How many days in a leap year?", "What's 15 percent of 240?"]
TOPICS = ["black holes", "the Mughal empire", "Satyajit Ray", "photosynthesis", "inflation",
          "machine learning", "the stock market", "Durga Puja", "the Bengal famine", "cricket",
          "climate change", "the RBI", "vaccines", "the Taj Mahal", "Rabindranath Tagore",
          "game theory", "the French Revolution", "monsoons", "GST", "quantum computing",
          "the Sundarbans", "regression", "Netaji Subhas Chandra Bose", "the IPL", "DNA",
          "instrumental variables", "mutual funds", "the Indian constitution", "volcanoes"]
CHAT_TMPL = ["what is {x}", "tell me about {x}", "explain {x} simply", "who started {x}",
             "why is {x} important", "give me a fact about {x}", "what do you know about {x}",
             "{x} ke baare mein batao", "{x} niye kichu bolo", "summarise {x} in two lines",
             "how does {x} work", "is {x} a good thing"]


def fmt_time(h, m):
    ampm = "am" if h < 12 else "pm"
    h12 = h % 12 or 12
    opts = [f"{h12}:{m:02d} {ampm}", f"{h12}:{m:02d}{ampm}", f"{h12} {m:02d} {ampm}"]
    if m == 0:
        opts += [f"{h12} {ampm}", f"{h12} o'clock " + ("in the morning" if h < 12 else "in the evening")]
    if m == 30:
        opts.append(f"half past {h12} {ampm}")
    if m == 15:
        opts.append(f"quarter past {h12} {ampm}")
    return random.choice(opts)


def ex_alarm():
    h = random.randint(0, 23)
    m = random.choice([0, 0, 0, 15, 30, 30, 45, random.randint(0, 59)])
    tmpl = random.choice([
        "wake me up at {t}", "set an alarm for {t}", "alarm at {t}", "set alarm {t} tomorrow",
        "I need to get up at {t} tomorrow", "kal subah {t} baje alarm laga do", "{t} er alarm dao",
        "kal {t} e alarm dao", "alarm for {t} today", "put an alarm at {t}",
        "set a {t} alarm", "{t} pe alarm set karo",
    ])
    day = "tomorrow" if ("tomorrow" in tmpl or "kal" in tmpl) else ("today" if "today" in tmpl else "next")
    return tmpl.format(t=fmt_time(h, m)), {"action": "set_alarm", "hour": h, "minute": m, "day": day}


def ex_timer():
    unit = random.choice(["seconds", "minutes", "minutes", "minutes", "hours"])
    n = random.randint(1, 3) if unit == "hours" else random.randint(1, 90)
    word = unit[:-1] if n == 1 else unit
    secs = n * {"seconds": 1, "minutes": 60, "hours": 3600}[unit]
    text = random.choice([
        "set a timer for {n} {u}", "timer {n} {u}", "start a {n} {u} timer", "{n} {u} ka timer lagao",
        "count down {n} {u}", "{n} {u} er timer dao", "time {n} {u}",
    ]).format(n=n, u=word)
    return text, {"action": "set_timer", "seconds": secs}


def ex_reminder():
    what = random.choice(REMIND_TEXTS)
    if random.random() < 0.5:
        n = random.choice([5, 10, 15, 20, 30, 45, 60, 90, 120])
        text = random.choice(["remind me to {w} in {n} minutes", "in {n} minutes remind me to {w}",
                              "{n} minute baad yaad dilana {w}", "remind me in {n} minutes to {w}"]).format(w=what, n=n)
        return text, {"action": "set_reminder", "text": what, "in_minutes": n}
    h, m = random.randint(6, 23), random.choice([0, 15, 30, 45])
    day = random.choice(["today", "tomorrow"])
    text = random.choice(["remind me to {w} at {t} {d}", "{d} at {t} remind me to {w}",
                          "set a reminder to {w} at {t} {d}", "remind me {d} at {t} to {w}"]).format(w=what, t=fmt_time(h, m), d=day)
    return text, {"action": "set_reminder", "text": what, "hour": h, "minute": m, "day": day}


def ex_open():
    app = random.choice(APPS)
    text = random.choice(["open {a}", "launch {a}", "start {a}", "{a} kholo", "go to {a}",
                          "can you open {a}", "{a} khulo", "open up {a}", "take me to {a}"]).format(a=app)
    return text, {"action": "open_app", "app": app}


def ex_call():
    c = random.choice(CONTACTS)
    text = random.choice(["call {c}", "phone {c}", "ring {c}", "{c} ko call karo", "{c} ke phone koro",
                          "give {c} a call", "dial {c}", "call {c} now", "make a call to {c}"]).format(c=c)
    return text, {"action": "call", "contact": c}


def ex_message():
    c, msg = random.choice(CONTACTS), random.choice(MSG_TEXTS)
    if random.random() < 0.3:
        app = "SMS"
        text = random.choice(["send {c} an SMS saying {m}", "SMS {c} {m}", "text {c} by SMS that {m}"]).format(c=c, m=msg)
    elif random.random() < 0.5:
        app = "WhatsApp"
        text = random.choice(["whatsapp {c} {m}", "send {c} a whatsapp saying {m}",
                              "tell {c} on whatsapp that {m}"]).format(c=c, m=msg)
    else:
        app = "WhatsApp"  # default when no app is named
        text = random.choice(["message {c} {m}", "text {c} saying {m}", "tell {c} {m}",
                              "{c} ko message bhejo {m}", "send a message to {c} that {m}"]).format(c=c, m=msg)
    return text, {"action": "message", "app": app, "contact": c, "text": msg}


def ex_tasker():
    t, state = random.choice(TASKS), random.choice(["on", "off"])
    text = random.choice(["turn {s} {t}", "turn {t} {s}", "switch {s} {t}", "switch {t} {s}",
                          "{t} {s}", "{t} {s} karo", "{t} {s} koro", "put {t} {s}"]).format(t=t, s=state)
    return text, {"action": "tasker", "task": f"{t} {state}"}


def ex_stop():
    text = random.choice(["stop listening", "go to sleep", "topshe off", "turn yourself off",
                          "that's all", "bye topshe", "stop", "chup karo", "thamo", "you can sleep now"])
    return text, {"action": "stop"}


def ex_chat():
    q = random.choice(CHAT_Q) if random.random() < 0.3 else random.choice(CHAT_TMPL).format(x=random.choice(TOPICS))
    return q, {"action": "chat", "query": q}


SONGS = ["Tum Hi Ho", "Blinding Lights", "Kesariya", "Shape of You", "Apna Bana Le", "Levitating",
         "Amar Shonar Bangla", "Ek Mulaqat", "Bohemian Rhapsody", "Channa Mereya", "Pasoori",
         "Hotel California", "Ami Je Tomar", "Kun Faya Kun", "Perfect", "Tere Bina", "Starboy"]
ARTISTS = ["Arijit Singh", "The Weeknd", "Anuv Jain", "Prateek Kuhad", "Coldplay", "Taylor Swift",
           "Shreya Ghoshal", "A R Rahman", "Anupam Roy", "Ed Sheeran", "Lata Mangeshkar", "Kishore Kumar"]


def ex_media():
    r = random.random()
    if r < 0.35:
        q = random.choice(SONGS + ARTISTS + [f"{s} by {a}" for s, a in zip(SONGS, ARTISTS)])
        text = random.choice(["play {q}", "play {q} on spotify", "put on {q}", "{q} chalao", "{q} bajao",
                              "i want to hear {q}", "can you play {q}"]).format(q=q)
        return text, {"action": "media", "op": "play", "query": q, "source": "spotify"}
    if r < 0.5:
        q = random.choice(["lofi beats", "the news", "a cooking video", "IPL highlights", "standup comedy",
                           "Satyajit Ray interview", "study music", random.choice(SONGS)])
        text = random.choice(["play {q} on youtube", "youtube pe {q} chalao", "open {q} on youtube",
                              "find {q} on youtube"]).format(q=q)
        return text, {"action": "media", "op": "play", "query": q, "source": "youtube"}
    op, phrases = random.choice([
        ("play", ["play some music", "play music", "resume the music", "music chalao", "gaana chalao"]),
        ("pause", ["pause the music", "pause it", "stop the song", "gaana band karo"]),
        ("next", ["next song", "skip this", "play the next one", "agla gaana"]),
        ("previous", ["previous song", "go back one song", "play the last song again"]),
        ("volume_up", ["turn it up", "louder please", "increase the volume", "awaaz badhao"]),
        ("volume_down", ["turn it down", "lower the volume", "awaaz kam karo"]),
        ("mute", ["mute", "mute the music"]),
    ])
    out = {"action": "media", "op": op}
    if op == "play":
        out.update({"query": "", "source": "spotify"})
    return random.choice(phrases), out


FACTS = ["my shuttle leaves at 8:10 in the morning", "my RA meeting is every Friday at 5 pm",
         "Rahul means Rahul Sen", "my sister is called Riya", "I like my coffee without sugar",
         "my Kaggle username is annayde", "Tanmay is my term paper partner", "my mother's birthday is on 12 March",
         "I take the 9 pm metro home", "my laptop password hint is the dog's name",
         "my favourite singer is Arijit Singh", "my gym days are Monday, Wednesday and Friday",
         "Professor Sen's office is in the AC 04 building", "my landlord is called Mr Gupta",
         "I'm vegetarian on Tuesdays", "my car is parked in slot B12"]


def ex_remember():
    f = random.choice(FACTS)
    if random.random() < 0.75:
        text = random.choice(["remember that {f}", "remember {f}", "note that {f}", "keep in mind {f}",
                              "yaad rakhna {f}", "save this: {f}", "don't forget that {f}"]).format(f=f)
        return text, {"action": "remember", "fact": f}
    text = random.choice(["forget that {f}", "forget about {f}", "you can forget {f}", "delete the note that {f}"]).format(f=f)
    return text, {"action": "forget", "fact": f}


# Commands that need the memory block to resolve ("call my sister" -> Riya)
MEMORY_CMDS = [
    (["my sister is called Riya"], ["call my sister", "phone my sister", "give my sister a call"], {"action": "call", "contact": "Riya"}),
    (["Rahul means Rahul Sen"], ["call Rahul", "ring Rahul"], {"action": "call", "contact": "Rahul Sen"}),
    (["my landlord is called Mr Gupta"], ["call my landlord", "phone the landlord"], {"action": "call", "contact": "Mr Gupta"}),
    (["Tanmay is my term paper partner"], ["message my term paper partner that I sent the draft"],
     {"action": "message", "app": "WhatsApp", "contact": "Tanmay", "text": "I sent the draft"}),
    (["my favourite singer is Arijit Singh"], ["play my favourite singer", "play songs by my favourite singer"],
     {"action": "media", "op": "play", "query": "Arijit Singh", "source": "spotify"}),
    (["my shuttle leaves at 8:10 in the morning"], ["remind me 15 minutes before my shuttle"],
     {"action": "set_reminder", "text": "catch the shuttle", "hour": 7, "minute": 55, "day": "next"}),
]

# Chat that should use the memory block
MEMORY_CHATS = [
    ("my shuttle leaves at 8:10 in the morning", ["when is my shuttle?", "what time is my shuttle"], "Your shuttle leaves at 8:10 in the morning."),
    ("my RA meeting is every Friday at 5 pm", ["when is my RA meeting?", "when's the RA meeting"], "Your RA meeting is every Friday at 5 pm."),
    ("my sister is called Riya", ["what's my sister's name?", "who is my sister"], "Your sister is Riya."),
    ("my mother's birthday is on 12 March", ["when is my mom's birthday?", "maa ka birthday kab hai"], "Your mother's birthday is on 12 March."),
    ("my car is parked in slot B12", ["where did I park?", "where's my car"], "Your car is in slot B12."),
    ("my gym days are Monday, Wednesday and Friday", ["which days do I go to the gym?"], "You go to the gym on Monday, Wednesday and Friday."),
    ("Professor Sen's office is in the AC 04 building", ["where is Professor Sen's office?"], "Professor Sen's office is in the AC 04 building."),
]


# Facts that change who a name refers to: never shown with generic commands (would contradict labels)
ALIAS_FACTS = {f for must, _, _ in MEMORY_CMDS for f in must}


def random_facts(k_max=6, must=None):
    pool = [f for f in FACTS if f not in (must or []) and f not in ALIAS_FACTS]
    facts = random.sample(pool, random.randint(0, k_max)) + list(must or [])
    random.shuffle(facts)
    return facts


GENERATORS = [(ex_alarm, 600), (ex_timer, 350), (ex_reminder, 600), (ex_open, 450), (ex_call, 400),
              (ex_message, 600), (ex_tasker, 350), (ex_stop, 100), (ex_chat, 600), (ex_media, 700),
              (ex_remember, 300)]


def noisy(text):
    """Make text look like speech-to-text output."""
    if random.random() < 0.15:
        text = random.choice(["topshe ", "hey topshe ", "please ", "umm ", "ok "]) + text
    if random.random() < 0.1:
        text += random.choice([" please", " thanks", " now"])
    text = text.replace("?", "") if random.random() < 0.5 else text
    return text.lower() if random.random() < 0.6 else text


def row(system, user, assistant, kind):
    return {"system": system, "user": user, "assistant": assistant, "kind": kind}


def make_rows(seed=42):
    """Synthetic training rows: dicts with system / user / assistant / kind."""
    random.seed(seed)
    rows, seen = [], set()
    for gen, n in GENERATORS:
        made = tries = 0
        while made < n and tries < n * 30:
            tries += 1
            text, out = gen()
            text = " ".join(noisy(text).split())
            if text in seen:
                continue
            seen.add(text)
            # 40% of commands see a memory block (the app always sends it when facts exist)
            facts = random_facts() if random.random() < 0.4 else []
            rows.append(row(with_facts(CMD_SYSTEM, facts), text, dumps(out), "command"))
            made += 1
    for _ in range(15):
        for must, phrasings, out in MEMORY_CMDS:
            for p in phrasings:
                rows.append(row(with_facts(CMD_SYSTEM, random_facts(5, must)), noisy(p), dumps(out), "memory_command"))
    for _ in range(10):
        for fact, questions, answer in MEMORY_CHATS:
            for q in questions:
                rows.append(row(with_facts(CHAT_SYSTEM, random_facts(5, [fact])), noisy(q), answer, "memory_chat"))
    random.shuffle(rows)
    return rows


def user_rows(interactions, facts):
    """
    Turns the phone's interaction log into training rows. Only trusted examples:
      - ok / confirmed commands (Topshe did it and you didn't object)
      - corrections ("no, I meant ...") -> the corrected action
    Cancelled actions and Topshe's own chat answers are never trained on.
    """
    final = {}   # last word wins: a later correction or cancel overrides an earlier "ok"
    for it in interactions:
        heard = (it.get("heard") or "").strip()
        if heard:
            final[heard.lower()] = (heard, it)
    out = []
    for heard, it in final.values():
        outcome = it.get("outcome")
        action = it.get("corrected_to") if outcome == "corrected" else it.get("action")
        if outcome not in ("ok", "confirmed", "corrected") or not isinstance(action, dict):
            continue
        if action.get("action") == "chat":
            continue
        out.append(row(with_facts(CMD_SYSTEM, facts), heard, dumps(action), "user"))
    return out


if __name__ == "__main__":
    import collections
    rows = make_rows()
    print(len(rows), collections.Counter(r["kind"] for r in rows))
    print(collections.Counter(json.loads(r["assistant"])["action"] for r in rows if r["kind"] != "memory_chat"))
    for r in rows[:8]:
        print(repr(r["user"]), "=>", r["assistant"])
    demo = [{"heard": "wake me at 7", "action": {"action": "set_alarm", "hour": 7, "minute": 0, "day": "next"}, "outcome": "ok"},
            {"heard": "call rahul", "action": {"action": "call", "contact": "Rahul"}, "outcome": "corrected",
             "corrected_to": {"action": "call", "contact": "Rahul Sen"}},
            {"heard": "message maa hi", "action": {"action": "message"}, "outcome": "cancelled"},
            {"heard": "call rahul", "action": {"action": "call", "contact": "Rahul"}, "outcome": "ok"}]
    for r in user_rows(demo, ["Rahul means Rahul Sen"]):
        print("USER ROW:", r["user"], "=>", r["assistant"])
