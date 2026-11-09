"""클릭 전 판정(/peek 뒤 큐) 오프라인 검사 — 미리 보는 것과 안 보는 것의 구분

    ANTHROPIC_API_KEY=x DB_PATH=/tmp/t.db python test_prejudge.py
"""
import os
import tempfile
import time

os.environ.setdefault("ANTHROPIC_API_KEY", "test")
os.environ["DB_PATH"] = os.path.join(tempfile.mkdtemp(), "t.db")

import main  # noqa: E402


def main_():
    T = main._prejudge_target
    # 미리 봐도 되는 것 — 목적지가 확실한 주소
    assert T("https://www.googleadservices.com/pagead/aclk?sa=L&ai=x&adurl=https%3A%2F%2Fshop.example%2Fp%3Fa%3D1") == "https://shop.example/p?a=1"
    assert T("https://brand-landing.co.kr/event?utm_source=google") == "https://brand-landing.co.kr/event?utm_source=google"
    # 안 보는 것 — 트래커(가짜 클릭)·광고망·장터·소재 이미지
    assert T("https://cyad1.nate.com/click.kti/mnate/news@bt?ads_no=1") is None
    assert T("https://www.googleadservices.com/pagead/aclk?sa=L&ai=x") is None
    assert T("https://api.dable.io/click_redirect?x=1") is None
    assert T("https://blog.naver.com/someone/123") is None
    assert T("https://adimg.nate.com/img/2026/07/lina.jpg") is None
    print("① 대상 고르기: adurl·직링크만, 트래커·광고망·장터·이미지는 제외 ✓")

    # 큐 — 같은 사이트 한 번, 상한, 진행 중 중복 방지. 에이전트는 가짜로 대체
    calls = []
    class FakeRun:
        def __init__(self, url): self.verdict = {"risk": "LOW", "type": "none", "reason": "r", "advice": "a", "evidence": ""}; self.trace = []; self.final_url = url
    main.judge_agent.run_agent = lambda client, model, criteria, url, click, deps, started=None, **kw: (calls.append(url), time.sleep(0.05), FakeRun(url))[2]
    main.refresh_blocklist = lambda: None
    main.blocked_by = lambda url: None
    main.PREJUDGE_DAILY_CAP = 3
    for u in ["https://a.example/1", "https://a.example/2", "https://b.example/", "https://c.example/", "https://d.example/"]:
        main._prejudge_enqueue(u)
    main._prejudge_pool.shutdown(wait=True)
    sites = sorted(main._site_of(c) for c in calls)
    assert sites == ["a.example", "b.example", "c.example"], sites   # a는 한 번, d는 상한 초과
    assert "a.example" in main._cache and main._cache["a.example"]["risk"] == "LOW"
    print(f"② 큐: 사이트당 한 번 · 하루 상한 {main.PREJUDGE_DAILY_CAP} · 결과 캐시 → {sites} ✓")

    # 판정 완료 사이트 재조회 없음
    main._prejudge_pool = None; calls.clear()
    main._prejudge_enqueue("https://a.example/3")
    assert main._prejudge_pool is None and not calls
    print("③ 판정 있는 사이트는 건너뜀 ✓")
    print("\n3개 검사 통과")


if __name__ == "__main__":
    main_()
