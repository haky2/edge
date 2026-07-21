#!/usr/bin/env python3
"""X4 교차 재계산 — StanceStats 초과수익 채점·기저율을 백엔드와 독립적으로 재현.

입력: .data/stance_log.jsonl + .cache/daily_history/*.json (백엔드와 동일 원본)
코스피: 야후 ^KS11 (백엔드는 KIS 0001 — 독립 소스라 종가 미세 차이 가능, 판정 일치 여부로 대조)
규약: 기준봉 = 생성일 이전(당일 포함) 마지막 거래일, exit = 기준봉+20거래일, excess = raw − kospi
채점: 긍정 excess>0 / 부정 excess<0 / 중립 |excess|<3
중복: (code,date,mode) 마지막만
"""
import json, urllib.request, sys
from collections import Counter
from datetime import datetime, timezone, timedelta

BASE = "/Users/haky/Workspace/edge/backend"
HORIZON = 20
BAND = 3.0

# ── stance_log ──────────────────────────────────────────────────────────
entries = {}
with open(f"{BASE}/.data/stance_log.jsonl") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        entries[f"{e['code']}|{e['date']}|{e['mode']}"] = e  # 마지막 승리
entries = list(entries.values())
print(f"stance_log: {len(entries)}건(dedup 후)")

# ── 종목 일봉 (오름차순) ──────────────────────────────────────────────────
def load_bars(code):
    try:
        with open(f"{BASE}/.cache/daily_history/{code}.json") as f:
            d = json.load(f)
        return sorted(d["bars"], key=lambda b: b["date"])
    except FileNotFoundError:
        return []

# ── 코스피 (^KS11, 야후) ─────────────────────────────────────────────────
def load_kospi():
    url = ("https://query1.finance.yahoo.com/v8/finance/chart/%5EKS11"
           "?range=1y&interval=1d")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=20) as r:
        j = json.load(r)
    res = j["chart"]["result"][0]
    ts = res["timestamp"]
    closes = res["indicators"]["quote"][0]["close"]
    kst = timezone(timedelta(hours=9))
    out = []
    for t, c in zip(ts, closes):
        if c is None:
            continue
        out.append((datetime.fromtimestamp(t, kst).strftime("%Y%m%d"), c))
    return sorted(out)

kospi = load_kospi()
print(f"kospi(^KS11): {len(kospi)}봉 {kospi[0][0]}~{kospi[-1][0]}")

def forward_pair(ymd, bars_asc, kospi_asc):
    """(raw%, excess%) 또는 None — CatalystValidationService.forwardPair 재현."""
    s_idx = max((i for i, b in enumerate(bars_asc) if b["date"] <= ymd), default=-1)
    if s_idx < 0 or s_idx + HORIZON >= len(bars_asc):
        return None
    s_base = bars_asc[s_idx]["close"]
    if s_base <= 0:
        return None
    raw = (bars_asc[s_idx + HORIZON]["close"] / s_base - 1) * 100
    k_idx = max((i for i, (d, _) in enumerate(kospi_asc) if d <= ymd), default=-1)
    if k_idx < 0 or k_idx + HORIZON >= len(kospi_asc):
        return None
    k_base = kospi_asc[k_idx][1]
    k = (kospi_asc[k_idx + HORIZON][1] / k_base - 1) * 100
    return raw, raw - k

def win(stance, excess):
    if stance == "긍정":
        return excess > 0
    if stance == "부정":
        return excess < 0
    return abs(excess) < BAND

scored, pending, unknown = [], 0, 0
for e in entries:
    if e["stance"] not in ("긍정", "중립", "부정"):
        unknown += 1
        continue
    pair = forward_pair(e["date"].replace("-", ""), load_bars(e["code"]), kospi)
    if pair is None:
        pending += 1
        continue
    scored.append((e, pair[1]))

print(f"\nscored={len(scored)} pending={pending} unknown={unknown}")

if scored:
    all_ex = [x for _, x in scored]
    base = {s: 100 * sum(win(s, x) for x in all_ex) / len(all_ex) for s in ("긍정", "중립", "부정")}
    print(f"스탠스별 기저율: " + ", ".join(f"{s} {v:.1f}%" for s, v in base.items()))

    def bucket(label, items):
        if not items:
            return
        c = sum(win(e["stance"], x) for e, x in items)
        b = sum(base[e["stance"]] for e, _ in items) / len(items)
        avg = sum(x for _, x in items) / len(items)
        print(f"  {label}: n={len(items)} correct={c} acc={100*c/len(items):.1f}% "
              f"avgExcess={avg:+.2f}% base={b:.1f}%")

    bucket("전체", scored)
    for s in ("긍정", "중립", "부정"):
        items = [(e, x) for e, x in scored if e["stance"] == s]
        if len(items) >= 3:
            bucket(f"[{s}]", items)
        elif items:
            print(f"  [{s}]: n={len(items)} <3 → 침묵")
    for m in ("defensive", "aggressive"):
        items = [(e, x) for e, x in scored if e["mode"] == m]
        if len(items) >= 3:
            bucket(f"[{m}]", items)
        elif items:
            print(f"  [{m}]: n={len(items)} <3 → 침묵")
    regimes = sorted({e.get("regime") for e, _ in scored if e.get("regime")})
    for r in regimes:
        items = [(e, x) for e, x in scored if e.get("regime") == r]
        if len(items) >= 3:
            bucket(f"[{r}]", items)
        elif items:
            print(f"  [{r}]: n={len(items)} <3 → 침묵")
