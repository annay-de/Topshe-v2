import json
import random

random.seed(42)

CMD_SYSTEM = (
    "You are Topshe, a phone assistant. Turn the user's request into ONE JSON object. "
    "Actions: set_alarm, set_timer, set_reminder, open_app, call, message, tasker, stop, chat. "
    "Use chat for questions or small talk. Reply with JSON only."
)

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


GENERATORS = [(ex_alarm, 700), (ex_timer, 400), (ex_reminder, 700), (ex_open, 500), (ex_call, 400),
              (ex_message, 700), (ex_tasker, 400), (ex_stop, 100), (ex_chat, 700)]


def noisy(text):
    """Make text look like speech-to-text output."""
    if random.random() < 0.15:
        text = random.choice(["topshe ", "hey topshe ", "please ", "umm ", "ok "]) + text
    if random.random() < 0.1:
        text += random.choice([" please", " thanks", " now"])
    text = text.replace("?", "") if random.random() < 0.5 else text
    return text.lower() if random.random() < 0.6 else text


def make_rows():
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
            rows.append({"messages": [
                {"role": "system", "content": CMD_SYSTEM},
                {"role": "user", "content": text},
                {"role": "assistant", "content": json.dumps(out, ensure_ascii=False)},
            ]})
            made += 1
    random.shuffle(rows)
    return rows


if __name__ == "__main__":
    import collections
    rows = make_rows()
    print(len(rows), collections.Counter(json.loads(r["messages"][2]["content"])["action"] for r in rows))
    for r in rows[:10]:
        print(r["messages"][1]["content"], "=>", r["messages"][2]["content"])
