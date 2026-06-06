#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import site


def main() -> None:
    candidates = [
        Path(p) / "vibe/core/llm/backend/generic.py"
        for p in site.getsitepackages()
    ]
    target = next((p for p in candidates if p.exists()), None)
    if target is None:
        raise SystemExit("mistral-vibe generic.py not found")

    text = target.read_text()
    old = '''                DELIM_CHAR = ":"
                if f"{DELIM_CHAR} " not in line:
                    raise ValueError(
                        f"Stream chunk improperly formatted. "
                        f"Expected `key{DELIM_CHAR} value`, received `{line}`"
                    )
                delim_index = line.find(DELIM_CHAR)
                key = line[0:delim_index]
                value = line[delim_index + 2 :]
'''
    new = '''                DELIM_CHAR = ":"
                if line.lstrip().startswith(DELIM_CHAR):
                    continue
                if DELIM_CHAR not in line:
                    raise ValueError(
                        f"Stream chunk improperly formatted. "
                        f"Expected `key{DELIM_CHAR} value`, received `{line}`"
                    )
                delim_index = line.find(DELIM_CHAR)
                key = line[0:delim_index]
                value = line[delim_index + 1 :]
                if value.startswith(" "):
                    value = value[1:]
'''
    if old not in text:
        if "if line.lstrip().startswith(DELIM_CHAR):" in text:
            print(f"mistral-vibe SSE parser already patched: {target}")
            return
        raise SystemExit(f"mistral-vibe SSE parser shape changed: {target}")

    target.write_text(text.replace(old, new))
    print(f"patched mistral-vibe SSE parser: {target}")


if __name__ == "__main__":
    main()
