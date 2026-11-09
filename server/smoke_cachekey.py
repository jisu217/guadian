"""식별키 매핑 검증 — '함께 저장한 무언가'가 실제 판독 열쇠가 되는지 확인

시나리오:
 1. 트래커 광고 클릭 → 판정 (실제 fetch + LLM)
 2. **같은 광고, 다른 클릭**(img_no만 다름) → 식별키 즉답 기대
 3. peek: 클릭 없이 화면 광고 href만으로 색 판독 여부
    - 같은 트래커 광고         → 식별키로 앎
    - 구글 aclk (adurl 포함)   → adurl 파싱으로 앎
    - 처음 보는 트래커         → 모름(None) 기대
"""

import os

if os.path.exists("judgments.db"):
    os.remove("judgments.db")

import main

TRACKER_1 = ("https://cyad1.nate.com/click.kti/mnate/wider@nlist_Top3"
             "?ads_no=243187&cmp_no=31179&img_no=415759")
TRACKER_2 = ("https://cyad1.nate.com/click.kti/mnate/wider@nlist_Top3"
             "?ads_no=243187&cmp_no=31179&img_no=999999")   # 같은 광고, 다른 소재/클릭
GOOGLE_AD = ("https://www.googleadservices.com/pagead/aclk?sa=L&ai=xyz"
             "&adurl=https%3A%2F%2Fwww.ohou.se%2Fevent")
UNSEEN =    "https://cyad1.nate.com/click.kti/mnate/other@slot?ads_no=777777"

print("== _ad_key ==")
print("  1:", main._ad_key(TRACKER_1))
print("  2:", main._ad_key(TRACKER_2))
print("  같은 키(ads_no·cmp_no 같음, img_no 다름 → 달라야 정상):",
      main._ad_key(TRACKER_1) == main._ad_key(TRACKER_2))

print("== 1. 첫 클릭 판정 (fetch+LLM) ==")
v1 = main.judge(main.JudgeIn(url=TRACKER_1))
print("  ->", v1.risk, "/ site:", v1.site)

print("== 2. 같은 주소 재클릭 → 식별키 즉답 ==")
v2 = main.judge(main.JudgeIn(url=TRACKER_1))
print("  ->", v2.risk, "/ site:", v2.site)

print("== 3. peek (클릭 없이 조회만) ==")
res = main.peek(main.PeekIn(urls=[TRACKER_1, GOOGLE_AD, UNSEEN]))
for u, r in res.items():
    print(f"  {r!s:8} <- {u[:70]}")
