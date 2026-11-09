"""Render the README hero figure: judge latency and accuracy per deployment round.

Source: docs/history.md §03 (judge.md §7), measured 2026-08-24 with --fresh.
    python scripts/make_figures.py   # -> docs/images/deploy-latency{,-dark}.png
"""
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib import font_manager

OUT = Path(__file__).resolve().parent.parent / "docs" / "images"

# round, x label, median, p90, max, correct, total  (None = not measured)
ROUNDS = [
    ("0", "0\nbaseline", 7.3, 11.1, 27.6, 33, 42),
    ("1", "1\noutage", None, None, None, None, 42),
    ("2", "2", 7.9, 11.4, 17.1, 27, 42),
    ("3", "3", 8.0, 12.3, 42.9, 30, 42),
    ("4", "4\ninstrumented", None, None, None, None, 42),
    ("5a", "5\nagent", 4.4, 7.7, 10.8, 32, 42),
    ("5b", "5\nsingle RT", 3.8, 5.7, 7.2, 33, 42),
    ("6", "6\ncriteria v2", 3.8, 5.3, 6.9, 38, 45),
    ("7", "7\ncurrent", 3.6, 5.4, 7.3, 37, 45),
]
DEADLINE = 12.0
YMAX = 30.0
V3_FROM = 6  # index of "5 single round trip"

THEMES = {
    "light": dict(
        surface="#fcfcfb", ink="#0b0b0b", ink2="#52514e", muted="#8a8984",
        grid="#e6e5e0", band="#eef4fc",
        series=["#2a78d6", "#eb6834", "#1baf7a"],
    ),
    "dark": dict(
        surface="#0d1117", ink="#f0efec", ink2="#c3c2b7", muted="#8b8a83",
        grid="#2a2a28", band="#132235",
        series=["#3987e5", "#d95926", "#199e70"],
    ),
}


def _font():
    for name in ("Apple SD Gothic Neo", "NanumGothic", "Noto Sans CJK KR", "Malgun Gothic"):
        if any(f.name == name for f in font_manager.fontManager.ttflist):
            return name
    return "DejaVu Sans"


