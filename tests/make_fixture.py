"""Generate a deterministic HTML fixture from the live VPN Gate table.

Run once: python tests/make_fixture.py
The fixture stores REAL VPN Gate markup (header + first 25 data rows)
so parser unit tests are anchored to the actual DOM (mission §31/§34).
"""
import os
import re
import urllib.request

URL = "https://www.vpngate.net/en/"
OUT = os.path.join(os.path.dirname(__file__), "fixtures", "vpngate_table_sample.html")

req = urllib.request.Request(
    URL,
    headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"},
)

html = urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "replace")

marker = "<table border='1' id='vg_hosts_table_id'"

# The live page has THREE tables sharing this id (layout helper,
# country ranking, and the real server list). Pick the last one,
# which is the actual hosts list inside <span id="Label_Table">.
start = html.rfind(marker)
assert start > 0, "hosts table not found on live page"

end = html.find("</table>", start)
table = html[start:end + len("</table>")]

rows = re.findall(r"<tr[\s\S]*?</tr>", table)
print("rows found:", len(rows))
assert len(rows) > 5, f"unexpectedly few rows: {len(rows)}"

# Header row + first 25 data rows keep the fixture compact.
fixture = (
    table[: table.find(rows[0]) + len(rows[0])]
    + "\n".join(rows[1:26])
    + "\n</table>"
)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(fixture)

print("fixture bytes:", len(fixture))
print("sample hosts:", re.findall(r"[a-z0-9-]+\.opengw\.net", fixture)[:10])
