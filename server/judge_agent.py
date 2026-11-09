"""판정 에이전트 — Claude가 도구를 골라 쓰며 착지 페이지의 위험도 판정

예전(main._ask_llm): 코드가 정한 순서 하나 — 페이지 1번 읽기 → Claude에 1번 묻기.
한계: 본문이 비면(JS 렌더링·리다이렉트 껍데기) "확인 못 함"으로 종료, "KB국민은행"이
적힌 kb-star.top은 글자만으로는 사칭 판별 불가

여기서는 **운전대는 Claude, 가드레일은 코드.**
 - 도구 종류·개수·순서는 Claude가 결정 (코드에는 루프만, 분기 없음)
 - 코드가 강제하는 것 셋:
     ① 예산 — 도구 호출 MAX_TOOL_CALLS번, 벽시계 WALL_BUDGET_S초. 초과 시 그때까지 본 것으로 판정
     ② 근거 — 고위험의 evidence는 **도구가 돌려준 결과 어딘가에** 실재 필수
     ③ 형식 — 최종 판정은 final_verdict 도구의 스키마로만 수신 (지어낸 등급·유형 불가)

도구 = 지금의 DB와 룰: 차단 목록(SQLite) · 판정 기록(SQLite) · 페이지 읽기 ·
페이지 신호(폼·스크립트·브랜드 언급) · 브랜드→공식 도메인 표 · 도메인 등록일(RDAP)
"""

from __future__ import annotations

import html as _html
import json
import logging
import re
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from concurrent.futures import Future, ThreadPoolExecutor, wait
from concurrent.futures import TimeoutError as FutureTimeout

log = logging.getLogger("judge.agent")

# ── 예산 ─────────────────────────────────────────────────────────────────────
#
# 폰의 "확인 중" 화면은 ANALYZE_TIMEOUT_MS(12초)에 종료. 서버는 빠른 길(차단 목록·캐시)을
# 거친 뒤 여기로 오므로, 에이전트 전체가 그 안에 끝나야 사용자에게 판정 표시
# 도구 하나 최대 3.5초(페이지), Claude 한 번 1~3초 — 왕복이 늘면 금세 소진
# 미리 모으기(prefetch) 뒤 Claude가 **추가로** 쓸 수 있는 도구 수. 흔한 것(페이지·신호·
# 차단 목록·기록·공식 도메인·등록일)은 이미 모아 첫 질문에 포함 — 대부분 추가 도구 없이 종료
MAX_TOOL_CALLS = 3
WALL_BUDGET_S = 7.5
# 서버가 답을 내야 하는 절대 상한. 앱 HttpJudge 읽기 시한 10초·쉴드 12초 — 그 안에 네트워크까지 포함
HARD_LIMIT_S = 9.0
# Claude 한 번의 시도 시한. 실측(2026-08-24 Azure, /recent llm 항목): 도구 고르기 호출 1.1초, 판정 문장 호출
# 2.5~3.5초(출력 ~300토큰). 3.0초로 잘랐더니 판정 호출이 전부 잘림 → 최소 4초. 시간이 남으면 한 번 재시도
LLM_TRY_MIN_S = 4.0
LLM_TRY_MAX_S = 6.0
LLM_RETRY_MIN_LEFT_S = 3.0
# 페이지 읽기 **전체** 상한(리다이렉트 홉 합산). 예전엔 requests의 소켓 단위 시한이라 홉 4번 ×
# 3.5초 + 느리게 흘러오는 본문이 무제한 — 실측(2026-08-23) chosun.com 27.6초, baddiary 19초
FETCH_TIMEOUT_S = 3.5
SIGNAL_FETCH_TIMEOUT_S = 2.5
# 본문 읽기 상한 바이트. 판정 신호는 상단에 집중 — 그 뒤는 끊어도 판정 동일
FETCH_MAX_BYTES = 300_000
# RDAP — 조회는 백그라운드, 판정은 기다리지 않음. Azure에서 rdap.org가 1.5초 안에 거의 안 옴
# (실측 2026-08-23: /recent의 domain_age 전부 ReadTimeout) — 기다려도 "모름"만 받던 1.5초 제거
# RDAP_TIMEOUT_S = 모델이 도구로 직접 부를 때 결과를 기다리는 상한 (남은 예산 안에서)
# RDAP_BG_TIMEOUT_S = 백그라운드 HTTP 타임아웃 — 기록이 쌓여야 다음 판정에 즉답
# RDAP_FAIL_RETRY_S = 실패 기록 유효 기간. 예전엔 실패도 영구 기록 — 타임아웃 한 번에 영영 "모름"
RDAP_TIMEOUT_S = 1.5
RDAP_BG_TIMEOUT_S = 6.0
RDAP_FAIL_RETRY_S = 24 * 3600
# RDAP 자체가 없는 TLD — 조회 없이 즉시 "모름". .kr(KRNIC)은 항상 404
NO_RDAP_TLDS = {"kr"}
MAX_REDIRECT_HOPS = 3
# Claude에 넘기는 본문 길이. 판정 신호는 상단에 집중, 길수록 응답 지연(실측 호출당 0.5~1초)
MAX_PAGE_CHARS = 2500
MAX_TOKENS = 450
# 도구 하나를 더 돌리고도 마지막 final_verdict 호출(Haiku ~2초)이 들어갈 최소 잔여 시간
TOOL_MIN_LEFT_S = 2.5
# 이보다 최근 등록된 도메인 = "새 도메인". 사칭·피싱 도메인은 며칠 만에 생성·폐기
YOUNG_DOMAIN_DAYS = 30

_MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
)

# 아웃바운드 연결 재사용. Azure App Service(Basic)는 SNAT 포트가 128개 — 요청마다 새 연결을 열면
# 고갈되어 외부 접속이 통째로 타임아웃(실측 2026-08-23: RDAP 전부 ReadTimeout, Claude 스트리밍 호출 무응답)
_http = requests.Session()

