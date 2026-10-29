"""Print the centre "x y" of the first node whose text or content-description contains the given words (a uiautomator dump), or nothing."""
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
want = sys.argv[2].lower()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = (re.search(r'\btext="([^"]*)"', n) or [None, ''])[1] + ' ' + (re.search(r'content-desc="([^"]*)"', n) or [None, ''])[1]
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b and want in t.lower():
        x0, y0, x1, y1 = map(int, b.groups())
        print((x0 + x1) // 2, (y0 + y1) // 2)
        break