def draw(mode: str) -> Path:
    t = THEMES[mode]
    plt.rcParams.update({
        "font.family": _font(), "font.size": 10.5, "axes.unicode_minus": False,
        "text.color": t["ink"], "axes.labelcolor": t["ink2"],
        "xtick.color": t["ink2"], "ytick.color": t["ink2"],
    })
    xs = list(range(len(ROUNDS)))
    fig, (ax, bx) = plt.subplots(
        2, 1, figsize=(10, 6.4), sharex=True,
        gridspec_kw={"height_ratios": [2.2, 1], "hspace": 0.12},
    )
    fig.patch.set_facecolor(t["surface"])

    for a in (ax, bx):
        a.set_facecolor(t["surface"])
        a.axvspan(V3_FROM - 0.5, len(ROUNDS) - 0.5, color=t["band"], zorder=0, lw=0)
        a.grid(axis="y", color=t["grid"], lw=0.8)
        a.set_axisbelow(True)
        for s in ("top", "right", "left"):
            a.spines[s].set_visible(False)
        a.spines["bottom"].set_color(t["grid"])
        a.tick_params(length=0)

    # --- latency ---------------------------------------------------------------
    series = [("median", 2, "o", "-"), ("p90", 3, "s", "--"), ("max", 4, "D", ":")]
    for (name, col, marker, ls), color in zip(series, t["series"]):
        # break the line at unmeasured rounds
        seg_x, seg_y = [], []
        for x, r in zip(xs, ROUNDS):
            v = r[col]
            if v is None:
                if seg_x:
                    ax.plot(seg_x, seg_y, color=color, lw=2, ls=ls, zorder=3)
                seg_x, seg_y = [], []
                continue
            seg_x.append(x)
            seg_y.append(min(v, YMAX))
        if seg_x:
            ax.plot(seg_x, seg_y, color=color, lw=2, ls=ls, zorder=3)
        pts = [(x, min(r[col], YMAX)) for x, r in zip(xs, ROUNDS) if r[col] is not None]
        ax.scatter(*zip(*pts), s=42, marker=marker, color=color,
                   edgecolor=t["surface"], linewidth=1.5, zorder=4)
        ax.plot([], [], color=color, lw=2, ls=ls, marker=marker, ms=6, label=name)
        last = ROUNDS[-1][col]
        ax.annotate(f"{name} {last}s", (xs[-1], last), xytext=(10, 0),
                    textcoords="offset points", va="center", fontsize=9.5,
                    color=t["ink2"])

    ax.axhline(DEADLINE, color=t["muted"], lw=1, ls=(0, (4, 3)), zorder=2)
    ax.text(-0.45, DEADLINE + 0.6, "app deadline 12s", fontsize=9.5, color=t["ink2"])

    # round-3 max is off the axis
    ax.annotate("42.9s ↑", (3, YMAX), xytext=(8, -2), textcoords="offset points",
                fontsize=9.5, color=t["ink2"], va="top")
    ax.annotate("27.6s", (0, 27.6), xytext=(8, 0), textcoords="offset points",
                fontsize=9.5, color=t["ink2"], va="center")
    for i, txt in ((1, "not measurable\n(streaming outage)"), (4, "n = 4\n5–14s")):
        ax.text(i, 15, txt, ha="center", va="center", fontsize=9, color=t["muted"])

    ax.text(V3_FROM - 0.4, YMAX - 1, "v3  single round trip + parallel prefetch",
            fontsize=9.5, color=t["ink2"], va="top", fontweight="bold")
    ax.text(1.0, YMAX - 1, "v2  tool agent", fontsize=9.5,
            color=t["ink2"], va="top", fontweight="bold")

    ax.set_ylim(0, YMAX + 1)
    ax.set_ylabel("judge latency (s, cache off)")
    leg = ax.legend(loc="upper center", bbox_to_anchor=(0.5, 1.12), ncol=3,
                    frameon=False, fontsize=9.5, handletextpad=0.4)
    for txt in leg.get_texts():
        txt.set_color(t["ink2"])

    # --- accuracy -------------------------------------------------------------
    acc_x = [x for x, r in zip(xs, ROUNDS) if r[5] is not None]
    acc = [ROUNDS[x][5] / ROUNDS[x][6] * 100 for x in acc_x]
    color = t["series"][0]
    bx.plot(acc_x[:3], acc[:3], color=color, lw=2, zorder=3)
    bx.plot(acc_x[2:], acc[2:], color=color, lw=2, zorder=3)
    bx.scatter(acc_x, acc, s=48, color=color, edgecolor=t["surface"],
               linewidth=1.5, zorder=4)
    for x, v in zip(acc_x, acc):
        r = ROUNDS[x]
        bx.text(x, v + 1.5, f"{r[5]}/{r[6]}", ha="center", va="bottom",
                fontsize=9, color=t["ink2"])
    bx.set_ylim(55, 95)
    bx.set_yticks([60, 70, 80, 90])
    bx.set_yticklabels([f"{v}%" for v in (60, 70, 80, 90)])
    bx.set_ylabel("eval accuracy")
    bx.set_xticks(xs)
    bx.set_xticklabels([r[1] for r in ROUNDS], fontsize=9.5)
    bx.set_xlim(-0.6, len(ROUNDS) - 0.1)
    fig.text(0.07, 0.02, "Deployment round (2026-08-24, judge model fixed: claude-haiku-4-5) · eval: 42 cases in rounds 0–5, 45 from round 6",
             fontsize=9, color=t["muted"])
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / ("deploy-latency.png" if mode == "light" else "deploy-latency-dark.png")
    fig.savefig(path, dpi=200, bbox_inches="tight", facecolor=t["surface"])
    plt.close(fig)
    return path


if __name__ == "__main__":
    for m in THEMES:
        print(draw(m))
