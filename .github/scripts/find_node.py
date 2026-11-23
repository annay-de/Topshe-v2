"""Prints the centre of the on-screen element whose text matches argv[2] (uiautomator dump), or all texts with --all."""
import re
import sys

xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
nodes = re.findall(r"<node [^>]*>", xml)
for n in nodes:
    t = re.search(r' text="([^"]*)"', n)
    if not t or not t.group(1):
        continue
    if sys.argv[2] == "--all":
        print(t.group(1))
    elif t.group(1).strip().lower() == sys.argv[2].lower():
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