_JS_REDIRECT = re.compile(
    r"""location\.(?:replace|assign)\(\s*['"]([^'"]+)['"]"""
    r"""|location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""
    r"""|http-equiv=["']refresh["'][^>]*url=([^"'>\s]+)""",
    re.I,
)

# ── 브랜드 → 공식 도메인 ──────────────────────────────────────────────────────
#
# **이 표가 이 서버의 자산.** 외부 수신이 아니라 직접 큐레이션
# 사칭 탐지 = "브랜드 이름은 적혀 있는데 도메인은 그 브랜드 것이 아님" — 전제는 브랜드별 도메인 지식
# 어르신이 당하는 사칭 대상(은행·카드·공공기관·통신사·택배·큰 쇼핑몰) 우선 수록.
# 별칭 비교는 소문자·공백 제거 기준
BRANDS: list[tuple[tuple[str, ...], tuple[str, ...]]] = [
    # 은행
    (("국민은행", "kb국민은행", "kb은행", "kbstar", "kb bank"), ("kbstar.com", "kbcard.com", "kbfg.com")),
    (("신한은행", "신한카드", "신한"), ("shinhan.com", "shinhancard.com")),
    (("우리은행", "우리카드"), ("wooribank.com", "wooricard.com")),
    (("하나은행", "하나카드", "kebhana"), ("hanabank.com", "kebhana.com", "hanacard.co.kr")),
    (("농협", "nh농협", "농협은행", "nh은행"), ("nonghyup.com", "nhbank.com", "nhcard.com")),
    (("기업은행", "ibk기업은행", "ibk"), ("ibk.co.kr",)),
    (("카카오뱅크",), ("kakaobank.com",)),
    (("케이뱅크", "k뱅크"), ("kbanknow.com",)),
    (("토스", "토스뱅크", "toss"), ("toss.im", "tossbank.com")),
    (("새마을금고",), ("kfcc.co.kr",)),
    (("신협",), ("cu.co.kr",)),
    (("우체국", "우체국예금", "우체국택배"), ("epost.go.kr", "epostbank.go.kr")),
    (("산업은행", "kdb"), ("kdb.co.kr",)),
    (("수협", "수협은행"), ("suhyup-bank.com",)),
    (("sc제일은행", "제일은행"), ("standardchartered.co.kr",)),
    (("씨티은행", "한국씨티"), ("citibank.co.kr",)),
    # 카드
    (("삼성카드",), ("samsungcard.com",)),
    (("현대카드",), ("hyundaicard.com",)),
    (("롯데카드",), ("lottecard.co.kr",)),
    (("비씨카드", "bc카드"), ("bccard.com",)),
    # 공공기관
    (("국세청", "홈택스"), ("nts.go.kr", "hometax.go.kr")),
    (("정부24", "행정안전부"), ("gov.kr", "mois.go.kr")),
    (("건강보험공단", "국민건강보험"), ("nhis.or.kr",)),
    (("국민연금", "국민연금공단"), ("nps.or.kr",)),
    (("경찰청", "경찰서", "사이버수사대"), ("police.go.kr", "ecrm.police.go.kr")),
    (("검찰청", "검찰"), ("spo.go.kr",)),
    (("금융감독원", "금감원"), ("fss.or.kr",)),
    (("질병관리청",), ("kdca.go.kr",)),
    (("도로교통공단",), ("koroad.or.kr", "safedriving.or.kr")),
    (("한국전력", "한전"), ("kepco.co.kr",)),
    (("법원", "대법원"), ("scourt.go.kr",)),
    (("국민비서",), ("ips.go.kr",)),
    (("교통민원24", "이파인"), ("efine.go.kr",)),
    (("고용노동부", "고용보험"), ("moel.go.kr", "ei.go.kr")),
    (("예금보험공사",), ("kdic.or.kr",)),
    # 통신
    (("skt", "sk텔레콤", "t월드", "tworld"), ("sktelecom.com", "tworld.co.kr", "sk.com")),
    (("kt", "케이티", "올레"), ("kt.com", "olleh.com")),
    (("lg유플러스", "lgu+", "유플러스"), ("lguplus.com", "uplus.co.kr")),
    # 택배
    (("cj대한통운", "대한통운"), ("cjlogistics.com",)),
    (("한진택배", "한진"), ("hanjin.com", "hanjin.co.kr")),
    (("롯데택배", "롯데글로벌로지스"), ("lotteglogis.com",)),
    (("로젠택배",), ("ilogen.com",)),
    (("쿠팡", "쿠팡이츠", "로켓배송"), ("coupang.com", "coupangeats.com")),
    # 포털·플랫폼
    (("네이버", "naver", "네이버페이"), ("naver.com", "navercorp.com", "pay.naver.com")),
    (("카카오", "카카오톡", "카카오페이"), ("kakao.com", "kakaopay.com", "kakaocorp.com")),
    (("구글", "google", "gmail"), ("google.com", "google.co.kr", "youtube.com")),
    (("애플", "apple", "아이클라우드", "icloud"), ("apple.com", "icloud.com")),
    (("삼성", "삼성전자", "갤럭시"), ("samsung.com",)),
    (("lg전자",), ("lge.co.kr", "lg.com")),
    (("넷플릭스", "netflix"), ("netflix.com",)),
    (("당근", "당근마켓"), ("daangn.com",)),
    (("배달의민족", "배민"), ("baemin.com",)),
    # 쇼핑
    (("11번가",), ("11st.co.kr",)),
    (("g마켓", "지마켓"), ("gmarket.co.kr",)),
    (("옥션",), ("auction.co.kr",)),
    (("ssg", "쓱", "신세계몰"), ("ssg.com", "shinsegae.com")),
    (("롯데온", "롯데닷컴"), ("lotteon.com", "lotte.com")),
    (("무신사",), ("musinsa.com",)),
    (("인터파크",), ("interpark.com",)),
    (("알리익스프레스", "알리"), ("aliexpress.com",)),
    (("테무", "temu"), ("temu.com",)),
    # 보험 — 어르신 광고에 가장 자주 나오는 업종. 사이트를 거의 다 자바스크립트로 그려
    # 본문을 읽을 수 없으므로 표에 있어야 도메인만으로 공식 확인 가능. 도메인은 전부 현장
    # 확인(본문 상호 또는 인증서 발급 대상). 실광고(AXA 다이렉트)에서 "확인 불가" 오판 발생(2026-08-23)
    (("삼성생명", "samsunglife"), ("samsunglife.com",)),
    (("삼성화재", "애니카", "samsungfire"), ("samsungfire.com",)),
    (("현대해상", "하이카"), ("hi.co.kr",)),
    (("db손해보험", "db손보", "동부화재", "idbins"), ("idbins.com",)),
    (("kb손해보험", "kb손보", "kbinsure"), ("kbinsure.co.kr",)),
    (("라이나생명", "라이나"), ("lina.co.kr",)),
    (("교보생명", "kyobo"), ("kyobo.com",)),
    (("한화생명", "hanwhalife"), ("hanwhalife.com",)),
    (("메리츠화재", "meritz"), ("meritzfire.com",)),
    (("AXA손해보험", "악사", "악사다이렉트", "axa"), ("axa.co.kr",)),
    (("캐롯손해보험", "캐롯", "carrot"), ("carrotins.com",)),
    # 증권·거래소
    (("키움증권",), ("kiwoom.com",)),
    (("미래에셋",), ("miraeasset.com",)),
    (("삼성증권",), ("samsungpop.com",)),
    (("nh투자증권",), ("nhqv.com",)),
    (("한국투자증권",), ("truefriend.com",)),
    (("업비트",), ("upbit.com",)),
    (("빗썸",), ("bithumb.com",)),
    # 항공·여행
    (("대한항공",), ("koreanair.com",)),
    (("아시아나",), ("flyasiana.com",)),
    (("야놀자",), ("yanolja.com",)),
    (("여기어때",), ("goodchoice.kr",)),
]


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s).lower()


def official_domains_of(brand: str) -> list[str]:
    """브랜드 이름(별칭 포함) → 공식 등록 도메인들. 모르면 빈 목록."""
    q = _norm(brand)
    if not q:
        return []
    for aliases, domains in BRANDS:
        for a in aliases:
            na = _norm(a)
            if q == na or (len(na) >= 2 and na in q) or (len(q) >= 2 and q in na):
                return list(domains)
    return []


def brand_of_domain(host_or_url: str) -> tuple[str, list[str]] | None:
    """도메인 → (브랜드 대표 이름, 공식 도메인들). 하위 도메인 포함. 표에 없으면 None.

    본문을 자바스크립트로 그리는 사이트는 모델이 아는 것이 주소뿐 — 그때 "이 주소가
    어느 회사 공식 도메인인가"를 물을 수 있어야 함. 이름만 받으면 모델이 도메인에서
    브랜드를 짐작해야 하고, 빗나가면 확인 불가
    """
    host = host_of(host_or_url) if "://" in host_or_url else host_or_url.strip().lower()
    host = (host or "").split("/")[0].split("?")[0].strip(".")
    host = host[4:] if host.startswith("www.") else host
    if not host or "." not in host:
        return None
    for aliases, domains in BRANDS:
        if any(same_site(host, d) for d in domains):
            return aliases[0], list(domains)
    return None


def _looks_like_domain(q: str) -> bool:
    q = q.strip().lower()
    return "://" in q or bool(re.fullmatch(r"[a-z0-9.\-]+\.[a-z]{2,}(/.*)?", q))


def brand_mentions(text: str) -> list[str]:
    """본문·제목에 등장하는 표 안의 브랜드들. 두 글자 별칭은 오탐이 많아 세 글자 이상만 대상"""
    t = _norm(text)
    found: list[str] = []
    for aliases, _ in BRANDS:
        for a in aliases:
            na = _norm(a)
            if len(na) >= 3 and na in t:
                found.append(aliases[0])
                break
    return found


# ── 주소 도우미 ───────────────────────────────────────────────────────────────


def host_of(url: str) -> str | None:
    m = re.match(r"https?://([^/?#]+)", url.lower())
    return m.group(1).split(":")[0].strip(".") if m else None


def site_of(host_or_url: str) -> str | None:
    """등록 도메인 (co.kr류는 세 조각). main._site_of와 같은 규칙."""
    host = host_of(host_or_url) if "://" in host_or_url else host_or_url.lower()
    if not host:
        return None
    parts = host.split(".")
    if len(parts) <= 2:
        return host
    two_level = {"co", "or", "ne", "go", "ac", "re", "pe"}
    take = 3 if len(parts[-1]) <= 3 and parts[-2] in two_level else 2
    return ".".join(parts[-take:])


def same_site(host: str, official: str) -> bool:
    h = host.lower().strip(".")
    o = official.lower().strip(".")
    return h == o or h.endswith("." + o)


# ── 도메인 등록일 (RDAP) ──────────────────────────────────────────────────────
#
# rdap.org가 TLD별 레지스트리로 중계. .kr(KRNIC)은 RDAP 미제공 → 조회 없이 "모름"
# 조회는 백그라운드(domain_age_start) — 판정은 기록만 읽음. 한 번 본 도메인은 DB 기록


def _ensure_age_table(db: sqlite3.Connection) -> None:
    db.execute(
        "CREATE TABLE IF NOT EXISTS domain_age("
        " domain TEXT PRIMARY KEY, registered TEXT, checked TEXT)"
    )


_age_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="rdap")
_age_lock = threading.Lock()
_age_inflight: dict[str, Future] = {}


def domain_age_cached(site: str, db_path: str) -> tuple[int | None, str] | None:
    """DB 기록만 조회. (경과일, 설명) — 기록 없음·실패 기록 만료면 None"""
    now = datetime.now(timezone.utc)
    with sqlite3.connect(db_path) as db:
        _ensure_age_table(db)
        row = db.execute("SELECT registered, checked FROM domain_age WHERE domain=?", (site,)).fetchone()
    if row is None:
        return None
    reg, checked = row
    if not reg:
        try:
            age = (now - datetime.fromisoformat(checked)).total_seconds()
        except (TypeError, ValueError):
            age = RDAP_FAIL_RETRY_S
        if age >= RDAP_FAIL_RETRY_S:
            return None                      # 실패 기록 만료 → 재조회 대상
        return None, "등록일을 알 수 없음 (조회 실패 기록)"
    days = (now - datetime.fromisoformat(reg)).days
    return days, f"등록일 {reg[:10]} (캐시)"


def _rdap_fetch_store(site: str, db_path: str) -> tuple[int | None, str]:
    """RDAP 조회 후 DB 기록. 백그라운드 스레드 전용"""
    now = datetime.now(timezone.utc)
    registered: str | None = None
    note = "등록일을 알 수 없음"
    try:
        r = _http.get(
            f"https://rdap.org/domain/{site}", timeout=RDAP_BG_TIMEOUT_S,
            headers={"Accept": "application/rdap+json", "User-Agent": _MOBILE_UA},
            allow_redirects=True,
        )
        if r.status_code == 200:
            data = r.json()
            for ev in data.get("events", []):
                if ev.get("eventAction") == "registration" and ev.get("eventDate"):
                    d = ev["eventDate"].replace("Z", "+00:00")
                    registered = datetime.fromisoformat(d).astimezone(timezone.utc).isoformat()
                    break
            if not registered:
                note = "RDAP 응답에 등록일이 없음"
        elif r.status_code == 404:
            note = "RDAP에 없는 도메인 (.kr 등 미지원 TLD이거나 미등록)"
        else:
            note = f"RDAP 응답 {r.status_code}"
    except Exception as e:  # 타임아웃·네트워크 — 조용히 "모름"
        note = f"RDAP 조회 실패 ({type(e).__name__})"

    with sqlite3.connect(db_path) as db:
        _ensure_age_table(db)
        db.execute(
            "INSERT OR REPLACE INTO domain_age(domain, registered, checked) VALUES(?,?,?)",
            (site, registered, now.isoformat()),
        )
    if registered:
        return (now - datetime.fromisoformat(registered)).days, f"등록일 {registered[:10]}"
    return None, note


def domain_age_start(site: str, db_path: str) -> Future:
    """백그라운드 조회 시작. 같은 도메인 동시 요청은 하나로 합침"""
    with _age_lock:
        f = _age_inflight.get(site)
        if f is not None:
            return f
        f = _age_pool.submit(_rdap_fetch_store, site, db_path)
        _age_inflight[site] = f

        def _done(_):
            with _age_lock:
                _age_inflight.pop(site, None)
        f.add_done_callback(_done)
        return f


def domain_age_days(site: str, db_path: str, wait_s: float = RDAP_TIMEOUT_S) -> tuple[int | None, str]:
    """(등록 후 경과일, 설명). 모르면 (None, 이유)

    기록 있으면 즉답. 없으면 백그라운드 조회를 걸고 wait_s까지만 대기 — 0이면 기다리지 않음.
    못 기다린 결과는 DB에 남아 다음 판정에서 즉답. RDAP 없는 TLD(.kr)는 조회 자체 없음
    """
    if site.rsplit(".", 1)[-1].lower() in NO_RDAP_TLDS:
        return None, "RDAP 미지원 TLD (.kr) — 등록일 조회 불가"
    hit = domain_age_cached(site, db_path)
    if hit is not None:
        return hit
    f = domain_age_start(site, db_path)
    try:
        return f.result(timeout=max(0.0, wait_s))
    except FutureTimeout:
        return None, "등록일 조회 중 (이번 판정에는 없음 — 다음 판정에 반영)"
    except Exception as e:
        return None, f"RDAP 조회 실패 ({type(e).__name__})"


# ── 페이지 읽기·신호 ──────────────────────────────────────────────────────────


# lxml이 있으면 그것으로 — html.parser보다 3~5배 빠름. B1 싱글코어 실측(2026-08-24): 8.7k자 페이지 신호 추출 750ms
try:
    import lxml  # noqa: F401
    _PARSER = "lxml"
except Exception:
    _PARSER = "html.parser"


def _soup(html_doc: str) -> BeautifulSoup:
    return BeautifulSoup(html_doc, _PARSER)


def _text_of(html_doc: str) -> str:
    soup = _soup(html_doc)
    for tag in soup(["script", "style", "noscript"]):
        tag.decompose()
    return re.sub(r"\s+", " ", soup.get_text(" ")).strip()


def _get_capped(url: str, deadline: float) -> requests.Response:
    """GET 한 번 — 마감(monotonic)까지만, 본문 FETCH_MAX_BYTES까지만

    requests의 timeout은 소켓 동작 하나의 시한 — 조금씩 오래 흘러오는 본문은 막지 못함.
    스트리밍으로 받으며 마감·상한에서 끊고, 받은 만큼을 본문으로 사용
    """
    left = max(0.3, deadline - time.monotonic())
    r = _http.get(
        url, timeout=(min(2.0, left), left), headers={"User-Agent": _MOBILE_UA},
        allow_redirects=True, stream=True,
    )
    chunks: list[bytes] = []
    size = 0
    try:
        for c in r.iter_content(8192):
            chunks.append(c)
            size += len(c)
            if size >= FETCH_MAX_BYTES or time.monotonic() >= deadline:
                break
    finally:
        r.close()
    # 받은 만큼을 r.content로 — 이후 r.text(인코딩 추정 포함)는 평소와 동일
    r._content = b"".join(chunks)
    r._content_consumed = True
    return r


def fetch_chain(url: str, timeout: float = FETCH_TIMEOUT_S) -> dict:
    """HTTP 리다이렉트는 requests, JS·meta 리다이렉트는 직접 추적 (최대 MAX_REDIRECT_HOPS)

    예전: 한 홉만 추적 → "최종 도착지가 아닌 중간 페이지"를 읽는 문제
    timeout = 홉 전부 합친 **전체** 상한. 마감이 지나면 그때까지 받은 페이지로 종료
    """
    deadline = time.monotonic() + timeout
    chain: list[str] = []
    cur = url
    last = None
    for _ in range(MAX_REDIRECT_HOPS + 1):
        r = _get_capped(cur, deadline)
        last = r
        chain.append(str(r.url))
        text = _text_of(r.text)
        if len(text) >= 200:
            break
        m = _JS_REDIRECT.search(r.text)
        if not m:
            break
        nxt = next(g for g in m.groups() if g)
        nxt = urljoin(str(r.url), _html.unescape(nxt).replace("\\/", "/"))
        if nxt in chain or nxt == cur or time.monotonic() >= deadline:
            break
        cur = nxt
    assert last is not None
    text = _text_of(last.text)
    return {
        "final_url": str(last.url),
        "status": last.status_code,
        "chain": chain,
        "html": last.text,
        "text": text[:MAX_PAGE_CHARS],
        "text_chars": len(text),
        "full_text": text,   # page_signals 재사용용 (응답에는 안 실림)
    }


_RRN = re.compile(r"주민\s*(등록)?\s*번호|주민번호|\brrn\b|resident", re.I)
_CARD = re.compile(r"카드\s*번호|card\s*(number|no)|cvc|cvv", re.I)
_PHONE = re.compile(r"전화|휴대폰|연락처|phone|tel\b", re.I)
_ACCOUNT = re.compile(r"계좌\s*번호|account\s*(number|no)", re.I)


def page_signals(html_doc: str, final_url: str, body_text: str | None = None) -> dict:
    """JS 미실행에도 HTML 원문에 남는 신호 — 본문이 비어도 사칭 대부분 탐지 가능

    폼(입력 항목·전송처), 외부 스크립트·iframe 호스트, APK 링크, 브랜드 언급
    body_text: fetch_chain이 이미 뽑은 본문 — 주면 재추출(파싱 한 번) 생략
    """
    soup = _soup(html_doc)
    host = host_of(final_url) or ""
    mysite = site_of(host) if host else None
    title = (soup.title.get_text(" ", strip=True) if soup.title else "")[:200]

    forms = []
    for f in soup.find_all("form")[:6]:
        action = f.get("action") or ""
        action_host = host_of(urljoin(final_url, action)) if action else host
        fields = []
        flags = set()
        for inp in f.find_all(["input", "select", "textarea"])[:20]:
            t = (inp.get("type") or inp.name or "").lower()
            label = " ".join(filter(None, [inp.get("name"), inp.get("id"), inp.get("placeholder"), inp.get("autocomplete")]))
            fields.append(f"{t}:{label[:30]}" if label else t)
            if t == "password":
                flags.add("password")
            if _RRN.search(label):
                flags.add("rrn")
            if _CARD.search(label):
                flags.add("card")
            if _PHONE.search(label) or t == "tel":
                flags.add("phone")
            if _ACCOUNT.search(label):
                flags.add("account")
        forms.append({
            "action_host": action_host,
            "external_action": bool(action_host and mysite and site_of(action_host) != mysite),
            "fields": fields[:12],
            "asks": sorted(flags),
        })

    def ext_hosts(tag: str, attr: str) -> list[str]:
        out: list[str] = []
        for el in soup.find_all(tag):
            src = el.get(attr)
            if not src:
                continue
            h = host_of(urljoin(final_url, src))
            if h and mysite and site_of(h) != mysite and h not in out:
                out.append(h)
        return out[:8]

    apk_links = []
    for a in soup.find_all("a", href=True):
        href = a["href"]
        if re.search(r"\.apk(\?|$)", href, re.I):
            apk_links.append(urljoin(final_url, href)[:120])
    for m in re.finditer(r"""https?://[^\s"'<>]+\.apk\b""", html_doc, re.I):
        if m.group(0)[:120] not in apk_links:
            apk_links.append(m.group(0)[:120])

    if body_text is None:
        body_text = _text_of(html_doc)
    mentions = brand_mentions(title + " " + body_text[:6000] + " " + html_doc[:3000])
    # 브랜드가 언급됐는데 도메인이 그 브랜드 것이 아니면 — 사칭 후보
    mismatches = []
    for b in mentions:
        offs = official_domains_of(b)
        if offs and host and not any(same_site(host, o) for o in offs):
            mismatches.append({"brand": b, "official": offs, "actual_host": host})

    meta_refresh = bool(re.search(r'http-equiv=["\']refresh["\']', html_doc, re.I))
    return {
        "title": title,
        "host": host,
        "text_chars": len(body_text),
        "js_only": len(body_text) < 200,   # 본문 사실상 비어 있음 — JS 렌더링 페이지
        "meta_refresh": meta_refresh,
        "forms": forms,
        "password_field": any("password" in f["asks"] for f in forms),
        "asks_rrn": any("rrn" in f["asks"] for f in forms),
        "asks_card": any("card" in f["asks"] for f in forms),
        "external_scripts": ext_hosts("script", "src"),
        "iframes": ext_hosts("iframe", "src"),
        "apk_links": apk_links[:5],
        "brand_mentions": mentions,
        "brand_domain_mismatch": mismatches,
    }


