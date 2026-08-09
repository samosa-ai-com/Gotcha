"""Project Falcon — document ingestion service (sample script)."""

import os
from pathlib import Path

SUPPORTED_EXTS = {"pdf", "docx", "xlsx", "pptx", "html", "txt", "csv", "md", "json", "yaml", "xml", "rtf"}
MAX_BYTES = 20 * 1024 * 1024  # mirror of DocumentParser.MAX_DOC_BYTES
MAX_CHARS = 40_000


def classify(path: Path) -> str:
    ext = path.suffix.lstrip(".").lower()
    if ext in SUPPORTED_EXTS:
        return "supported"
    return "unsupported"


def extract(path: Path) -> str:
    if path.stat().st_size > MAX_BYTES:
        raise ValueError("file too large (max 20 MB)")
    text = path.read_text(encoding="utf-8", errors="replace")
    if len(text) > MAX_CHARS:
        text = text[: MAX_CHARS - 46] + "\n\n[Note: document text was truncated to fit the model's context window]"
    return text


def main() -> None:
    for name in sorted(os.listdir(".")):
        p = Path(name)
        if p.suffix.lower() not in {".py"} and p.is_file():
            print(f"{p.name:20} {classify(p):10} {extract(p)[:40]!r}")


if __name__ == "__main__":
    main()
