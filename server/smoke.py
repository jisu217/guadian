"""로컬 검증 스크립트 — 배포 전 서버 코드 각 부분의 실제 동작 확인

  python smoke.py          — 키 없이 되는 부분만 (페이지 가져오기, 캐시 열쇠)
  python smoke.py llm      — LLM 판정까지 (ANTHROPIC_API_KEY 필요)
"""

import sys

import main

# 실측에서 잡은 진짜 광고 클릭 링크 — 리다이렉트 추적 후 최종 목적지 도출 기대
AD_LINK = (
    "https://cyad1.nate.com/click.kti/mnate/wider@nlist_Top3"
    "?ads_no=243187&cmp_no=31179&img_no=415759"
)

print("== _site_of ==")
for u in ("https://m.news.nate.com/x", "https://ohouse.airbridge.io/y",
          "https://news.wec.co.kr/z"):
    print(f"  {u} -> {main._site_of(u)}")

print("== _fetch (광고 클릭 링크, 리다이렉트 추적) ==")
text, final = main._fetch(AD_LINK)
print(f"  final: {final}")
print(f"  text({len(text or '')}자): {(text or '')[:150]}")

if "llm" in sys.argv:
    print("== 판정 (실제 LLM 호출) ==")
    for url in (AD_LINK, "https://www.ohou.se/"):
        v = main._ask_llm(url, *reversed(main._fetch(url)))
        v = main._verify(v, None)
        print(f"  {url[:60]}")
        print(f"    -> {v['risk']}: {v['reason']} / {v['advice']}")