# ── 도구 정의 (Claude에게 보여주는 것) ───────────────────────────────────────

TOOLS = [
    {
        "name": "fetch_page",
        "description": "주소를 열어 리다이렉트(HTTP·JS·meta)를 끝까지 따라간 최종 주소와 본문 텍스트를 돌려준다. "
                       "본문이 비어 있으면(text_chars가 작으면) JS로 그려지는 페이지다 — page_signals로 HTML 원문을 보라.",
        "input_schema": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]},
    },
    {
        "name": "page_signals",
        "description": "HTML 원문에서 JS 없이도 보이는 신호: 제목, 폼이 무엇을 입력받고 어디로 보내는지(비밀번호·주민번호·카드번호), "
                       "외부 스크립트·iframe 호스트, APK 다운로드 링크, 브랜드 언급과 도메인 불일치(brand_domain_mismatch). "
                       "본문이 비어 있을 때, 또는 사칭이 의심될 때 쓴다.",
        "input_schema": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]},
    },
    {
        "name": "check_blocklist",
        "description": "호스트가 악성(피싱·멀웨어) 도메인 목록에 있는지. 하위 도메인까지 본다.",
        "input_schema": {"type": "object", "properties": {"host": {"type": "string"}}, "required": ["host"]},
    },
    {
        "name": "lookup_cache",
        "description": "이 사이트(등록 도메인)에 대해 전에 내린 판정 기록. 같은 광고주를 다시 만났을 때 쓴다.",
        "input_schema": {"type": "object", "properties": {"site": {"type": "string"}}, "required": ["site"]},
    },
    {
        "name": "official_domain_of",
        "description": "브랜드(은행·보험·공공기관·통신사·택배·쇼핑몰 등)의 공식 도메인. 쓰는 법이 둘이다 — "
                       "① 페이지가 어떤 브랜드를 자처할 때 **이름**을 넣어 실제 도메인과 비교해 사칭인지 가린다. "
                       "② 본문을 못 읽었을 때 **도착한 도메인 자체**(예: axa.co.kr)를 넣어 어느 회사의 공식 도메인인지 묻는다. "
                       "표에 없으면 known=false — 표에 없다는 것은 사칭의 근거가 아니다.",
        "input_schema": {"type": "object", "properties": {"brand": {"type": "string", "description": "브랜드 이름 또는 도메인"}},
                         "required": ["brand"]},
    },
    {
        "name": "domain_age",
        "description": "도메인이 등록된 지 며칠 됐는지 (RDAP). 30일 미만이면 새 도메인 — 사칭·피싱 도메인의 흔한 특징. "
                       ".kr 도메인은 조회가 안 돼 '모름'이 나온다.",
        "input_schema": {"type": "object", "properties": {"host": {"type": "string"}}, "required": ["host"]},
    },
    {
        "name": "final_verdict",
        "description": "최종 판정. 더 볼 것이 없거나 예산이 다 되면 이것을 부른다. evidence는 도구 결과에서 **그대로 옮긴** 짧은 구절이어야 한다.",
        "input_schema": {
            "type": "object",
            "properties": {
                # 필드 순서 = 출력 순서. risk가 맨 앞이라 스트리밍 중 등급을 먼저 읽을 수 있음(run_agent on_early)
                "risk": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                "type": {"type": "string", "enum": [
                    "impersonation", "credentials", "apk", "personal_info", "payment",
                    "urgency", "investment", "contentfarm", "unverifiable", "none",
                ]},
                # 짧게 — 출력 토큰이 곧 지연(실측 2026-08-24: 출력 300토큰에 2.5~3.5초). 어르신 화면도 짧은 쪽이 읽기 쉬움
                "reason": {"type": "string", "description": "어르신이 읽을 한 문장, 30자 이내"},
                "advice": {"type": "string", "description": "어르신이 읽을 한 문장, 25자 이내"},
                # 근거 검증(_ground)은 고위험에만 쓰이고 구절 하나면 충분
                "evidence": {"type": "string", "description": "도구 결과에서 그대로 옮긴 근거 — 핵심 구절 하나, 40자 이내. 저위험이면 빈 문자열"},
            },
            "required": ["risk", "type", "reason", "advice", "evidence"],
        },
    },
]

