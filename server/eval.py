"""판정 정확도 측정 — 라벨 붙은 URL 묶음을 서버에 질의, 적중 개수 집계

    # 배포된 서버
    python eval.py --server https://adalert-judge.azurewebsites.net
    # 로컬 서버 (JUDGE_AGENT=0 으로 띄우면 예전 1회 판정과 비교 가능)
    python eval.py --server http://127.0.0.1:8000
    # 캐시 무시(매번 새 판정) + 스트리밍(저위험 선행 통보까지의 시간도 측정)
    python eval.py --server http://127.0.0.1:8000 --fresh --stream

라벨 파일: eval_set.json — [{"url": ..., "expect": "LOW|MEDIUM|HIGH", "why": ...}]
적중 기준: 기대 등급과 동일. HIGH→MEDIUM은 "약함", LOW→HIGH는 "오탐"으로 별도 집계 —
오탐이 미탐보다 나쁘다는 이 앱의 원칙을 숫자에도 적용

기록(/recent·DB)에 남기지 않으려면 서버를 별도 DB_PATH로 실행
"""

import argparse
import json
import sys
import time

import requests


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", required=True)
    ap.add_argument("--set", default="eval_set.json")
    ap.add_argument("--timeout", type=float, default=25.0)
    ap.add_argument("--fresh", action="store_true", help="서버 캐시(광고키·광고주 사이트) 무시 — 소요 시간 측정용")
    ap.add_argument("--stream", action="store_true", help="NDJSON 스트리밍으로 받아 저위험 선행 통보 시각도 기록")
    args = ap.parse_args()

    items = [it for it in json.load(open(args.set, encoding="utf-8")) if "url" in it]
    hit = weak = false_alarm = 0
    rows = []
    tooks: list[float] = []
    earlies: list[float] = []
    for it in items:
        t0 = time.monotonic()
        early_at = None
        try:
            payload = {"url": it["url"], "fresh": args.fresh, "stream": args.stream}
            if args.stream:
                r = requests.post(f"{args.server}/judge", json=payload, timeout=args.timeout, stream=True)
                last = {}
                for line in r.iter_lines(decode_unicode=True):
                    if not line:
                        continue
                    obj = json.loads(line)
                    if obj.get("early"):
                        early_at = time.monotonic() - t0
                    else:
                        last = obj
                got, reason = last.get("risk", "?"), last.get("reason", "")
            else:
                r = requests.post(f"{args.server}/judge", json=payload, timeout=args.timeout)
                got = r.json().get("risk", "?")
                reason = r.json().get("reason", "")
        except Exception as e:
            got, reason = f"ERR:{type(e).__name__}", ""
        took = time.monotonic() - t0
        tooks.append(took)
        exp = it["expect"]
        mark = "✓" if got == exp else "✗"
        if got == exp:
            hit += 1
        elif exp == "HIGH" and got == "MEDIUM":
            weak += 1
        elif exp == "LOW" and got in ("MEDIUM", "HIGH"):
            false_alarm += 1
        early_s = f" (저위험 선행 {early_at:.1f}s)" if early_at is not None else ""
        if early_at is not None:
            earlies.append(early_at)
        rows.append((mark, exp, got, f"{took:.1f}s", it["url"][:60], reason[:40]))
        print(f"{mark} 기대 {exp:6} 결과 {got:8} {took:5.1f}s{early_s}  {it['url'][:60]}  {reason[:40]}")

    n = len(items)
    print("\n" + "─" * 60)
    print(f"맞춤 {hit}/{n} ({100 * hit // max(n, 1)}%) · 약함(HIGH→MEDIUM) {weak} · 오탐(LOW→경고) {false_alarm}")
    if tooks:
        ts = sorted(tooks)
        pct = lambda q: ts[min(len(ts) - 1, int(len(ts) * q))]
        print(f"소요 중앙값 {pct(0.5):.1f}s · p90 {pct(0.9):.1f}s · 최대 {ts[-1]:.1f}s · 12초 초과 {sum(t >= 12 for t in ts)}건")
    if earlies:
        es = sorted(earlies)
        print(f"저위험 선행 통보 {len(es)}건 · 중앙값 {es[len(es) // 2]:.1f}s")
    return 0 if false_alarm == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
