#!/usr/bin/env python3
"""Build README-performance download table from latest combined-*-smoke/large logs."""
from __future__ import annotations

import gzip
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOG_DIR = ROOT / "altastata-examples" / "build" / "performance-logs"
OUT_MD = ROOT / "altastata-examples" / "docs" / "README-performance-LIVE.md"

SIZES = ["1MB", "10MB", "100MB", "1GB", "5GB"]
TYPES = [("Text", "text-{}."), ("Binary", "binary-{}.")]  # suffix filled later


def strip_ansi(s: str) -> str:
    s = re.sub(r"\x1b\[[0-9;]*m", "", s)
    s = re.sub(r"\r.\x1b\[K", "", s)
    return s


def fmt_ms(ms: float) -> str:
    """Always milliseconds (no s/min), as requested for fair side-by-side tables."""
    return f"{ms:.0f} ms"


def latest_logs(cloud: str) -> list[Path]:
    """Prefer newest smoke + newest large/full for each cloud."""
    patterns = [
        f"combined-{cloud}-smoke-*.log",
        f"combined-{cloud}-large-*.log",
        f"combined-{cloud}-full-*.log",
        f"aws-*-*.log",  # ad-hoc
    ]
    found: list[Path] = []
    if not LOG_DIR.exists():
        return found
    for pat in patterns:
        matches = sorted(LOG_DIR.glob(pat), key=lambda p: p.stat().st_mtime, reverse=True)
        if matches and "smoke" in pat:
            found.append(matches[0])
        elif matches and ("large" in pat or "full" in pat):
            found.append(matches[0])
    # also /tmp chain mirrors
    return found


def parse_downloads(text: str, native_label: str, ratio_label: str) -> dict[str, tuple[float, float, float]]:
    """
    Return map filename -> (native_ms, alta_ms, throughput_ratio vs native).
    Only Small/Medium/Large/Very Large (skip Warm-up).
    """
    out: dict[str, tuple[float, float, float]] = {}
    headers = list(
        re.finditer(
            r"=== (Small Files|Medium Files|Large Files|Very Large Files) Test: (\S+) ===\n",
            text,
        )
    )
    for i, h in enumerate(headers):
        fname = h.group(2)
        end = headers[i + 1].start() if i + 1 < len(headers) else len(text)
        body = text[h.end() : end]
        dm = re.search(
            rf"DOWNLOAD COMPARISON for [^\n]+\n(.*?)COMPARISON \(trimmed\):(.*?)\n  =",
            body,
            re.S,
        )
        if not dm:
            continue
        sec = dm.group(1) + dm.group(2)
        gn = re.search(
            rf"{re.escape(native_label)}:.*?TRIMMED Duration: ([\d.]+) ms", sec, re.S
        )
        an = re.search(r"ALTASTATA:.*?TRIMMED Duration: ([\d.]+) ms", sec, re.S)
        thr = re.search(
            rf"Throughput Ratio \(AltaStata/{re.escape(ratio_label)}\): ([\d.]+)x", sec
        )
        if not (gn and an and thr):
            continue
        out[fname] = (float(gn.group(1)), float(an.group(1)), float(thr.group(1)))
    return out


CLOUD_CFG = {
    "gcp": ("DIRECT GCS", "GCS"),
    "azure": ("DIRECT AZURE", "Azure"),
    "aws": ("DIRECT AWS", "AWS"),
}


def collect() -> dict[str, dict[str, tuple[float, float, float]]]:
    data: dict[str, dict[str, tuple[float, float, float]]] = {
        "gcp": {},
        "azure": {},
        "aws": {},
    }
    for cloud, (native, ratio) in CLOUD_CFG.items():
        logs = sorted(
            LOG_DIR.glob(f"combined-{cloud}-*.log"),
            key=lambda p: p.stat().st_mtime,
        )
        # also aws ad-hoc
        if cloud == "aws":
            logs += sorted(LOG_DIR.glob("aws-*.log"), key=lambda p: p.stat().st_mtime)
        merged: dict[str, tuple[float, float, float]] = {}
        for log in logs:
            try:
                text = strip_ansi(log.read_text(errors="replace"))
            except OSError:
                continue
            parsed = parse_downloads(text, native, ratio)
            merged.update(parsed)  # newer logs overwrite
        data[cloud] = merged
    return data


def vs_cell(v: float | None) -> str:
    if v is None:
        return "…"
    if v >= 1:
        return f"**{v:.2f}x**"
    return f"{v:.2f}x"


def pair_cell(text_v: float | None, bin_v: float | None, *, is_ratio: bool = False) -> str:
    """Format Text/Binary as `a / b` (or …)."""
    def one(v: float | None) -> str:
        if v is None:
            return "…"
        return vs_cell(v) if is_ratio else fmt_ms(v)

    if text_v is None and bin_v is None:
        return "…"
    return f"{one(text_v)} / {one(bin_v)}"