AGENT_RULES = """\

## 도구 사용 규칙
첫 메시지에 **미리 모아 둔 것**(페이지 본문·페이지 신호·차단 목록·전 판정·공식 도메인·도메인 등록일)이 들어 있다.
그것으로 충분하면 **도구를 더 쓰지 말고 바로 final_verdict**를 내라 — 평범한 쇼핑몰·기사가 대부분 그렇다.
더 볼 것이 있을 때만 도구를 쓴다. 어떤 도구를, 몇 개, 어떤 순서로 쓸지는 네가 정한다.
- 본문이 비어 있으면(text_chars < 200) 끝내지 말고 page_signals를 보라. JS 페이지도 HTML 원문에 폼·브랜드·스크립트 호스트가 남는다.
- 페이지가 브랜드(은행·기관·택배·쇼핑몰)를 자처하면 official_domain_of로 공식 도메인을 확인하고, 실제 도메인과 다르면 domain_age까지 보라.
  "공식 도메인이 아님 + 등록 30일 미만"은 사칭의 강한 근거다.
- 본문이 비어 있는데(자바스크립트로 그리는 페이지) official_domain_of(도착 도메인)가 **공식 도메인으로 확인**해 주면 저위험이다.
  그것은 접속 실패도 빈 페이지도 아니다. fetch_page를 다시 쓰지 마라 — 같은 결과가 온다.
- 추가 도구는 최대 {max_calls}번이다. 예산이 끝나면 final_verdict만 부를 수 있다 — 그때까지 본 것으로 판정하라.
- evidence는 도구 결과에 실제로 있는 문장·값을 그대로 옮겨라. 도구 결과에 없는 근거로 고위험을 내면 중위험으로 내려간다.
- 도구가 '모름'·'실패'를 돌려주면 그것은 사실이 아니라 공백이다. 공백을 위험의 근거로 쓰지 마라. 남은 도구로 판단하라.
"""

