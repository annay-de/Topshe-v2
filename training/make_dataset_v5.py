import json
import random
import re

random.seed(42)

"""
Alfred training data v5 (v4 renamed: the assistant is now called Alfred).

Shared by the first training run and every weekly retrain. The Android app uses the
SAME system prompts (Brain.kt), so keep them in sync:
  - CMD_SYSTEM  + optional facts block  -> one compact JSON action
  - CHAT_SYSTEM + optional facts block  -> a short spoken answer
"""

CMD_SYSTEM = (
    "You are Topshe, a phone assistant. Turn the user's request into ONE JSON object. "   # kept as Topshe: the phone sends this exact text
    "Actions: set_alarm, set_timer, set_reminder, open_app, call, message, tasker, media, "
    "remember, forget, stop, chat. Use chat for questions or small talk. Reply with JSON only."
)
CHAT_SYSTEM = (
    "You are Alfred, a friendly assistant. Answer in 1-3 short, simple sentences. "
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


# ---- Two hearings (v4) -----------------------------------------------------------------------
# The phone runs two speech models on every request: Whisper (English) and a Bengali model.
# The brain sees both and learns which one makes sense. MUST match Hearing.forBrain() in the app.
HEARING = "dual-v1"


def hearing(en, bn):
    return f"english: {en}\nbangla: {bn}"


# What each model makes of the OTHER language (real outputs, from training/eval_asr_bn.py).
# Replaced by the much larger data/hearing_pool.jsonl when it is available (set_pool()).
FILLER_BN_FOR_EN = ["উইক মিয়াপ অ্যাডসেক্স ফার্টি ফাইভ টু মরো", "সেট আর টাইমার ফর ট্যাঙ্ক ম্যানেজ", "কল মা",
                    "রিমান্ড মিটু কল বাবা অ্যাড এইট পিএম", "তপসি প্লেই তুমহী হো অন স্পটে ফাই", "গল মা",
                    "স্যাটেল টাইম অফ ও ট্যান ম্যানেজ", "রেমাইন মিডু কল বাবা আট এড পিএম", "টপশি প্লে তুমি হো অন স্পোর্টায়",
                    "উইকমিয়াট সিক্স ফার্টি ফাইভ ড মরো"]
FILLER_EN_FOR_BN = ["Topo se makifun kor", "Make Akbar Phone Coro", "Baba's message pathhaja ami berikore firbo.",
                    "Take a small piece of paper and put it on top.", "10 minutes timer Lada", "Sparifate tum hi ho chala",
                    "Danta Bondhukar", "Poregantachala", "Then go.", "Now let's go to the bar.", "YouTube Call",
                    "Monerakis Amarjim Shon Deshaktai.", "Or is it single gun?", "5-6 of the stones.",
                    "Today's Bye-Re-Common Thanda.", "Here we die.", "Nana Onnota", "Acon tube core", "Good morning",
                    "Shukrobar Pasta Ramirez Kotha Monaco Riedis.", "Ria's WhatsApp link to Amia's Sheet."]
POOL = []


def set_pool(items):
    """Uses real hearings (data/hearing_pool.jsonl) for training rows and as filler."""
    global POOL, FILLER_BN_FOR_EN, FILLER_EN_FOR_BN
    POOL = [it for it in items if it.get("en") is not None and it.get("bn") is not None]
    bn_for_en = [it["bn"] for it in POOL if it["lang"] == "en" and it["bn"]]
    en_for_bn = [it["en"] for it in POOL if it["lang"] == "bn" and it["en"]]
    if len(bn_for_en) > 50:
        FILLER_BN_FOR_EN = bn_for_en
    if len(en_for_bn) > 50:
        FILLER_EN_FOR_BN = en_for_bn


def as_heard(text, lang, rnd=random):
    """A typed/clean request as the brain sees it: 85% both hearings, 15% the old single line."""
    if rnd.random() < 0.15 and lang == "en":
        return text
    if lang == "bn":
        return hearing(rnd.choice(FILLER_EN_FOR_BN), text)
    return hearing(text, rnd.choice(FILLER_BN_FOR_EN))

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
          "Translate thank you to Bengali", "Thank you Alfred", "Suggest a good book",
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
    text = random.choice(["stop listening", "go to sleep", "alfred off", "turn yourself off",
                          "that's all", "bye alfred", "stop", "chup karo", "thamo", "you can sleep now"])
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
                              "i want to hear {q}", "can you play {q}", "{q} er gaan chalao", "{q} chalao na",
                              "spotify te {q} chalao"]).format(q=q)
        return text, {"action": "media", "op": "play", "query": q, "source": "spotify"}
    if r < 0.5:
        q = random.choice(["lofi beats", "the news", "a cooking video", "IPL highlights", "standup comedy",
                           "Satyajit Ray interview", "study music", random.choice(SONGS)])
        text = random.choice(["play {q} on youtube", "youtube pe {q} chalao", "open {q} on youtube",
                              "find {q} on youtube"]).format(q=q)
        return text, {"action": "media", "op": "play", "query": q, "source": "youtube"}
    op, phrases = random.choice([
        ("play", ["play some music", "play music", "resume the music", "music chalao", "gaana chalao", "gaan chalao"]),
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


# Who Alfred is (written for Alfred; answers kept short, like Annay wants)
IDENTITY_CHATS = [
    (["who are you", "what are you", "tumi ke", "introduce yourself"],
     "I'm Alfred, your assistant. I play music, set alarms and reminders, call or message people, open apps and answer questions."),
    (["what can you do", "what all can you do", "help"],
     "I can play music, set alarms, timers and reminders, call or message people, open apps, run Tasker tasks, remember things for you and answer questions."),
    (["who made you", "who built you"], "Annay built me."),
    (["are you listening", "you there", "alfred are you there"], "Yes, I'm here."),
    (["thank you", "thanks alfred", "thanks"], "Anytime."),
    (["good night alfred", "good night"], "Good night, Annay."),
]

# Held-out memory checks (facts NEVER used in training): does Alfred use what it was told?
EVAL_MEMORY_CHATS = [
    ("my dentist appointment is on Thursday at 4 pm", "when is my dentist appointment?", "4"),
    ("my bike is a blue Hero Splendor", "what bike do I have?", "splendor"),
    ("my favourite food is kosha mangsho", "what's my favourite food?", "kosha"),
    ("Riju is my cousin", "who is Riju?", "cousin"),
    ("my flight to Kolkata is on 18 December", "when is my flight?", "18"),
    ("my locker code hint is my roll number", "what's my locker code hint?", "roll"),
    ("my thesis advisor is Professor Mehta", "who is my thesis advisor?", "mehta"),
    ("I keep my passport in the blue folder", "where is my passport?", "blue"),
]
EVAL_MEMORY_CMDS = [
    ("my cousin is called Riju", "call my cousin", {"action": "call", "contact": "Riju"}),
    ("my advisor is Professor Mehta", "message my advisor that I'll be late",
     {"action": "message", "app": "WhatsApp", "contact": "Professor Mehta", "text": "I'll be late"}),
    ("my favourite band is Coldplay", "play my favourite band",
     {"action": "media", "op": "play", "query": "Coldplay", "source": "spotify"}),
]


def random_facts(k_max=6, must=None):
    pool = [f for f in FACTS if f not in (must or []) and f not in ALIAS_FACTS]
    facts = random.sample(pool, random.randint(0, k_max)) + list(must or [])
    random.shuffle(facts)
    return facts


# ============================================================================================
# Bengali and Banglish (v4). What the Bengali speech model hears: Bengali script, with English
# words (apps, some song names) usually in Latin letters. Informal (tui), friendly (tumi) and
# formal (apni) verb forms all map to the same action. Names map to how contacts are saved
# (Latin letters); song and artist names map to their usual Latin spelling for Spotify search.
# Message, reminder and memory text stays in Bengali, as said.
# ============================================================================================

BN_CONTACTS = {"Maa": "মা", "Baba": "বাবা", "Tanmay": "তন্ময়", "Rahul": "রাহুল", "Priya": "প্রিয়া", "Didi": "দিদি",
               "Dada": "দাদা", "Arjun": "অর্জুন", "Sneha": "স্নেহা", "Professor Sen": "প্রফেসর সেন", "Ankit": "অঙ্কিত",
               "Riya": "রিয়া", "Mama": "মামা", "Kaku": "কাকু", "Shreya": "শ্রেয়া", "Sourav": "সৌরভ", "Ritwik": "ঋত্বিক",
               "Pishi": "পিসি", "Jethu": "জেঠু", "Anirban": "অনির্বাণ", "Debjani": "দেবযানী", "Rohan": "রোহন",
               "Ishita": "ইশিতা", "Sayan": "সায়ন", "Moumita": "মৌমিতা"}
BN_APPS = {"WhatsApp": "হোয়াটসঅ্যাপ", "YouTube": "ইউটিউব", "Spotify": "স্পটিফাই", "Chrome": "ক্রোম", "Gmail": "জিমেইল",
           "Maps": "ম্যাপস", "Camera": "ক্যামেরা", "Instagram": "ইনস্টাগ্রাম", "Telegram": "টেলিগ্রাম", "Settings": "সেটিংস",
           "Calculator": "ক্যালকুলেটর", "Calendar": "ক্যালেন্ডার", "Zomato": "জোম্যাটো", "Swiggy": "সুইগি", "Uber": "উবার",
           "Google Pay": "গুগল পে", "PhonePe": "ফোনপে", "Netflix": "নেটফ্লিক্স", "Hotstar": "হটস্টার",
           "Photos": "ফটোস", "Clock": "ঘড়ি", "Amazon": "অ্যামাজন", "Flipkart": "ফ্লিপকার্ট", "Zerodha Kite": "জেরোধা কাইট"}
# (Bengali script as heard, canonical name for search)
BN_MUSIC = [("তুম হি হো", "Tum Hi Ho"), ("কেসরিয়া", "Kesariya"), ("আপনা বানা লে", "Apna Bana Le"),
            ("আমার সোনার বাংলা", "Amar Shonar Bangla"), ("এক মুলাকাত", "Ek Mulaqat"), ("চন্না মেরেয়া", "Channa Mereya"),
            ("পসুরি", "Pasoori"), ("আমি যে তোমার", "Ami Je Tomar"), ("কুন ফায়া কুন", "Kun Faya Kun"),
            ("তেরে বিনা", "Tere Bina"), ("অরিজিৎ সিং", "Arijit Singh"), ("অনুভ জৈন", "Anuv Jain"),
            ("প্রতীক কুহাড়", "Prateek Kuhad"), ("শ্রেয়া ঘোষাল", "Shreya Ghoshal"), ("এ আর রহমান", "A R Rahman"),
            ("অনুপম রায়", "Anupam Roy"), ("লতা মঙ্গেশকর", "Lata Mangeshkar"), ("কিশোর কুমার", "Kishore Kumar"),
            ("রবীন্দ্রসংগীত", "Rabindrasangeet"), ("নচিকেতা", "Nachiketa"), ("কবীর সুমন", "Kabir Suman"),
            ("মহীনের ঘোড়াগুলি", "Moheener Ghoraguli"), ("চন্দ্রবিন্দু", "Chandrabindoo"), ("ফসিলস", "Fossils")]
BN_MUSIC_LATIN = ["Blinding Lights", "Shape of You", "Levitating", "Bohemian Rhapsody", "Hotel California",
                  "Perfect", "Starboy", "Coldplay", "The Weeknd", "Taylor Swift", "Ed Sheeran"]

BN_NUM = {1: "এক", 2: "দুই", 3: "তিন", 4: "চার", 5: "পাঁচ", 6: "ছয়", 7: "সাত", 8: "আট", 9: "নয়", 10: "দশ",
          11: "এগারো", 12: "বারো", 13: "তেরো", 14: "চোদ্দ", 15: "পনেরো", 16: "ষোলো", 17: "সতেরো", 18: "আঠারো",
          19: "উনিশ", 20: "কুড়ি", 25: "পঁচিশ", 30: "তিরিশ", 35: "পঁয়ত্রিশ", 40: "চল্লিশ", 45: "পঁয়তাল্লিশ",
          50: "পঞ্চাশ", 55: "পঞ্চান্ন", 60: "ষাট", 90: "নব্বই"}
BN_DIGITS = str.maketrans("0123456789", "০১২৩৪৫৬৭৮৯")
BN_HOUR_TA = {1: "একটা", 2: "দুটো", 3: "তিনটে", 4: "চারটে", 5: "পাঁচটা", 6: "ছটা", 7: "সাতটা", 8: "আটটা",
              9: "নটা", 10: "দশটা", 11: "এগারোটা", 12: "বারোটা"}
BN_HOUR_AT = {1: "একটায়", 2: "দুটোয়", 3: "তিনটেয়", 4: "চারটেয়", 5: "পাঁচটায়", 6: "ছটায়", 7: "সাতটায়", 8: "আটটায়",
              9: "নটায়", 10: "দশটায়", 11: "এগারোটায়", 12: "বারোটায়"}
BN_MIN_AT = {5: "পাঁচে", 10: "দশে", 20: "কুড়িতে", 25: "পঁচিশে", 35: "পঁয়ত্রিশে", 40: "চল্লিশে",
             45: "পঁয়তাল্লিশে", 50: "পঞ্চাশে", 55: "পঞ্চান্নতে"}


def bn_daypart(h):
    if 4 <= h <= 5:
        return "ভোর"
    if 6 <= h <= 11:
        return "সকাল"
    if 12 <= h <= 14:
        return "দুপুর"
    if 15 <= h <= 17:
        return "বিকেল"
    if 18 <= h <= 19:
        return random.choice(["সন্ধে", "সন্ধ্যা"])
    return "রাত"


def bn_time(h, m):
    """A Bengali spoken time for 24-hour h:m. The day part (সকাল, রাত...) makes the hour unambiguous."""
    dp = bn_daypart(h)
    h12 = h % 12 or 12
    nxt = h12 % 12 + 1
    r = random.random()
    if r < 0.15:   # the speech model sometimes writes digits
        digits = f"{h12}:{m:02d}"
        return f"{dp} " + (digits.translate(BN_DIGITS) if random.random() < 0.5 else digits) + "-এ"
    if m == 0:
        return f"{dp} {BN_HOUR_AT[h12]}"
    if m == 30:
        special = {1: "দেড়টায়", 2: "আড়াইটায়"}
        return f"{dp} " + (special[h12] if h12 in special else f"সাড়ে {BN_HOUR_AT[h12]}")
    if m == 15:
        return f"{dp} সোয়া {BN_HOUR_AT[h12]}"
    if m == 45:
        return random.choice([f"{dp} পৌনে {BN_HOUR_AT[nxt]}", f"{dp} {BN_HOUR_TA[h12]} {BN_MIN_AT[45]}"])
    return f"{dp} {BN_HOUR_TA[h12]} {BN_MIN_AT[m]}"


def bn_name(canon):
    """A contact as heard: Bengali script (mostly) or Latin letters."""
    return BN_CONTACTS[canon] if random.random() < 0.8 else canon


def bn_obj(name):
    """Object form: মা -> মাকে / মা-কে."""
    return name + random.choice(["কে", "কে", "-কে"])


def bn_app(canon):
    return BN_APPS[canon] if canon in BN_APPS and random.random() < 0.4 else canon


def bn_loc(word):
    """Locative ("on/in"): Spotify -> Spotify-তে / Spotify-এ, স্পটিফাই -> স্পটিফাইতে, ইউটিউব -> ইউটিউবে."""
    if word.isascii():
        return word + random.choice(["-তে", "-এ"])
    return word + ("তে" if word[-1] in "ািীুূেৈোৌঅআইঈউঊএঐওঔ" else "ে")


def ex_bn_alarm():
    h = random.randint(4, 23)
    m = random.choice([0, 0, 15, 30, 30, 45, 45, random.choice([5, 10, 20, 25, 35, 40, 50, 55])])
    dword, day = random.choice([("কাল", "tomorrow"), ("কাল", "tomorrow"), ("আজ", "today"), ("", "next")])
    t = bn_time(h, m)
    text = random.choice([
        "{d} {t} অ্যালার্ম দে", "{d} {t} অ্যালার্ম দাও", "{d} {t} অ্যালার্ম লাগা", "{d} {t} একটা অ্যালার্ম লাগাও",
        "{d} {t} অ্যালার্ম সেট কর", "{d} {t} অ্যালার্ম সেট করুন", "{d} {t} তুলে দিস", "{d} {t} তুলে দিও",
        "{d} {t} ডেকে দিস", "{d} {t} উঠিয়ে দিও", "{d} {t} alarm দে",
    ]).format(d=dword, t=t)
    return text, {"action": "set_alarm", "hour": h, "minute": m, "day": day}


def ex_bn_timer():
    r = random.random()
    if r < 0.12:
        text, secs = random.choice([("আধ ঘণ্টার টাইমার দে", 1800), ("দেড় ঘণ্টার টাইমার লাগা", 5400),
                                    ("আধ ঘণ্টা গোন", 1800), ("এক ঘণ্টার টাইমার দাও", 3600)])
        return text, {"action": "set_timer", "seconds": secs}
    unit, mult, gen = random.choice([("মিনিট", 60, "মিনিটের"), ("মিনিট", 60, "মিনিটের"), ("মিনিট", 60, "মিনিটের"),
                                     ("সেকেন্ড", 1, "সেকেন্ডের"), ("ঘণ্টা", 3600, "ঘণ্টার")])
    pool = [1, 2, 3] if unit == "ঘণ্টা" else [n for n in BN_NUM if n <= 90]
    n = random.choice(pool)
    word = BN_NUM[n] if random.random() < 0.7 else (str(n).translate(BN_DIGITS) if random.random() < 0.5 else str(n))
    text = random.choice(["{n} {g} টাইমার দে", "{n} {g} টাইমার লাগা", "{n} {g} টাইমার লাগাও", "{n} {g} একটা টাইমার দাও",
                          "{n} {u} টাইমার চালু কর", "{n} {u} গোন", "{n} {g} timer দে"]).format(n=word, g=gen, u=unit)
    return text, {"action": "set_timer", "seconds": n * mult}


BN_REMIND = [("অ্যাসাইনমেন্ট জমা দিতে", "অ্যাসাইনমেন্ট জমা দেওয়া"), ("মাকে ফোন করতে", "মাকে ফোন করা"),
             ("শাটল ধরতে", "শাটল ধরা"), ("দুধ কিনতে", "দুধ কেনা"), ("কারেন্টের বিল দিতে", "কারেন্টের বিল দেওয়া"),
             ("ওষুধ খেতে", "ওষুধ খাওয়া"), ("জল খেতে", "জল খাওয়া"), ("জিমে যেতে", "জিমে যাওয়া"),
             ("ল্যাপটপ চার্জ দিতে", "ল্যাপটপ চার্জ দেওয়া"), ("প্রফেসরকে রিপ্লাই করতে", "প্রফেসরকে রিপ্লাই করা"),
             ("RA meeting-এ যেতে", "RA meeting"), ("পেপারটা পড়তে", "পেপারটা পড়া"), ("ক্যাব বুক করতে", "ক্যাব বুক করা"),
             ("Stata রানটা চালাতে", "Stata রান চালানো")]


def ex_bn_reminder():
    said, what = random.choice(BN_REMIND)
    if random.random() < 0.45:
        n = random.choice([5, 10, 15, 20, 30, 45, 60, 90])
        nw = BN_NUM[n] if random.random() < 0.75 else str(n)
        text = random.choice(["{n} মিনিট পরে আমাকে {w} মনে করিয়ে দিস", "{n} মিনিট পর {w} মনে করিয়ে দিও",
                              "{n} মিনিট পরে মনে করাবি {w}", "{n} মিনিট পরে {w} মনে করিয়ে দেবেন"]).format(n=nw, w=said)
        return text, {"action": "set_reminder", "text": what, "in_minutes": n}
    h, m = random.randint(6, 23), random.choice([0, 15, 30, 45])
    dword, day = random.choice([("কাল", "tomorrow"), ("আজ", "today")])
    text = random.choice(["{d} {t} আমাকে {w} মনে করিয়ে দিস", "{d} {t} {w} মনে করিয়ে দিও",
                          "{d} {t} মনে করাবি {w}", "{d} {t} {w} রিমাইন্ড করিস"]).format(d=dword, t=bn_time(h, m), w=said)
    return text, {"action": "set_reminder", "text": what, "hour": h, "minute": m, "day": day}


def ex_bn_open():
    app = random.choice(list(BN_APPS))
    text = random.choice(["{a} খোল", "{a} খোলো", "{a} খুলুন", "{a} খুলে দে", "{a} টা খোল", "{a} চালু কর",
                          "একটু {a} খোলো তো"]).format(a=bn_app(app))
    return " ".join(text.split()), {"action": "open_app", "app": app}


def ex_bn_call():
    c = random.choice(list(BN_CONTACTS))
    if random.random() < 0.15:   # Banglish in Latin letters
        text = random.choice(["{c} ke phone kor", "{c} ke call de", "{c} ke phone koro", "{c} ke ekbar phone kor"]).format(c=c.lower())
        return text, {"action": "call", "contact": c}
    text = random.choice(["{o} ফোন কর", "{o} ফোন করো", "{o} ফোন করুন", "{o} একবার ফোন কর", "{o} কল কর",
                          "{o} কল দে", "{o} ফোন লাগা", "{o} call কর", "আলফ্রেড, {o} ফোন কর"]).format(o=bn_obj(bn_name(c)))
    return text, {"action": "call", "contact": c}


BN_MSGS = ["আমি দেরি করে ফিরব", "বাড়ি পৌঁছে গেছি", "ফ্রি হলে ফোন করিস", "আমি আসছি", "দশ মিনিট দেরি হবে",
           "কাল দেখা করবি?", "ফাইলটা পাঠিয়ে দিয়েছি", "শুভ জন্মদিন", "আমি এখন ক্লাসে আছি", "রাতে নটায় খাবি?",
           "ড্রাফট রেডি", "পরে ফোন করছি", "মিটিংয়ে আছি", "শাটলে উঠেছি", "খেয়ে নিয়েছি"]


def ex_bn_message():
    c, msg = random.choice(list(BN_CONTACTS)), random.choice(BN_MSGS)
    o = bn_obj(bn_name(c))
    if random.random() < 0.25:
        app = "SMS"
        text = random.choice(["{o} SMS কর যে {m}", "{o} এসএমএস পাঠা যে {m}", "{o} একটা SMS পাঠাও {m}"]).format(o=o, m=msg)
    else:
        app = "WhatsApp"
        wa = bn_loc(random.choice(["WhatsApp", "হোয়াটসঅ্যাপ"])) if random.random() < 0.4 else ""
        text = random.choice(["{o} {wa} মেসেজ পাঠা যে {m}", "{o} {wa} লেখ যে {m}", "{o} {wa} লেখো {m}",
                              "{o} বলে দে যে {m}", "{o} {wa} মেসেজ করো যে {m}", "{o} {wa} message কর {m}"]).format(o=o, wa=wa, m=msg)
    return " ".join(text.split()), {"action": "message", "app": app, "contact": c, "text": msg}


BN_TASKS = {"wifi": ["ওয়াইফাই", "WiFi"], "bluetooth": ["ব্লুটুথ", "Bluetooth"], "silent mode": ["সাইলেন্ট মোড"],
            "dnd": ["ডিএনডি", "DND"], "torch": ["টর্চ"], "battery saver": ["ব্যাটারি সেভার"], "hotspot": ["হটস্পট"],
            "dark mode": ["ডার্ক মোড"], "mobile data": ["মোবাইল ডেটা", "নেট"], "location": ["লোকেশন"],
            "airplane mode": ["এরোপ্লেন মোড", "ফ্লাইট মোড"]}


def ex_bn_tasker():
    t, state = random.choice(list(BN_TASKS)), random.choice(["on", "off"])
    word = random.choice(BN_TASKS[t])
    if t == "torch" and random.random() < 0.5:
        verb = "জ্বালা" if state == "on" else "নেভা"
    else:
        verb = random.choice(["চালু কর", "চালু করো", "অন কর"] if state == "on" else ["বন্ধ কর", "বন্ধ করো", "অফ কর"])
    return f"{word} {verb}", {"action": "tasker", "task": f"{t} {state}"}


def ex_bn_stop():
    text = random.choice(["চুপ কর", "চুপ করো", "এখন চুপ কর", "থাম", "থামো", "এবার ঘুমো", "ঘুমিয়ে পড়",
                          "আলফ্রেড অফ", "শোনা বন্ধ কর", "আর শুনতে হবে না", "ব্যস, হয়ে গেছে", "এবার তুই যা",
                          "ঠিক আছে, এবার বিশ্রাম নে"])
    return text, {"action": "stop"}


def ex_bn_media():
    r = random.random()
    if r < 0.4:
        if random.random() < 0.75:
            heard, q = random.choice(BN_MUSIC)
        else:
            heard = q = random.choice(BN_MUSIC_LATIN)
        sp = bn_loc(random.choice(["Spotify", "স্পটিফাই"])) if random.random() < 0.35 else ""
        text = random.choice(["{sp} {h} চালা", "{sp} {h} চালাও", "{sp} {h} বাজা", "{h} বাজাও", "{sp} {h} লাগা",
                              "{h}-এর গান চালা", "{h}-এর গান চালাও", "একটু {h} শোনা", "{sp} {h} চালান", "{h} play কর"]).format(sp=sp, h=heard)
        return " ".join(text.split()), {"action": "media", "op": "play", "query": q, "source": "spotify"}
    if r < 0.52:
        heard, q = random.choice([("রান্নার ভিডিও", "রান্নার ভিডিও"), ("IPL highlights", "IPL highlights"),
                                  ("বাংলা খবর", "বাংলা খবর"), ("lofi", "lofi"), ("স্ট্যান্ড আপ কমেডি", "standup comedy"),
                                  ("সত্যজিৎ রায়ের ইন্টারভিউ", "Satyajit Ray interview"), ("পড়ার মিউজিক", "study music")])
        yt = bn_loc(random.choice(["YouTube", "ইউটিউব"]))
        text = random.choice(["{y} {h} চালা", "{y} {h} দেখা", "{y} {h} খোঁজ", "{y} {h} চালাও"]).format(y=yt, h=heard)
        return text, {"action": "media", "op": "play", "query": q, "source": "youtube"}
    op, phrases = random.choice([
        ("play", ["গান চালা", "গান চালাও", "কিছু একটা গান চালা", "মিউজিক চালু কর", "গান লাগা", "আবার গান চালাও"]),
        ("pause", ["গানটা বন্ধ কর", "গান থামা", "গানটা থামাও", "পজ কর", "গানটা পজ করো", "মিউজিক বন্ধ করুন"]),
        ("next", ["পরের গান", "পরেরটা চালা", "স্কিপ কর", "এটা বাদ দে", "পরের গানটা চালাও"]),
        ("previous", ["আগের গানটা চালা", "আগেরটা আবার চালা", "আগের গানে ফিরে যা"]),
        ("volume_up", ["আওয়াজ বাড়া", "আওয়াজ একটু বাড়াও", "ভলিউম বাড়া", "জোরে কর"]),
        ("volume_down", ["আওয়াজ কমা", "আওয়াজ একটু কমাও", "ভলিউম কমা", "আস্তে কর"]),
        ("mute", ["মিউট কর", "মিউট করে দাও"]),
    ])
    out = {"action": "media", "op": op}
    if op == "play":
        out.update({"query": "", "source": "spotify"})
    return random.choice(phrases), out


BN_FACTS = ["আমার জিম সন্ধে সাতটায়", "আমার বোনের নাম রিয়া", "আমার শাটল সকাল আটটা দশে ছাড়ে",
            "মায়ের জন্মদিন বারোই মার্চ", "আমার গাড়ি B12 স্লটে রাখা", "আমি চিনি ছাড়া কফি খাই",
            "শুক্রবার পাঁচটায় RA meeting", "তন্ময় আমার টার্ম পেপারের পার্টনার"]


def ex_bn_remember():
    f = random.choice(BN_FACTS)
    if random.random() < 0.75:
        text = random.choice(["মনে রাখিস যে {f}", "মনে রাখো {f}", "এটা মনে রাখ: {f}", "নোট করে রাখ যে {f}",
                              "মনে রাখবেন {f}"]).format(f=f)
        return text, {"action": "remember", "fact": f}
    text = random.choice(["ভুলে যা যে {f}", "ভুলে যাও {f}", "{f}, এটা ভুলে যা", "ওই নোটটা মুছে দে যে {f}"]).format(f=f)
    return text, {"action": "forget", "fact": f}


BN_CHAT_Q = ["পথের পাঁচালী কে বানিয়েছিল?", "সূর্য কত দূরে?", "একটা জোক বল", "কেমন আছিস?", "রবীন্দ্রনাথ ঠাকুর কে?",
             "ইনফ্লেশন কী, সহজ করে বলো", "সতেরো গুণ তেইশ কত?", "একটা ভালো বই বলো", "আকাশ নীল কেন?",
             "পি-ভ্যালু কী?", "গুড মর্নিং", "আজ রাতে কী রান্না করব?", "ফেলুদা কে?", "সুন্দরবন কোথায়?",
             "নেতাজি কে ছিলেন?", "একটা মজার তথ্য বল", "চাঁদ কত দূরে?", "জাপানের রাজধানী কী?",
             "আজকে বাইরে কেমন ঠান্ডা?", "লিপ ইয়ারে কত দিন?", "দুশো চল্লিশের পনেরো শতাংশ কত?"]
BN_TOPICS = ["ব্ল্যাক হোল", "মুঘল সাম্রাজ্য", "সত্যজিৎ রায়", "সালোকসংশ্লেষ", "মুদ্রাস্ফীতি", "মেশিন লার্নিং",
             "শেয়ার বাজার", "দুর্গাপুজো", "ক্রিকেট", "জলবায়ু পরিবর্তন", "গেম থিওরি", "ফরাসি বিপ্লব", "বর্ষা",
             "জিএসটি", "আইপিএল", "ডিএনএ", "রিগ্রেশন", "মিউচুয়াল ফান্ড"]
BN_CHAT_TMPL = ["{x} কী?", "{x} নিয়ে কিছু বল", "{x} সহজ করে বোঝা", "{x} নিয়ে একটা তথ্য বলো", "{x} কেন দরকারি?"]


def ex_bn_chat():
    q = random.choice(BN_CHAT_Q) if random.random() < 0.4 else random.choice(BN_CHAT_TMPL).format(x=random.choice(BN_TOPICS))
    return q, {"action": "chat", "query": q}


BN_IDENTITY_CHATS = [
    (["তুই কে", "তুমি কে", "তোমার নাম কী", "নিজের সম্পর্কে বলো"],
     "আমি আলফ্রেড, তোমার অ্যাসিস্ট্যান্ট। গান চালাই, অ্যালার্ম আর রিমাইন্ডার দিই, ফোন বা মেসেজ করি, অ্যাপ খুলি আর প্রশ্নের উত্তর দিই।"),
    (["তুমি কী কী পারো", "তুই কী করতে পারিস"],
     "গান চালাতে পারি, অ্যালার্ম, টাইমার আর রিমাইন্ডার দিতে পারি, ফোন বা মেসেজ করতে পারি, অ্যাপ খুলতে পারি, কথা মনে রাখতে পারি আর প্রশ্নের উত্তর দিতে পারি।"),
    (["তোকে কে বানিয়েছে", "তোমাকে কে বানিয়েছে"], "আমাকে অনয় বানিয়েছে।"),
    (["শুনছিস", "আছিস?", "আলফ্রেড শুনছো"], "হ্যাঁ, শুনছি।"),
    (["ধন্যবাদ", "থ্যাঙ্ক ইউ আলফ্রেড", "থ্যাংকস"], "যখন খুশি।"),
    (["শুভ রাত্রি", "গুড নাইট আলফ্রেড"], "শুভ রাত্রি, অনয়।"),
]

# Bengali questions for the self-distilled chat set (the base model answers them in Bengali, once)
BN_CHAT_DISTILL = BN_CHAT_Q + [t.format(x=x) for t in BN_CHAT_TMPL[:3] for x in BN_TOPICS]

BN_GENERATORS = [(ex_bn_alarm, 350), (ex_bn_timer, 200), (ex_bn_reminder, 300), (ex_bn_open, 200), (ex_bn_call, 300),
                 (ex_bn_message, 350), (ex_bn_tasker, 150), (ex_bn_stop, 60), (ex_bn_chat, 300), (ex_bn_media, 450),
                 (ex_bn_remember, 150)]


GENERATORS = [(ex_alarm, 600), (ex_timer, 350), (ex_reminder, 600), (ex_open, 450), (ex_call, 400),
              (ex_message, 600), (ex_tasker, 350), (ex_stop, 100), (ex_chat, 600), (ex_media, 700),
              (ex_remember, 300)]


def noisy(text):
    """Make text look like speech-to-text output."""
    if random.random() < 0.15:
        text = random.choice(["alfred ", "hey alfred ", "please ", "umm ", "ok "]) + text
    if random.random() < 0.1:
        text += random.choice([" please", " thanks", " now"])
    text = text.replace("?", "") if random.random() < 0.5 else text
    return text.lower() if random.random() < 0.6 else text


def noisy_bn(text):
    """Speech-to-text-like variation for Bengali."""
    if random.random() < 0.15:
        text = random.choice(["আলফ্রেড, ", "আলফ্রেড ", "এই আলফ্রেড, ", "আচ্ছা ", "শোন, "]) + text
    if random.random() < 0.08:
        text += random.choice([" প্লিজ", " তো", " এখনই"])
    if random.random() < 0.5:
        text = text.replace("?", "").replace("।", "")
    return text.lower() if random.random() < 0.3 else text   # only affects Latin letters


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
            rows.append(row(with_facts(CMD_SYSTEM, facts), as_heard(text, "en"), dumps(out), "command"))
            made += 1
    for gen, n in BN_GENERATORS:
        made = tries = 0
        while made < n and tries < n * 30:
            tries += 1
            text, out = gen()
            text = " ".join(noisy_bn(text).split())
            if text in seen:
                continue
            seen.add(text)
            facts = random_facts() if random.random() < 0.4 else []
            rows.append(row(with_facts(CMD_SYSTEM, facts), as_heard(text, "bn"), dumps(out), "command_bn"))
            made += 1
    for _ in range(15):
        for must, phrasings, out in MEMORY_CMDS:
            for p in phrasings:
                rows.append(row(with_facts(CMD_SYSTEM, random_facts(5, must)), as_heard(noisy(p), "en"), dumps(out), "memory_command"))
    for _ in range(10):
        for fact, questions, answer in MEMORY_CHATS:
            for q in questions:
                rows.append(row(with_facts(CHAT_SYSTEM, random_facts(5, [fact])), noisy(q), answer, "memory_chat"))
    for _ in range(8):
        for qs, answer in IDENTITY_CHATS + BN_IDENTITY_CHATS:
            for q in qs:
                rows.append(row(with_facts(CHAT_SYSTEM, random_facts(4) if random.random() < 0.4 else []),
                                noisy(q), answer, "identity"))
    for it in POOL:
        if not pool_held(it):
            facts = random_facts() if random.random() < 0.4 else []
            rows.append(row(with_facts(CMD_SYSTEM, facts), hearing(it["en"], it["bn"]), it["assistant"], "heard"))
    random.shuffle(rows)
    return rows


def pool_held(it):
    """Stable 10% of the hearing pool kept out of training, to test on real speech-model output."""
    import hashlib
    return int(hashlib.md5(it["text"].encode()).hexdigest(), 16) % 10 == 0


def eval_heard_rows():
    return [row(CMD_SYSTEM, hearing(it["en"], it["bn"]), it["assistant"], "eval_heard") for it in POOL if pool_held(it)]


def eval_memory_rows():
    """Held-out memory checks (never trained on)."""
    chats = [row(with_facts(CHAT_SYSTEM, random_facts(4, [f])), q, key, "eval_memory_chat") for f, q, key in EVAL_MEMORY_CHATS]
    cmds = [row(with_facts(CMD_SYSTEM, random_facts(4, [f])), as_heard(q, "en"), dumps(out), "eval_memory_cmd") for f, q, out in EVAL_MEMORY_CMDS]
    return chats, cmds


def user_rows(interactions, facts, now_ts=None):
    """
    Turns the phone's interaction log into training rows. Only trusted examples:
      - ok / confirmed commands (Alfred did it and you didn't object)
      - corrections ("no, I meant ...") -> the corrected action
    Cancelled actions and Alfred's own chat answers are never trained on.

    Each row gets a weight (how many copies go into training):
      correction 5, confirmed 3, ok 2;  x1.5 if from the last 14 days.
    """
    import time as _time
    now_ts = now_ts or _time.time()
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
        w = {"corrected": 5, "confirmed": 3, "ok": 2}[outcome]
        if now_ts - float(it.get("ts") or 0) < 14 * 86400:
            w *= 1.5
        user = heard if heard.startswith("english: ") else as_heard(heard, "en")
        r = row(with_facts(CMD_SYSTEM, facts), user, dumps(action), "user")
        r["weight"] = max(1, round(w))
        out.append(r)
    return out


COMMAND_START = re.compile(r"^(hey |ok )?(alfred )?(play|pause|call|ring|phone|message|text|whatsapp|remind|set|wake|timer|"
                           r"alarm|open|launch|turn|switch|remember|forget|stop|next|skip|volume|mute)\b", re.I)


# Phone-command phrases anywhere in a message: skip it (it might really be a command)
COMMAND_ANYWHERE = re.compile(r"\b(remind me|set (an |a )?(alarm|timer|reminder)|wake me|call (my|maa|baba|him|her)|"
                              r"play (some|the|a|my)|open (the )?app|whatsapp|send (a )?message|turn (on|off)|volume)\b", re.I)


def style_rows(messages, facts, k=1500, seed=0):
    """
    Your own past messages (from your Claude export), labelled as conversation.
    Teaches Alfred how YOU phrase things (Hinglish, shorthand, typos) so it doesn't mistake
    your normal talk for a phone command. Messages that look like phone commands are skipped.
    """
    rnd = random.Random(seed)
    pool = [m["text"] for m in messages
            if m.get("text") and not COMMAND_START.search(m["text"].strip()) and not COMMAND_ANYWHERE.search(m["text"])]
    rnd.shuffle(pool)
    out = []
    for t in pool[:k]:
        sys_prompt = with_facts(CMD_SYSTEM, facts if rnd.random() < 0.5 else [])
        out.append(row(sys_prompt, as_heard(t, "en", rnd), dumps({"action": "chat", "query": t}), "style"))
    return out


if __name__ == "__main__":
    import collections
    rows = make_rows()
    print(len(rows), collections.Counter(r["kind"] for r in rows))
    print(collections.Counter(json.loads(r["assistant"])["action"] for r in rows if r["kind"] not in ("memory_chat", "identity")))
    labels = {}
    for r in rows:
        if r["kind"] in ("command", "command_bn"):
            u = r["user"]
            clean = u.split("\nbangla: ")[-1] if r["kind"] == "command_bn" else u.split("\nbangla: ")[0].removeprefix("english: ")
            labels.setdefault(clean, set()).add(r["assistant"])
    conflicts = {u: v for u, v in labels.items() if len(v) > 1}
    print("label conflicts:", len(conflicts), list(conflicts.items())[:5])
    for r in [r for r in rows if r["kind"] == "command_bn"][:25]:
        print("BN:", r["user"], "=>", r["assistant"])
    for r in rows[:8]:
        print(repr(r["user"]), "=>", r["assistant"])
    demo = [{"heard": "wake me at 7", "action": {"action": "set_alarm", "hour": 7, "minute": 0, "day": "next"}, "outcome": "ok"},
            {"heard": "call rahul", "action": {"action": "call", "contact": "Rahul"}, "outcome": "corrected",
             "corrected_to": {"action": "call", "contact": "Rahul Sen"}},
            {"heard": "message maa hi", "action": {"action": "message"}, "outcome": "cancelled"},
            {"heard": "call rahul", "action": {"action": "call", "contact": "Rahul"}, "outcome": "ok"}]
    for r in user_rows(demo, ["Rahul means Rahul Sen"]):
        print("USER ROW:", r["user"], "=>", r["assistant"])