def build_table(data: dict) -> str:
    lines = [
        "# Performance (LIVE — download, TRIMMED)",
        "",
        "Each time/ratio cell is **Text / Binary** (compressible `.txt` vs incompressible `.bin`).",
        "Durations in **ms**. **vs** = AltaStata ÷ native **throughput** (higher is better).",
        "",
        "- **Native …** = direct cloud SDK download (no AltaStata).",
        "- **AltaStata (cloud)** = same file downloaded through AltaStata on that cloud.",
        "",
    ]

    # Prefer compact per-cloud sections (readable); empty clouds get a stub line.
    sections = [
        ("GCP", "gcp", "Native GCS ↓", "AltaStata (GCP) ↓", "vs"),
        ("Azure", "azure", "Native Azure ↓", "AltaStata (Azure) ↓", "vs"),
        ("AWS", "aws", "Native S3 ↓", "AltaStata (AWS) ↓", "vs"),
    ]
    for title, key, native_h, as_h, vs_h in sections:
        cloud = data.get(key) or {}
        lines += [
            f"## {title}",
            "",
            f"| Size | {native_h}<br/>Text / Binary | {as_h}<br/>Text / Binary | {vs_h}<br/>Text / Binary |",
            "|------|" + "|".join(["------"] * 3) + "|",
        ]
        for size in SIZES:
            tname = f"text-{size}.txt"
            bname = f"binary-{size}.bin"
            t = cloud.get(tname)
            b = cloud.get(bname)
            if not t and not b:
                lines.append(f"| {size} | … | … | … |")
                continue
            lines.append(
                "| {label} | {n} | {a} | {v} |".format(
                    label=size,
                    n=pair_cell(t[0] if t else None, b[0] if b else None),
                    a=pair_cell(t[1] if t else None, b[1] if b else None),
                    v=pair_cell(t[2] if t else None, b[2] if b else None, is_ratio=True),
                )
            )
        lines.append("")

    running = []
    import subprocess

    for simple in (
        "PerformanceTestCombinedGCP",
        "PerformanceTestCombinedAzure",
        "PerformanceTestCombinedAWS",
        "TestFileGenerator",
    ):
        r = subprocess.run(
            ["pgrep", "-f", f"com\\.altastata\\.performance\\..*{simple}"],
            capture_output=True,
            text=True,
        )
        if r.returncode == 0 and r.stdout.strip():
            short = simple.replace("PerformanceTestCombined", "").replace("TestFile", "File")
            running.append(short)
    lines += [
        f"_Running: {', '.join(running) if running else 'none'}_",
        "",
    ]
    for cloud in ("gcp", "azure", "aws"):
        lines.append(f"- {cloud}: {len(data[cloud])} download rows")
    return "\n".join(lines) + "\n"


def promote_to_readme(md: str) -> None:
    """Replace the main table section in README-performance.md with LIVE content.

    Final README uses the same per-cloud compact layout as the screenshot
    (Size | native ↓ | AS ↓ | vs, Text/Binary, all ms) without the LIVE footer.
    """
    readme = ROOT / "altastata-examples" / "docs" / "README-performance.md"
    if not readme.exists():
        return
    # Drop LIVE header/footer; keep ## GCP / Azure / AWS tables only
    body_lines = []
    skip_meta = False
    for line in md.splitlines():
        if line.startswith("# Performance"):
            continue
        if line.startswith("Same layout") or line.startswith("Text/Binary"):
            continue
        if line.startswith("Each time/ratio") or line.startswith("Durations in"):
            continue
        if line.startswith("- **Native") or line.startswith("- **AltaStata"):
            continue
        if line.startswith("_Running:") or line.startswith("- gcp:") or line.startswith("- azure:") or line.startswith("- aws:"):
            skip_meta = True
            continue
        if skip_meta:
            continue
        body_lines.append(line)
    body = "\n".join(body_lines).strip() + "\n"
    text = readme.read_text()
    marker = "<!-- LIVE_TABLE_START -->"
    end = "<!-- LIVE_TABLE_END -->"
    if marker in text and end in text:
        pre = text.split(marker)[0]
        post = text.split(end)[1]
        readme.write_text(pre + marker + "\n\n" + body + "\n" + end + post)
    else:
        readme.write_text(text.rstrip() + "\n\n" + marker + "\n\n" + body + "\n" + end + "\n")


def main() -> int:
    data = collect()
    md = build_table(data)
    OUT_MD.parent.mkdir(parents=True, exist_ok=True)
    OUT_MD.write_text(md)
    if "--promote" in sys.argv:
        promote_to_readme(md)
        print("Promoted into README-performance.md", file=sys.stderr)
    print(md)
    print(f"Wrote {OUT_MD}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