# 한 번에 판정(oneshot) — 도구 없이 미리 모아 둔 것만으로. 왕복 1회 고정(JUDGE_ONESHOT=1)
# 실측(2026-08-23, 72건): 46%가 왕복 2회 이상, 추가 도구의 대부분이 page_signals(이미 줌)·
# domain_age(도착지)·official_domain_of(브랜드) — 전부 미리 모을 수 있는 것. 여기서는 그것까지 모아 줌
ONESHOT_RULES = """\

## 판정 규칙
첫 메시지에 **모아 둔 것**(페이지 본문·페이지 신호·차단 목록·전 판정·공식 도메인·도메인 등록일)이 전부다.
도구는 더 쓸 수 없다 — 이것만으로 final_verdict를 내라.
- 본문이 비어 있으면(text_chars < 200) page_signals를 보라. JS 페이지도 HTML 원문에 폼·브랜드·스크립트 호스트가 남는다.
- 페이지가 브랜드(은행·기관·택배·쇼핑몰)를 자처하는데 official_domain_of의 공식 도메인과 실제 도메인이 다르면 사칭 후보다.
  "공식 도메인이 아님 + 등록 30일 미만"은 사칭의 강한 근거다.
- 본문이 비어 있는데(자바스크립트로 그리는 페이지) official_domain_of(도착 도메인)가 **공식 도메인으로 확인**해 주면 저위험이다.
  그것은 접속 실패도 빈 페이지도 아니다.
- evidence는 모아 둔 결과에 실제로 있는 문장·값을 그대로 옮겨라. 결과에 없는 근거로 고위험을 내면 중위험으로 내려간다.
- '모름'·'실패'·'조회 중'은 사실이 아니라 공백이다. 공백을 위험의 근거로 쓰지 마라. 나머지로 판단하라.
"""


# ── 실행 ─────────────────────────────────────────────────────────────────────


@dataclass
class Deps:
    """main.py에서 빌려 오는 것들 — DB 경로, 차단 목록 조회, 판정 기록 조회"""
    db_path: str
    blocked_by: Callable[[str], str | None]
    cache_lookup: Callable[[str], dict | None]


@dataclass
class Run:
    verdict: dict
    trace: list[dict] = field(default_factory=list)
    final_url: str | None = None
    page_text: str | None = None
    tool_calls: int = 0
    llm_calls: int = 0


class _Budget:
    def __init__(self, started: float):
        self.started = started
        self.tool_calls = 0

    def left_s(self) -> float:
        return WALL_BUDGET_S - (time.monotonic() - self.started)

    def exhausted(self) -> bool:
        # 예전 0.8초 — 마지막 호출 시한이 사실상 없어 13초 판정 발생(실측 2026-08-23, 앱 12초 상한 초과)
        return self.tool_calls >= MAX_TOOL_CALLS or self.left_s() <= TOOL_MIN_LEFT_S

    def can_run_tool(self) -> bool:
        return self.left_s() > TOOL_MIN_LEFT_S


def _trim(s: str, n: int = 1500) -> str:
    return s if len(s) <= n else s[:n] + f"…(+{len(s) - n}자)"


