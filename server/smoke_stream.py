"""/judge 스트리밍(NDJSON) 오프라인 검사 — Claude 없이 가짜 에이전트로 줄 순서 확인

    python smoke_stream.py

첫 줄 {"early":true,"risk":"LOW","site":…} → 둘째 줄 JudgeOut. stream=false면 예전과 같은 JSON 하나
"""
import json
import os
import tempfile
import time

os.environ.setdefault("ANTHROPIC_API_KEY", "test")
os.environ["DB_PATH"] = os.path.join(tempfile.mkdtemp(), "t.db")

import threading  # noqa: E402

import requests  # noqa: E402
import uvicorn  # noqa: E402

import main  # noqa: E402


class FakeRun:
    def __init__(self, url, risk):
        self.verdict = {"risk": risk, "type": "none" if risk == "LOW" else "personal_info",
                        "reason": "r", "advice": "a", "evidence": ""}
        self.trace = []
        self.final_url = url
        self.page_text = ""
        self.tool_calls = 0
        self.llm_calls = 1


def fake_agent(client, model, criteria, url, click, deps, started=None, oneshot=False, on_early=None):
    risk = "LOW" if "safe" in url else "MEDIUM"
    if on_early:
        on_early(risk, url)       # 실제로는 risk 필드가 스트리밍되는 순간
    time.sleep(0.2)               # reason·advice 생성 시간 흉내
    return FakeRun(url, risk)


def main_():
    main.judge_agent.run_agent = fake_agent
    main.USE_LLM_STREAM = True     # 기본은 꺼져 있음(JUDGE_LLM_STREAM) — 여기서는 early 줄 순서를 보는 것이 목적
    main.refresh_blocklist = lambda: None
    main.blocked_by = lambda url: None
    # 진짜 uvicorn — TestClient는 본문을 모아서 주므로 "먼저 도착"을 잴 수 없음
    srv = uvicorn.Server(uvicorn.Config(main.app, host="127.0.0.1", port=0, log_level="warning"))
    threading.Thread(target=srv.run, daemon=True).start()
    while not srv.started:
        time.sleep(0.05)
    port = srv.servers[0].sockets[0].getsockname()[1]
    base = f"http://127.0.0.1:{port}"

    class _C:
        def stream(self, method, path, json):
            return requests.request(method, base + path, json=json, stream=True, timeout=10)

        def post(self, path, json):
            return requests.post(base + path, json=json, timeout=10)
    c = _C()

    # 스트리밍 — 저위험: early 줄 먼저, 그다음 결론
    seen = []
    with c.stream("POST", "/judge", json={"url": "https://safe-shop.example/p?x=1", "stream": True, "fresh": True}) as r:
        assert r.headers["content-type"].startswith("application/x-ndjson"), r.headers
        t0 = time.monotonic()
        for line in r.iter_lines(decode_unicode=True):
            if line and line.strip():
                seen.append((round(time.monotonic() - t0, 2), json.loads(line)))
    assert len(seen) == 2, seen
    assert seen[0][1] == {"early": True, "risk": "LOW", "site": "safe-shop.example", "at": "safe-shop.example"}, seen[0]
    assert seen[1][1]["risk"] == "LOW" and seen[1][1]["site"] == "safe-shop.example", seen[1]
    assert seen[1][0] - seen[0][0] >= 0.15, seen     # early가 결론보다 먼저 도착
    print(f"① 스트리밍 저위험: early {seen[0][0]}s → 결론 {seen[1][0]}s ✓")

    # 스트리밍 — 중위험: early 줄 없음(reason까지 있어야 화면에 씀), 결론만
    seen = []
    with c.stream("POST", "/judge", json={"url": "https://form.example/", "stream": True, "fresh": True}) as r:
        for line in r.iter_lines(decode_unicode=True):
            if line and line.strip():
                seen.append(json.loads(line))
    assert len(seen) == 1 and seen[0]["risk"] == "MEDIUM", seen
    print("② 스트리밍 중위험: early 없이 결론 한 줄 ✓")

    # 예전 방식 — JSON 하나 (eval.py·앱 구버전)
    r = c.post("/judge", json={"url": "https://safe-shop.example/p?x=2", "fresh": True})
    assert r.headers["content-type"].startswith("application/json") and r.json()["risk"] == "LOW", r.text
    print("③ stream=false: 예전과 같은 JSON 하나 ✓")

    # 캐시 적중도 스트리밍이면 한 줄로 (파라미터 없는 직링크 — 광고주 사이트 캐시 대상)
    seen = []
    with c.stream("POST", "/judge", json={"url": "https://safe-shop.example/", "stream": True}) as r:
        for line in r.iter_lines(decode_unicode=True):
            if line and line.strip():
                seen.append(json.loads(line))
    assert len(seen) == 1 and seen[0]["risk"] == "LOW", seen
    print("④ 캐시 적중 + 스트리밍: 결론 한 줄 ✓")
    print("\n4개 검사 통과")


if __name__ == "__main__":
    main_()
