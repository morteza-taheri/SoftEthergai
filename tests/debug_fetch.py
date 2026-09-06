import re
import urllib.request

req = urllib.request.Request(
    "https://www.vpngate.net/en/",
    headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"},
)
html = urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "replace")

print("page len:", len(html))
for m in re.finditer(r"vg_hosts_table_id", html):
    ctx = html[max(0, m.start() - 120): m.start() + 80]
    print("---- occurrence at", m.start(), repr(ctx[:200]))

tables = re.findall(r"<table[^>]*>", html)
print("table open tags:", len(tables))
for t in tables[:10]:
    print("   ", t[:160])

print("tr count:", len(re.findall(r"<tr", html)))
print("opengw count:", len(re.findall(r"opengw\.net", html)))