def _prefetch(url: str, deps: Deps, budget: "_Budget", run_tool, oneshot: bool = False) -> dict:
    """흔한 판정 보조 도구를 먼저 실행 → 첫 질문에 넣을 요약 생성

    - fetch_page(리다이렉트 끝까지) → page_signals(같은 HTML, 추가 요청 없음)
    - check_blocklist · lookup_cache (SQLite, 즉시)
    - domain_age (DB 기록, 즉시 — 없으면 백그라운드 조회만 걸고 기다리지 않음) — 처음 주소의 도메인.
      도착지가 다른 도메인이면 그쪽도 (oneshot: 항상 / agent: 의심스러울 때만)
    - oneshot: 본문이 언급한 브랜드마다 official_domain_of(표 조회, 즉시) — 모델이 더 물을 것이 없게
    결과는 run_tool 경유 — trace와 근거 검증(outputs)에 그대로 잔존
    마감이 지나면 **기다리지 않고** 모인 것만으로 진행. 늦게 온 결과는 outputs에 남아 근거 검증에만 쓰임
    (예전: with ThreadPoolExecutor 블록이 종료 시 스레드 전부를 기다려 마감이 무효 — 27초 판정의 원인)
    """
    host = host_of(url) or ""
    site = site_of(host) or host
    # 첫 Claude 호출 몫(~3초)을 남김. 페이지 한도 3.5초가 그 안에 들어감
    deadline = max(1.0, budget.left_s() - 3.0)
    got: dict[str, str] = {}

    def page():
        got["fetch"] = run_tool("fetch_page", {"url": url})
        try:
            fr = json.loads(got["fetch"])
        except Exception:
            return
        if "error" in fr:
            return
        got["signals"] = run_tool("page_signals", {"url": fr.get("final_url") or url})
        try:
            sg = json.loads(got["signals"])
        except Exception:
            return
        fsite = site_of(fr.get("final_url") or "") or ""
        suspicious = sg.get("js_only") or sg.get("brand_domain_mismatch") or sg.get("password_field") or sg.get("apk_links")
        unreadable = sg.get("js_only") or (fr.get("status") or 0) >= 400
        if unreadable:
            # **답을 아는 것은 묻지 않음.** 본문이 비면 모델이 첫 바퀴에 부르는 도구가
            # official_domain_of(도착 도메인)인데 그 답은 우리 표에 있음 — 왕복 한 번이
            # 앱 시한을 넘기게 함(실측 2026-08-23: hi.co.kr 9.6초 > 9.5초). 미리 조회해 첫 질문에
            # 포함. run_tool 경유라 trace·근거 검증(outputs)에도 그대로 잔존
            # 403·404처럼 **본문을 못 받은 경우도 같음** — 쿠팡이 Azure IP를 403으로 막아 coupang.com이
            # "확인 불가(중위험)"로 나옴(실측 2026-08-24). 공식 도메인이면 못 읽어도 저위험이 기준
            fhost = host_of(fr.get("final_url") or url) or site
            got["official"] = run_tool("official_domain_of", {"brand": fhost})
            try:
                got_official_known = json.loads(got["official"]).get("known") is True
            except Exception:
                got_official_known = False
            got["_official_known"] = "1" if got_official_known else ""
            # 4xx가 우선 — 404의 빈 본문을 "자바스크립트 페이지"로 설명하면 틀림
            got["_unreadable"] = f"http {fr.get('status')}" if (fr.get("status") or 0) >= 400 else "js"
        if fsite and fsite != site and (suspicious or oneshot):
            got["age_final"] = run_tool("domain_age", {"host": fsite})
        if oneshot:
            # 브랜드마다 공식 도메인 — 표 조회라 즉시. 에이전트 모드에서 모델이 두 번째 왕복에 묻던 것
            brands = [b for b in (sg.get("brand_mentions") or []) if isinstance(b, str)][:6]
            got["brands"] = "\n".join(
                f"  - {b}: {run_tool('official_domain_of', {'brand': b})}" for b in brands
            ) if brands else "(본문에 표 안의 브랜드 언급 없음)"

    def fast():
        got["blocklist"] = run_tool("check_blocklist", {"host": host})
        got["cache"] = run_tool("lookup_cache", {"site": site})

    def age():
        got["age"] = run_tool("domain_age", {"host": site})

    ex = ThreadPoolExecutor(max_workers=3, thread_name_prefix="prefetch")
    futs = [ex.submit(page), ex.submit(fast), ex.submit(age)]
    wait(futs, timeout=deadline)
    ex.shutdown(wait=False)          # 늦은 스레드는 두고 감 — 결과는 outputs(근거 검증)에만 잔존

    lines = []
    for key, label in (("fetch", "fetch_page"), ("signals", "page_signals"), ("official", "official_domain_of(도착 도메인)"),
                       ("brands", "official_domain_of(언급된 브랜드)"),
                       ("blocklist", "check_blocklist"), ("cache", "lookup_cache"), ("age", "domain_age"),
                       ("age_final", "domain_age(도착지)")):
        if key in got:
            lines.append(f"- {label}: {_trim(got[key], 3000 if key == 'fetch' else 1200)}")
        elif key not in ("official", "brands"):   # 본문이 있을 때는 조회하지 않은 것 — "못 받음"이 아님
            lines.append(f"- {label}: (시간 안에 못 받음)")
    out = {"summary": "\n".join(lines), "js_only": got.get("_unreadable") == "js",
           "unreadable": got.get("_unreadable"), "official_known": bool(got.get("_official_known"))}
    try:
        fr = json.loads(got.get("fetch", "{}"))
        out["final_url"] = fr.get("final_url")
        out["text"] = fr.get("text")
    except Exception:
        pass
    return out


_RISK_IN_JSON = re.compile(r'"risk"\s*:\s*"(LOW|MEDIUM|HIGH)"')


# 스트리밍 첫 이벤트 대기 상한. 정상이면 1초 안에 옴 — 이보다 늦으면 연결 문제로 보고 일반 호출로 대체
STREAM_FIRST_EVENT_S = 3.5


def _ask(client, *, model: str, system: list, tools: list, tool_choice: dict, messages: list,
         timeout: float, on_risk: Callable[[str], None] | None, note: Callable[[str], None] | None = None):
    """Claude 호출 한 번. on_risk가 있으면 스트리밍 — final_verdict 입력 JSON에서 risk가
    읽히는 즉시 on_risk(risk). 반환은 두 경우 모두 완성된 Message

    스트리밍의 timeout은 읽기 한 번의 시한이라 전체를 막지 못함 — 마감은 여기서 직접 강제.
    스트리밍이 **시작도 못 하면**(실측 2026-08-23 Azure: 헤더조차 없이 APITimeoutError) 남은 시간으로
    일반 호출 — 선행 통보만 포기하고 판정은 살림. note(문장)로 경로를 trace에 남김
    """
    kw = dict(model=model, max_tokens=MAX_TOKENS, system=system, tools=tools,
              tool_choice=tool_choice, messages=messages, timeout=timeout)
    if on_risk is None:
        return client.messages.create(**kw)
    started = time.monotonic()
    deadline = started + timeout
    try:
        return _ask_stream(client, kw, deadline, on_risk)
    except _StreamNeverStarted as e:
        left = deadline - time.monotonic()
        if note:
            note(f"스트리밍 시작 실패({e.args[0]}) → 일반 호출(남은 {left:.1f}초)")
        log.warning(f"스트리밍 시작 실패: {e.args[0]} → 일반 호출")
        if left < 1.5:
            raise
        return client.messages.create(**{**kw, "timeout": left})


class _StreamNeverStarted(Exception):
    pass


def _ask_stream(client, kw: dict, deadline: float, on_risk: Callable[[str], None]):
    first_kw = {**kw, "timeout": min(kw["timeout"], STREAM_FIRST_EVENT_S)}
    try:
        manager = client.messages.stream(**first_kw)
        stream = manager.__enter__()     # 여기서 요청·응답 헤더까지. 실패 = 시작도 못 한 것
    except Exception as e:   # 연결·헤더 단계 실패 — 일반 호출로 대체 가능
        raise _StreamNeverStarted(type(e).__name__) from e
    try:
        buf = ""
        in_final = False
        told = False
        for ev in stream:
            t = getattr(ev, "type", "")
            if t == "content_block_start":
                cb = ev.content_block
                in_final = getattr(cb, "type", "") == "tool_use" and getattr(cb, "name", "") == "final_verdict"
                buf = ""
            elif t == "content_block_delta" and in_final and not told \
                    and getattr(ev.delta, "type", "") == "input_json_delta":
                buf += ev.delta.partial_json
                m = _RISK_IN_JSON.search(buf)
                if m:
                    told = True
                    on_risk(m.group(1))
            if time.monotonic() > deadline:
                raise TimeoutError("스트리밍 마감 초과")
        return stream.get_final_message()
    finally:
        manager.__exit__(None, None, None)   # 스트림·연결 반납 (예외 시에도)


