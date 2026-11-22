"""Build the APK word list from the four Vocab WordBank JSON files."""

import argparse
import json
import re
from pathlib import Path


def entries(source: Path) -> dict[str, str]:
    payload = json.loads(source.read_text(encoding="utf-8-sig"))
    return {
        row[0].strip().lower(): " ".join(" ".join(row[2:4]).split())
        for row in payload["words"]
        if re.fullmatch(r"[a-z]+", row[0].strip().lower())
    }


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
args = parser.parse_args()
school = entries(args.source / "gaokao-3500.json").keys() | entries(args.source / "senior.json").keys()
cet4 = entries(args.source / "cet4.json")
cet6 = entries(args.source / "cet6.json")
targets = (cet4.keys() | cet6.keys()) - school
out = Path("app/src/main/assets/vocabulary/cet.tsv")
out.parent.mkdir(parents=True, exist_ok=True)
with out.open("w", encoding="utf-8", newline="\n") as stream:
    for word in sorted(school):
        stream.write(f"school\t{word}\n")
    for word in sorted(targets):
        level = "CET-4 / CET-6" if word in cet4 and word in cet6 else "CET-4" if word in cet4 else "CET-6"
        meaning = cet6.get(word) or cet4.get(word, "")
        stream.write(f"{level}\t{word}\t{meaning}\n")
print(f"{len(school)} school exclusions; {len(targets)} CET headwords after exclusion")
