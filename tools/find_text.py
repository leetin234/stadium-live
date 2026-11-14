"""uiautomator 덤프(stdin)에서 글자를 찾아 누를 좌표를 출력한다.
사용: find_text.py 글자 [n번째] [dx dy]  — dx/dy를 주면 글자 오른쪽 위 모서리 기준 오프셋."""
import re
import sys

text, n = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 1
hits = [b for s, b in re.findall(r'text="([^"]*)"[^>]*bounds="([^"]*)"', sys.stdin.read()) if s.strip() == text]
x1, y1, x2, y2 = map(int, re.findall(r"\d+", hits[n - 1]))
if len(sys.argv) > 4:
    print(x2 + int(sys.argv[3]), y1 + int(sys.argv[4]))
else:
    print((x1 + x2) // 2, (y1 + y2) // 2)