def run_agent(client, model: str, criteria: str, url: str, click_url: str | None,
              deps: Deps, started: float | None = None, oneshot: bool = False,
              on_early: Callable[[str, str | None], None] | None = None) -> Run:
    """도구 루프 — Claude의 final_verdict 호출 또는 예산 소진까지 반복

    oneshot: 도구 없이 미리 모아 둔 것만으로 첫 호출에서 final_verdict 강제 (왕복 1회)
    on_early(risk, final_url): 스트리밍 중 final_verdict의 risk가 읽히는 즉시 호출 — reason·advice가
      다 써지기 전. 저위험은 폰이 쉴드만 걷으면 되므로 문장을 기다릴 이유가 없음(1~2초 절감)
    """
    started = started if started is not None else time.monotonic()
    budget = _Budget(started)
    trace: list[dict] = []
    outputs: list[str] = []          # 근거 검증용 — 도구가 돌려준 모든 텍스트
    html_cache: dict[str, str] = {}  # page_signals가 fetch_page의 HTML을 재사용
    text_cache: dict[str, str] = {}  # 같은 HTML의 본문 — 파싱 한 번 더 안 함
    final_url: str | None = None
    page_text: str | None = None
    messages: list[dict] = []   # 비어 있으면 아직 미리 모으는 중(phase=pre)

    def record(name: str, args: dict, t0: float, summary: str, result: dict | str) -> str:
        text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)
        outputs.append(text)
        trace.append({"tool": name, "args": args, "ms": int((time.monotonic() - t0) * 1000), "summary": summary,
                      "phase": "pre" if not messages else "agent"})
        return text

    def run_tool(name: str, args: dict) -> str:
        nonlocal final_url, page_text
        t0 = time.monotonic()
        try:
            if name == "fetch_page":
                u = args.get("url") or url
                timeout = max(1.0, min(FETCH_TIMEOUT_S, budget.left_s() - 1.5))
                r = fetch_chain(u, timeout=timeout)
                html_cache[u] = r["html"]
                html_cache[r["final_url"]] = r["html"]
                text_cache[r["html"][:200]] = r["full_text"]
                final_url = final_url or r["final_url"]
                page_text = page_text or r["text"]
                out = {k: v for k, v in r.items() if k not in ("html", "full_text")}
                return record(name, args, t0, f"{r['status']} {r['text_chars']}자 hops={len(r['chain'])}", out)
            if name == "page_signals":
                u = args.get("url") or final_url or url
                doc = html_cache.get(u)
                fu = u
                if doc is None:
                    timeout = max(1.0, min(SIGNAL_FETCH_TIMEOUT_S, budget.left_s() - 1.5))
                    r = fetch_chain(u, timeout=timeout)
                    doc, fu = r["html"], r["final_url"]
                    html_cache[u] = doc
                    final_url = final_url or fu
                else:
                    fu = final_url if (final_url and html_cache.get(final_url) is doc) else u
                sig = page_signals(doc, fu, body_text=text_cache.get(doc[:200]))
                return record(name, args, t0, f"폼{len(sig['forms'])} 브랜드{sig['brand_mentions']} 불일치{len(sig['brand_domain_mismatch'])}", sig)
            if name == "check_blocklist":
                h = args.get("host", "")
                src = deps.blocked_by(f"https://{h}/")
                return record(name, args, t0, src or "없음", {"host": h, "listed": bool(src), "list": src})
            if name == "lookup_cache":
                s = site_of(args.get("site", "")) or args.get("site", "")
                prev = deps.cache_lookup(s)
                return record(name, args, t0, prev["risk"] if prev else "기록 없음", {"site": s, "previous": prev})
            if name == "official_domain_of":
                b = args.get("brand", "")
                if _looks_like_domain(b):
                    # **도메인으로 물었으면 여기서 종료** — 이름 대조로 흘리면 짧은 별칭이 아무 도메인에나
                    # 걸림. 실측(2026-08-23): srchdiscounts.com이 국세청 별칭 "nts"에 걸려
                    # "국세청 공식 도메인: hometax.go.kr"로 답함 → 광고 착지지가 국세청 사칭으로 읽힐 뻔
                    hit = brand_of_domain(b)
                    if hit:
                        name_, offs = hit
                        out = {"domain": b, "brand": name_, "official_domains": offs, "known": True,
                               "note": f"{b}은(는) {name_}의 공식 도메인이다"}
                        return record(name, args, t0, f"{name_} 공식", out)
                    out = {"domain": b, "known": False,
                           "note": f"{b}은(는) 공식 도메인 표에 없다 — 표에 없다는 것은 사칭의 근거가 아니다"}
                    return record(name, args, t0, "표에 없음", out)
                offs = official_domains_of(b)
                return record(name, args, t0, ",".join(offs) or "표에 없음", {"brand": b, "official_domains": offs, "known": bool(offs)})
            if name == "domain_age":
                s = site_of(args.get("host", "")) or args.get("host", "")
                # 미리 모으기 단계는 대기 0 — 기록 없으면 백그라운드 조회만 걸고 "조회 중". 모델이 직접
                # 부를 때만(사칭 의심 등) 남은 예산 안에서 RDAP_TIMEOUT_S까지 대기
                wait_s = 0.0 if not messages else max(0.0, min(RDAP_TIMEOUT_S, budget.left_s() - TOOL_MIN_LEFT_S))
                days, note = domain_age_days(s, deps.db_path, wait_s=wait_s)
                out = {"domain": s, "age_days": days, "young": (days is not None and days < YOUNG_DOMAIN_DAYS), "note": note}
                return record(name, args, t0, f"{days}일" if days is not None else note, out)
            return record(name, args, t0, "모르는 도구", {"error": f"unknown tool {name}"})
        except Exception as e:
            return record(name, args, t0, f"실패 {type(e).__name__}", {"error": f"{type(e).__name__}: {str(e)[:120]}"})

    # ── 미리 모으기 — 서로 독립인 것들을 **동시에** 수집 (최대 ~3.5초)
    # 예전: 읽기 → 묻기 → 도구 → 묻기 직렬 → Claude 왕복(2~4초) 두세 번 누적
    # 흔한 도구 결과를 첫 질문에 포함 → 대부분 **왕복 한 번**으로 종료
    pre = _prefetch(url, deps, budget, run_tool, oneshot=oneshot)
    final_url = final_url or pre.get("final_url")
    page_text = page_text or pre.get("text")

    system = criteria + (ONESHOT_RULES if oneshot else AGENT_RULES.format(max_calls=MAX_TOOL_CALLS))
    first = f"광고를 눌러 도착한 주소: {url}"
    if click_url and click_url != url:
        first += f"\n누른 광고의 링크: {click_url}"
    first += "\n\n## 미리 모아 둔 것\n" + pre["summary"]
    if pre.get("js_only"):
        # 200으로 받았지만 본문을 스크립트가 그리는 페이지 — 접속 실패가 아님. 큰 회사 사이트에서
        # 흔한 방식(실측: axa.co.kr·hi.co.kr·kbinsure.co.kr·kyobo.com 전부 <title>조차 없는 빈 껍데기).
        # 접속 실패와 묶으면 제대로 만든 대기업 사이트일수록 "확인 불가(중위험)"가 뜨는 역설
        first += ("\n\n본문이 비어 있는 것은 페이지를 자바스크립트로 그리기 때문이다 — 접속 실패도 빈 페이지도 아니다. "
                  + ("" if oneshot else "fetch_page를 다시 쓰지 마라(같은 결과가 온다)."))
    elif pre.get("unreadable"):
        # 403·404 — 서버(Azure) IP를 막거나 없는 페이지. 사용자 폰에서는 열렸을 수 있음
        first += (f"\n\n페이지를 받지 못했다({pre['unreadable']}) — 이 서버의 접속을 막았을 수 있다. "
                  "본문 없이 판단해야 한다.")
    if pre.get("unreadable"):
        if pre.get("official_known"):
            first += ("\n도착 도메인이 공식 도메인 표에서 확인됐으니 저위험이다. reason에는 어느 회사의 공식 사이트인지 쓰고, "
                      "evidence에는 위 official_domain_of 결과의 note를 그대로 옮겨라.")
        else:
            first += ("\n도착 도메인은 공식 도메인 표에 없다(사칭의 근거는 아니다). page_signals·차단 목록·등록일로 판단하고, "
                      "위험 근거가 없으면 확인 불가(중위험)로 둔다.")
    first += ("\n\n이것으로 final_verdict를 내라." if oneshot
              else "\n\n충분하면 바로 final_verdict. 더 봐야 할 것이 있을 때만 도구를 써라.")
    messages.append({"role": "user", "content": first})
    # 시스템 프롬프트·도구 정의는 요청마다 동일 — 캐시로 호출마다 앞부분 처리 시간 절감
    # (Haiku 4.5는 4096토큰부터 캐시 — 지금 접두부는 그 언저리. usage.cache_read_input_tokens로 확인)
    system_blocks = [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]
    # final_verdict는 입력 JSON을 즉시 흘려보냄(eager) — risk 필드가 나오는 순간 on_early
    final_tool = {**TOOLS[-1], "eager_input_streaming": True, "cache_control": {"type": "ephemeral"}}
    tools = ([final_tool] if oneshot else [dict(t) for t in TOOLS[:-1]] + [final_tool])

    def on_risk(risk: str) -> None:
        if on_early is not None:
            try:
                on_early(risk, final_url)
            except Exception as e:   # 폰 쪽 통보 실패가 판정을 망치면 안 됨
                log.warning(f"on_early 실패: {type(e).__name__}: {e}")

    verdict: dict | None = None
    llm_calls = 0
    for _ in range(1 if oneshot else MAX_TOOL_CALLS + 2):
        force_final = oneshot or budget.exhausted()
        tool_choice = {"type": "tool", "name": "final_verdict"} if force_final else {"type": "any"}
        resp = None
        for attempt in (1, 2):
            # 시도 시한 = 하드 리밋까지 남은 시간(4~6초). 재시도는 3초 이상 남았을 때만 — 멈춘 연결 대비
            hard_left = HARD_LIMIT_S - (time.monotonic() - started)
            if attempt == 2 and hard_left < LLM_RETRY_MIN_LEFT_S:
                break
            try_timeout = max(LLM_TRY_MIN_S, min(LLM_TRY_MAX_S, hard_left))
            t_llm = time.monotonic()
            try:
                resp = _ask(client, model=model, system=system_blocks, tools=tools, tool_choice=tool_choice,
                            messages=messages, timeout=try_timeout,
                            on_risk=on_risk if on_early is not None else None,
                            note=lambda msg: trace.append({"tool": "llm", "args": {}, "ms": 0, "summary": msg, "phase": "agent"}))
                break
            except Exception as e:
                log.warning(f"에이전트 LLM 실패({attempt}차): {type(e).__name__}: {e}")
                # /recent 경로에도 남김 — 서버 로그 없이 실패 종류·걸린 시간 확인 가능
                trace.append({"tool": "llm", "args": {}, "ms": int((time.monotonic() - t_llm) * 1000),
                              "summary": f"실패{attempt} {type(e).__name__} {time.monotonic() - t_llm:.1f}s(시한 {try_timeout:.1f})",
                              "phase": "agent"})
        if resp is None:
            break
        llm_calls += 1
        # 호출 시간·캐시 적중을 경로에 — 지연 분석용 (Haiku 4.5는 접두부 4096토큰부터 캐시)
        u = getattr(resp, "usage", None)
        cached = getattr(u, "cache_read_input_tokens", None) if u else None
        trace.append({"tool": "llm", "args": {}, "ms": int((time.monotonic() - t_llm) * 1000),
                      "summary": f"{time.monotonic() - t_llm:.1f}s 입력{getattr(u, 'input_tokens', '?') if u else '?'} "
                                 f"캐시{cached if cached is not None else '?'} 출력{getattr(u, 'output_tokens', '?') if u else '?'}",
                      "phase": "agent"})
        uses = [b for b in resp.content if getattr(b, "type", "") == "tool_use"]
        if not uses:
            break
        messages.append({"role": "assistant", "content": resp.content})
        results = []
        for b in uses:
            if b.name == "final_verdict":
                verdict = dict(b.input)
                break
            budget.tool_calls += 1
            if not budget.can_run_tool():
                # 도구를 돌리면 마지막 판정 호출이 앱 시한을 넘김 — 실행 없이 예산 소진 통보.
                # 다음 바퀴는 exhausted()가 final_verdict를 강제
                out = json.dumps({"error": "시간 예산 소진 — 지금까지 본 것으로 final_verdict를 내라"}, ensure_ascii=False)
                trace.append({"tool": b.name, "args": dict(b.input), "ms": 0, "summary": "예산 소진(미실행)", "phase": "agent"})
            else:
                out = run_tool(b.name, dict(b.input))
            results.append({"type": "tool_result", "tool_use_id": b.id, "content": _trim(out, 3500)})
        if verdict is not None:
            break
        messages.append({"role": "user", "content": results})

    if verdict is None:
        verdict = {
            "risk": "UNKNOWN", "type": "unverifiable",
            "reason": "안전한지 확인하지 못했습니다.", "advice": "잘 모르겠으면 돌아가세요.", "evidence": "",
        }
    verdict = _ground(verdict, outputs)
    run = Run(verdict=verdict, trace=trace, final_url=final_url, page_text=page_text,
              tool_calls=budget.tool_calls, llm_calls=llm_calls)
    log.info(
        f"agent {url[:70]} -> {verdict['risk']}/{verdict.get('type')} "
        f"tools={[t['tool'] for t in trace]} llm={llm_calls} {time.monotonic() - started:.1f}s"
    )
    return run


