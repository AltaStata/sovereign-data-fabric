#!/usr/bin/env python3
"""Download vs-native chart from README-performance.md numbers. Do not invent data."""

from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

OUT = Path(__file__).resolve().parent.parent / "docs" / "images" / "performance-download-vs-native.png"

# Size in MB (1GB=1024, 5GB=5120) so log ticks stay honest.
SIZES_MB = [1, 10, 100, 1024, 5120]
SIZE_LABELS = ["1MB", "10MB", "100MB", "1GB", "5GB"]

# vs = AltaStata throughput ÷ native (higher is better). From README-performance.md.
DATA = {
    "GCP": {"Text": [0.38, 0.98, 2.32, 3.15, 3.90], "Binary": [0.35, 0.68, 0.90, 1.17, 1.33]},
    "Azure": {"Text": [0.67, 1.41, 2.54, 4.11, 4.74], "Binary": [0.58, 0.81, 0.94, 1.62, 1.63]},
    "AWS": {"Text": [0.50, 0.97, 2.13, 4.11, 5.19], "Binary": [0.56, 0.57, 0.90, 2.10, 2.05]},
}

TEXT = "#0B6E4F"
BINARY = "#1B4F72"


def main() -> None:
    fig, axes = plt.subplots(1, 3, figsize=(11.2, 3.9), sharey=True)
    fig.patch.set_facecolor("white")

    for ax, cloud in zip(axes, ("GCP", "Azure", "AWS")):
        ax.axhline(
            1.0,
            color="#888888",
            linewidth=1.15,
            linestyle="--",
            zorder=0,
            label="1.0× = the cloud (native SDK)",
        )
        ax.plot(
            SIZES_MB,
            DATA[cloud]["Text"],
            color=TEXT,
            marker="o",
            markersize=6,
            linewidth=2.0,
            label="Text (compressible)",
        )
        ax.plot(
            SIZES_MB,
            DATA[cloud]["Binary"],
            color=BINARY,
            marker="s",
            markersize=5.5,
            linewidth=2.0,
            label="Binary (incompressible)",
        )
        ax.set_xscale("log")
        ax.set_xticks(SIZES_MB)
        ax.set_xticklabels(SIZE_LABELS, fontsize=8)
        ax.set_title(cloud, fontsize=11, pad=8)
        ax.set_xlabel("Object size", fontsize=9)
        ax.grid(True, axis="y", linestyle=":", alpha=0.45)
        ax.spines["top"].set_visible(False)
        ax.spines["right"].set_visible(False)
        ax.set_xlim(0.7, 7000)
        ax.set_ylim(0, 5.8)

    axes[0].set_ylabel("AltaStata ÷ the cloud  (higher is better)", fontsize=9)
    axes[0].yaxis.set_major_formatter(mticker.FormatStrFormatter("%.1f×"))
    axes[1].legend(loc="upper left", frameon=False, fontsize=8)

    fig.suptitle(
        "Download: AltaStata vs cloud SDK  ·  same object  ·  compression on for .txt",
        fontsize=11,
        y=1.02,
    )
    fig.tight_layout()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT, dpi=160, bbox_inches="tight", facecolor="white")
    print(OUT)


if __name__ == "__main__":
    main()