def _ground(v: dict, outputs: list[str]) -> dict:
    """고위험의 근거는 도구 결과 어딘가에 실재 필수. 없으면 중위험으로 강등

    예전 _verify: "페이지 본문"만 확인 → "도메인이 2일 전 등록"처럼 도구가 알려준 근거는
    미인정·강등. 이제는 도구가 돌려준 모든 텍스트가 근거의 출처
    """
    v = {"risk": v.get("risk", "UNKNOWN"), "type": v.get("type") or "none",
         "reason": v.get("reason", ""), "advice": v.get("advice", ""), "evidence": v.get("evidence", "")}
    if v["risk"] == "LOW":
        v["type"] = "none"
    if v["risk"] != "HIGH":
        return v
    ev = re.sub(r"\s+", " ", v["evidence"]).strip()
    hay = re.sub(r"\s+", " ", " ".join(outputs))
    # 통째로 있으면 통과. 길면(여러 도구의 근거를 한 문장으로 모은 경우) 숫자·도메인 같은
    # 핵심 토큰 전부가 도구 결과에 있으면 통과 — "kb-star.top은 2일 전 등록, 공식은 kbstar.com"
    if ev and (ev in hay or _tokens_grounded(ev, hay)):
        return v
    log.info("고위험인데 근거가 도구 결과에 없다 -> MEDIUM으로 내림")
    return {**v, "risk": "MEDIUM"}


def _tokens_grounded(ev: str, hay: str) -> bool:
    toks = re.findall(r"[a-z0-9][a-z0-9.\-]{3,}|\d+", ev.lower())
    toks = [t for t in toks if len(t) >= 2]
    if not toks:
        return False
    low = hay.lower()
    return all(t in low for t in toks)
