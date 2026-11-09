"""광고 랜딩페이지 위험도 판정 서버

앱이 광고 클릭으로 도착한 주소를 보내면, 서버가 그 페이지를 대신 열어 읽고
LLM에 물어 {위험도, 이유, 권장행동} 반환

앱이 아니라 서버가 페이지를 여는 이유:
 - 악성 페이지를 사용자 폰(사용자 IP)이 직접 요청하는 일 방지
 - 리다이렉트 체인을 따라 최종 목적지 읽기 — 앱이 보내는 주소는
   매체의 클릭 집계 링크(cyad1.nate.com/click.kti/...)인 경우가 많음
 - API 키는 서버 환경변수에만. 앱(APK)에는 키가 한 글자도 없음

판정 기준은 코드가 아니라 CRITERIA(프롬프트)에. 별도 규칙 엔진 없음 —
정상 쇼핑몰도 카드번호를 받고 정상 이벤트도 개인정보를 받아 신호는 같고 맥락만 다름.
맥락 판단은 LLM 담당, 서버가 강제하는 것은 한 가지:
**근거를 대지 못하면 고위험 불가** (아래 verify 참고)
"""

import html
import logging
import os
import re
import sqlite3
import threading
import time
from datetime import datetime, timezone, timedelta
from urllib.parse import urljoin

import anthropic
import requests
from bs4 import BeautifulSoup
from fastapi import FastAPI
from pydantic import BaseModel

import judge_agent

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("judge")

app = FastAPI()

# ── 설정 ─────────────────────────────────────────────────────────────────────

# 페이지 가져오기 제한. 앱 쉴드 12초 타임아웃 — 전체가 그 안에 종료 필요
# 지연의 주범은 광고 랜딩페이지 서버(사기성일수록 싸구려 서버라 느림) —
# 3.5초 초과 시 페이지 없이 주소만으로 판정하는 쪽이 유리
FETCH_TIMEOUT_S = 3.5
# LLM에 넘길 본문 길이. 판정 신호(APK 유도·개인정보 폼·사칭 문구)는 대개 상단에 집중 —
# 통째로 넘기면 토큰만 증가, 판정 개선 없음
MAX_PAGE_CHARS = 4000

# 판정 완료 사이트 재질의 없음. 광고 랜딩 주소는 클릭마다 gclid가 새로 붙어
# 문자열이 매번 다르므로 열쇠는 주소가 아니라 **등록 도메인**
# 서버 재시작에 사라지는 메모리 캐시. 오래 남기는 DB는 추후 추가
_cache: dict[str, dict] = {}

# ANTHROPIC_API_KEY 환경변수 사용. SDK 재시도 0 — 기본 2회에 긴 시한이 겹치면 13초까지 늘어남(실측 2026-08-23).
# 재시도는 judge_agent가 하드 리밋까지 남은 시간을 보고 한 번만(LLM_RETRY_MIN_LEFT_S)
client = anthropic.Anthropic(max_retries=0)   # 재시도는 judge_agent.run_agent가 남은 시간 보고 직접

KST = timezone(timedelta(hours=9))

# ── 악성 주소 목록 ────────────────────────────────────────────────────────────
#
# LLM에 묻기 **전에** 이미 아는 악성 도메인 필터. 적중 시 페이지 열기·Claude 호출 없음 —
# 빠르고 무비용
#
# 목록 선택 주의: "광고 차단" 목록 그대로 사용 금지 — 광고 띄우는 도메인 전부가 담겨
# 정상 광고까지 빨강 → "광고는 광고일 뿐, 위험한 건 따로 있다"는 이 앱의 주장 붕괴
# 아래 셋은 그 점을 확인하고 고른 것(smed79/blacklist에는 doubleclick·criteo 같은
# 주요 광고망 없음. 수상한 잡 도메인만 수록)
#
# 라이선스: smed79/blacklist는 CC BY-NC-SA 3.0(**비상업적**). 시연·연구 가능,
# 유료 배포·광고 붙인 배포 불가. 상용화 시 이 목록부터 제외
BLOCKLISTS = [
    # 툴루즈 대학 분류 목록 — 카테고리 구분이 있어 위험한 것만 선택 수신
    ("ut1-phishing", "https://raw.githubusercontent.com/smed79/ut1/master/phishing/domains"),
    ("ut1-malware", "https://raw.githubusercontent.com/smed79/ut1/master/malware/domains"),
    # smed79/blacklist **제외.** 실기기에서 ader.naver.com이 빨강 → 목록 확인 결과
    # 정상 한국 서비스의 통계·광고 호스트 포함:
    #   ad.daum.net · stat.tiara.daum.net · lcs.naver.com · nlog.naver.com
    #   kyson.kakao.com · videostats.kakao.com · logs-partners.coupang.com
    # doubleclick 같은 유명 도메인 부재만 보고 안심한 것이 오판 — 그 목록의 목적은
    # "광고·추적 차단"이지 "악성 차단"이 아님. 광고 차단기 기준으로는 전부 차단 대상,
    # 우리 기준으로는 정상. 오탐 하나가 미탐 열보다 나쁨
]

# 목록 재수신 간격. 피싱 도메인은 며칠 만에 바뀌므로 하루 한 번 수신 필요
BLOCKLIST_REFRESH_S = 24 * 60 * 60

# 목록은 **메모리 미적재.** 41만 개를 파이썬 set으로 들면 31MB 상주 필요 —
# SQLite PRIMARY KEY 색인으로 파일에서 바로 찾아도 한 번에 0.14ms(실측).
# 판정 한 건 6초 남짓이라 무시 가능한 시간, 대신 메모리·적재 단계 전부 제거
# 목록 교체 시 재적재 불필요
_refreshing = False

# 판정 기록 DB. 애저 App Service의 /home은 재시작·재배포에도 유지되는 영구 저장소 —
# SQLite 파일을 거기 두면 별도 DB 서비스 없이 기록 보존. 로컬에서는 작업 폴더에 생성
# 모든 요청 기록(캐시 히트 포함) — /recent의 읽기 원본, 재시작 후 캐시 복원에도 사용
DB_PATH = os.environ.get(
    "DB_PATH", "/home/judgments.db" if os.path.isdir("/home") else "judgments.db"
)

# 차단 목록 DB — 판정 기록과 **다른 파일**. 2026-08-24 손상 사고: 배포 재시작이
# 26만 행 쓰기를 끊어 judgments.db 전체가 malformed, 판정 기록까지 인질
# 분리하면 목록 파일은 깨져도 버리고 다시 받으면 끝 — 판정 기록은 무사
# 옛 위치(judgments.db 안)의 blocklist 테이블은 잔존·미사용 — 26만 행 DROP이
# 또 다른 잠금을 만들므로 수동 정리 대상
BL_PATH = os.environ.get(
    "BL_PATH", "/home/blocklist.db" if os.path.isdir("/home") else "blocklist.db"
)

_bl_ready = False


def _bldb() -> sqlite3.Connection:
    global _bl_ready
    conn = sqlite3.connect(BL_PATH)
    if not _bl_ready:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS blocklist("
            " domain TEXT PRIMARY KEY, source TEXT, at TEXT)"
        )
        _bl_ready = True
    return conn


_schema_ready = False


def _db() -> sqlite3.Connection:
    """연결. 스키마 확인(CREATE·PRAGMA·ALTER)은 프로세스당 한 번 — 예전엔 연결마다 실행.
    Azure의 /home은 네트워크 디스크라 불필요한 왕복이 곧 지연"""
    global _schema_ready
    conn = sqlite3.connect(DB_PATH)
    if _schema_ready:
        return conn
    conn.execute(
        "CREATE TABLE IF NOT EXISTS judgments("
        " id INTEGER PRIMARY KEY AUTOINCREMENT,"
        " at TEXT, url TEXT, site TEXT, risk TEXT,"
        " reason TEXT, advice TEXT, evidence TEXT,"
        " cached INTEGER, took_s REAL)"
    )
    # ad_key: 클릭 주소에서 뽑은 광고 식별키. 기존 DB에 열이 없으면 추가
    cols = [r[1] for r in conn.execute("PRAGMA table_info(judgments)")]
    if "ad_key" not in cols:
        conn.execute("ALTER TABLE judgments ADD COLUMN ad_key TEXT")
    # type: 위험 **유형**(사칭·개인정보 요구…). 등급만으로는 원인 파악 불가 —
    # 보호자 화면 제작 중 뒤늦게 추가
    if "type" not in cols:
        conn.execute("ALTER TABLE judgments ADD COLUMN type TEXT")
    # trace: 에이전트의 도구 사용 순서 (JSON). /recent에 표시 —
    # "LLM/DB/룰의 연결"을 그림이 아니라 실제 기록으로 보여주는 용도
    if "trace" not in cols:
        conn.execute("ALTER TABLE judgments ADD COLUMN trace TEXT")
    _schema_ready = True
    return conn


def _parse_list(text: str) -> set[str]:
    """목록 파일에서 도메인만 추출. hosts 형식(0.0.0.0 도메인)과 도메인 한 줄 모두 지원"""
    out = set()
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        d = parts[-1] if len(parts) > 1 else parts[0]
        d = d.lower().strip(".")
        # hosts 파일의 자기 자신 항목과 IP 제외
        if d in ("localhost", "localhost.localdomain", "broadcasthost", "0.0.0.0", "::1"):
            continue
        if "." in d and not d.replace(".", "").isdigit():
            out.add(d)
    return out


def blocklist_age_s() -> float | None:
    """목록 마지막 수신 후 경과 시간. 수신 이력 없으면 None."""
    try:
        with _bldb() as db:
            row = db.execute("SELECT at FROM blocklist LIMIT 1").fetchone()
    except sqlite3.Error:
        return None
    if not row or not row[0]:
        return None
    try:
        return (datetime.now(KST) - datetime.fromisoformat(row[0])).total_seconds()
    except ValueError:
        return None


def refresh_blocklist() -> None:
    """오래됐으면 **백그라운드** 재수신

    소요 10초 이상(내려받기 5초 + 41만 줄 삽입 7초). 요청 중 실행 시 그 사용자의
    판정이 그만큼 지연, 앱 쉴드는 12초에 타임아웃. 따라서 스레드로 넘기고 즉시 반환 —
    이번 요청은 옛 목록으로 판단
    """
    global _refreshing
    if _refreshing:
        return
    age = blocklist_age_s()
    # 시간이 안 됐어도 **쓰는 목록이 바뀌었으면** 즉시 재수신
    # 목록 하나를 빼도 DB에 남아 있으면 하루 동안 그대로 적중 —
    # smed79 제외 뒤에도 네이버가 계속 빨강이던 원인
    if age is not None and age < BLOCKLIST_REFRESH_S and _sources_match():
        return
    _refreshing = True
    threading.Thread(target=_do_refresh, daemon=True).start()


_sources_have: set | None = None   # DB에 든 목록 이름 — 프로세스당 한 번 읽고 갱신 때 교체


def _sources_match() -> bool:
    """DB에 든 목록과 현재 사용 목록의 일치 여부

    예전엔 요청마다 `SELECT DISTINCT source` — 26만 행 전체 스캔, 네트워크 디스크(/home)라 초 단위
    (실측 2026-08-24: 판정 경로 앞단에 안 보이는 ~3초). 메모리에 들고 갱신 완료 때만 교체
    """
    global _sources_have
    if _sources_have is None:
        try:
            with _bldb() as db:
                _sources_have = {r[0] for r in db.execute("SELECT DISTINCT source FROM blocklist")}
        except sqlite3.Error:
            return True   # 목록 잠김·손상 — 이번 판은 갱신 보류, 다음 요청에 재시도
    return _sources_have == {name for name, _ in BLOCKLISTS}


def _do_refresh() -> None:
    global _refreshing, _bl_ready
    try:
        _fetch_blocklist()
    except sqlite3.DatabaseError as e:
        # malformed = 목록 파일 손상. 판정 기록과 분리된 파일이라 버리고 새로 받으면 끝(1회 재시도)
        # 잠김(locked)은 손상이 아님 — 파일 삭제 금지, 다음 주기에 재시도
        if "malformed" in str(e):
            log.warning(f"blocklist DB 손상 — 파일 재생성 후 재수신: {e}")
            try:
                os.remove(BL_PATH)
            except OSError:
                pass
            _bl_ready = False
            try:
                _fetch_blocklist()
            except Exception as e2:
                log.warning(f"blocklist 재수신 실패: {e2}")
        else:
            log.warning(f"blocklist 갱신 실패: {e}")
    except Exception as e:
        log.warning(f"blocklist 갱신 실패: {e}")
    finally:
        _refreshing = False


def _fetch_blocklist() -> int:
    fetched: list[tuple[str, str]] = []
    for name, url in BLOCKLISTS:
        try:
            r = requests.get(url, timeout=30)
            r.raise_for_status()
            got = _parse_list(r.text)
            fetched += [(d, name) for d in got]
            log.info(f"blocklist {name}: {len(got)}개")
        except Exception as e:
            # 한 곳 실패해도 나머지 사용. 목록이 통째로 비면 차단 불가
            log.warning(f"blocklist {name} 실패: {e}")

    if not fetched:
        # 전부 실패 시 기존 목록 유지. 비우면 차단 불가
        log.warning("blocklist를 하나도 받지 못했다 — 있던 목록을 그대로 쓴다")
        return blocklist_count()

    # 통째로 교체. 누적 없음 → 크기 증가 없음 —
    # 지운 자리는 SQLite가 재사용하므로 파일도 비대화 없음
    now = datetime.now(KST).isoformat(timespec="seconds")
    with _bldb() as db:
        db.execute("DELETE FROM blocklist")
        db.executemany(
            "INSERT OR IGNORE INTO blocklist(domain, source, at) VALUES(?,?,?)",
            [(d, src, now) for d, src in fetched],
        )
    global _sources_have
    _sources_have = {src for _, src in fetched}
    n = blocklist_count()
    log.info(f"blocklist 갱신 완료 {n}개")
    return n


def blocklist_count() -> int:
    try:
        with _bldb() as db:
            return db.execute("SELECT COUNT(*) FROM blocklist").fetchone()[0]
    except sqlite3.Error:
        return -1   # 목록 잠김·손상 — 서버는 계속 동작, 빠른 길만 비활성


def blocked_by(url: str) -> str | None:
    """이 주소의 목록 등재 여부. 등재 시 목록 이름 반환

    하위 도메인도 필터 대상 — 목록에 `bad.com`만 있어도 `login.bad.com`은 같은 곳.
    호스트를 점 단위로 잘라 올라가며 조회
    """
    m = re.match(r"https?://([^/?#]+)", url.lower())
    if not m:
        return None
    host = m.group(1).split(":")[0].strip(".")
    parts = host.split(".")
    cands = [".".join(parts[i:]) for i in range(len(parts) - 1)]
    if not cands:
        return None
    try:
        with _bldb() as db:
            row = db.execute(
                f"SELECT source FROM blocklist WHERE domain IN ({','.join('?' * len(cands))})"
                " LIMIT 1",
                cands,
            ).fetchone()
    except sqlite3.Error:
        return None   # 목록 잠김·손상 — 빠른 길만 포기, 판정은 LLM이 계속
    return row[0] if row else None


def blocked_many(urls: list[str]) -> set[str]:
    """여러 주소를 **한 번의 조회로** 판별. 화면 광고 스무 개에 스무 번 질의할 이유 없음"""
    cands: dict[str, list[str]] = {}
    for u in urls:
        m = re.match(r"https?://([^/?#]+)", u.lower())
        if not m:
            continue
        parts = m.group(1).split(":")[0].strip(".").split(".")
        cands[u] = [".".join(parts[i:]) for i in range(len(parts) - 1)]
    every = sorted({d for ds in cands.values() for d in ds})
    if not every:
        return set()
    try:
        with _bldb() as db:
            found = {
                r[0]
                for r in db.execute(
                    f"SELECT domain FROM blocklist WHERE domain IN ({','.join('?' * len(every))})",
                    every,
                )
            }
    except sqlite3.Error:
        return set()   # 목록 잠김·손상 — 사전 표시만 보류
    return {u for u, ds in cands.items() if any(d in found for d in ds)}


# 클릭마다 새로 발급되는 파라미터 — 광고의 정체성이 아니라 그 클릭의 일련번호
# 식별키에서 제외해야 같은 광고의 두 클릭이 같은 키
_VOLATILE_PARAMS = re.compile(
    r"^(gclid|gad_source|gad_campaignid|wbraid|gbraid|msclkid|fbclid"
    r"|utm_[a-z]+|airbridge_referrer|referrer_id|event_uuid|client_id"
    r"|ts|t|rnd|cb|r|nonce"
    # img_no는 이름과 달리 소재 번호가 아니라 **노출마다 올라가는 일련번호**
    # (실측: 411807 → 411808 → 425159). 키에 포함 시 새로고침마다 키 변경 →
    # ✓가 같은 페이지 안에서만 유지, 다시 열면 해제
    r"|img_no|imp_no|view_no|seq|seq_no"
    r"|ai|ved|usg|sig"     # 구글 aclk의 노출 단위 값들
    # dable click_redirect의 ?q= — 노출마다 새로 생성되는 거대한 blob
    # 정작 광고의 정체(campaigns/<id>/contents/<id>)는 **경로**에 있어 이것을 빼야
    # 같은 소재의 두 클릭이 같은 키 (실측 2026-08-16)
    r"|q)$"
)


def _ad_key(url: str) -> str | None:
    """클릭 주소에서 광고 식별키 생성

    트래커 주소(cyad1.nate.com/click.kti/...?ads_no=243187&cmp_no=31179)는 목적지 없이
    **광고 번호만 포함.** 진짜 클릭 한 번에 판정이 붙으면 이후 같은 번호의 광고는 클릭
    없이 식별 가능 — 트래커 선요청(가짜 클릭 생성)과 달리 이미 일어난 클릭 기록의 재사용일 뿐

    키 = 호스트+경로+**클릭 표식을 뺀** 파라미터(정렬). 소재 번호(img_no)까지 포함되어
    좁아서 놓칠 수는 있어도(소재 변경 시 미지로 복귀) 다른 광고에 남의 판정이 붙는 일 없음.
    경로만 쓰면 광고 '슬롯' 단위가 되어 다음 광고가 판정 상속 — 금지
    """
    m = re.match(r"https?://([^/?#]+)([^?#]*)\??([^#]*)", url.lower())
    if not m:
        return None
    host, path, query = m.groups()
    # 매체 자체 트래커에는 식별키 미사용. 실측(네이트 cyad): ads_no가 광고 하나가 아니라
    # **광고 구좌**로 추정 — 같은 번호 자리에 뉴트리원·쿠팡이 번갈아 노출
    # 구좌에 색을 붙이면 그 자리의 모든 광고주가 색 상속. 확실해질 때까지
    # 이런 트래커는 "모름(파랑)" 유지. 틀린 초록보다 파랑이 나음
    if "cyad" in host or "/click." in path:
        return None
    # 소재 이미지 주소 = 그 자체가 광고 식별자. 매체 트래커(cyad류)는 클릭 주소에
    # 광고주 정보가 없어 앱이 click_url로 **소재 이미지**를 대신 전송. 실측
    # (2026-08-16, 네이트 기사 구좌 10회): 같은 소재는 노출이 바뀌어도 이미지 주소
    # 동일(cp_c_0116_640x160.jpg 4회 일치), 파일명에 광고주 그대로 포함
    # (cp=쿠팡, tmap=티맵). 경로가 슬롯이 아니라 소재를 가리키므로 파라미터 없이도 키
    if re.search(r"\.(png|jpe?g|gif|webp)$", path):
        return f"{host}{path}"
    params = sorted(
        p for p in query.split("&")
        if p and "=" in p and not _VOLATILE_PARAMS.match(p.split("=")[0])
    )
    if not params:
        return None  # 파라미터 없으면 경로 = 슬롯 → 식별키 불가
    return f"{host}{path}?{'&'.join(params)}"

# 위험 판정은 문맥 읽기가 필요해 제일 싼 모델이 최선이 아닐 수 있음
# 리허설에서 판정이 갈리면 그때 상향. 환경변수로 변경 가능
MODEL = os.environ.get("JUDGE_MODEL", "claude-haiku-4-5")

# 판정 에이전트(judge_agent.py) — Claude가 도구를 골라 쓰며 판정. 끄면 예전 1회 판정으로 동작
# 예전 길은 비교·비상용으로 보존 (JUDGE_AGENT=0)
USE_AGENT = os.environ.get("JUDGE_AGENT", "1") != "0"
# 왕복 1회 고정 — 도구 루프 없이 미리 모은 것만으로 판정(judge_agent.ONESHOT_RULES). 기본 켬.
# eval 42건(2026-08-24, 캐시 없이): 에이전트 32/42·중앙값 4.4s·p90 7.7s ↔ 왕복 1회 33/42·3.8s·5.7s.
# 에이전트 모드는 JUDGE_ONESHOT=0 — 비교·비상용
USE_ONESHOT = os.environ.get("JUDGE_ONESHOT", "1") != "0"
# Claude 호출 스트리밍(저위험 선행 통보). 실측(2026-08-23 Azure): 스트리밍 호출이 간헐적으로 헤더도 못 받고
# 타임아웃 → 판정 UNKNOWN. 원인(아웃바운드 연결) 확인 전까지 기본 끔 — 응답 형식(NDJSON)은 그대로,
# early 줄만 안 나감. 켜면 실패 시 일반 호출로 자동 대체(judge_agent._ask)
USE_LLM_STREAM = os.environ.get("JUDGE_LLM_STREAM", "0") != "0"

# ── 판정 기준 — 팀이 정한 것. 여기가 이 서버의 본체다 ─────────────────────────

CRITERIA = """\
너는 어르신을 광고 사기에서 보호하는 앱의 판정원이다. 사용자가 광고를 눌러 도착한
페이지가 위험한지 판정한다.

## 위험도 기준

**고위험** — 확실할 때만. 사용자에게서 선택권을 빼앗는 판정이므로 애매하면 절대 넣지 않는다.
- 다른 회사·기관을 사칭한다 (은행·택배·공공기관·유명 쇼핑몰을 자처하는데 도메인이 그곳 것이 아님)
- 비밀번호·주민등록번호·카드번호 입력을 요구한다 — 정체불명·사칭 맥락일 때다.
  공식 도메인으로 확인된 사이트의 정상 로그인·본인인증·결제는 해당 없음.
- 공식 앱스토어가 아닌 곳에서 APK 파일을 직접 내려받게 한다

**중위험** — 사용자가 직접 고르게 한다.
- 이름·전화번호 등 개인정보 입력 폼이 있다 (이벤트 응모, 상담 신청, 보험 상담).
  **공식 브랜드의 페이지여도 마찬가지다** — 이 등급의 뜻은 "사기"가 아니라
  "개인정보를 넣기 전에 한 번 더 확인하세요"다. 넣으면 영업 전화가 온다.
  공식 사이트에 이 등급을 줄 때 reason은 "위험하다"가 아니라 "공식 사이트지만
  연락처를 넣으면 연락이 올 수 있다"는 뜻으로 쓴다.

  **예외: 공식 사이트의 로그인·회원가입 폼은 저위험이다.** `official_domain_of`가
  도착 도메인을 공식으로 확인해 줬고 폼이 아이디·비밀번호를 만들거나 넣는
  맥락이면, 사칭이 아님이 확인된 것이고 계정은 사용자가 의도한 행동이다.
  회원가입 절차가 이름·전화번호를 받아도 마찬가지다. 단 같은 공식 사이트여도
  **전화번호 수집이 목적인 폼**(상담 신청·이벤트 응모·견적)은 그대로 중위험이다.
  이 예외는 `official_domain_of` 결과에 known=true(공식 도메인 표 등재)가 **실제로
  있을 때만** 쓴다. 표에서 확인되지 않은 사이트의 로그인 폼은 페이지가 아무리
  평범해 보여도 중위험(`personal_info`)이다 — 광고가 로그인 페이지로 곧장
  데려가는 것 자체가 이상 신호이고, 비밀번호는 잘못 넣으면 되돌릴 수 없다.
- 결제·가입을 유도하는데 사업자 정보가 없다
- "당첨" "무료" "한정" "경품" 같은 문구로 급하게 행동을 유도한다
- 투자·고수익·재테크·부업을 권유한다 (교육·정보 형식을 하고 있어도)
- 콘텐츠팜으로 보인다 — 출처 불명의 글에 광고가 가득하고, 가짜 검색창이나
  미끼 링크로 체류·클릭을 유도하는 페이지
- **페이지를 확인할 수 없다** — 존재하지 않는 페이지, 내용이 없는 페이지,
  리디렉션 껍데기만 있는 페이지. 확인하지 못했으면서 "저위험"이라고 말하면
  안 된다. reason에 확인하지 못했다고 정직하게 쓴다.

  **예외가 하나 있다.** `official_domain_of`가 도착한 도메인을 어느 회사의 **공식
  도메인으로 확인해 준 경우**에는, 본문을 못 읽었어도 저위험이다. 공식 도메인은
  사칭할 수 없는 근거이고(사칭이라면 다른 도메인이어야 한다), 큰 회사일수록
  본문을 자바스크립트로 그려서 글자를 읽을 수 없다 — 그때마다 주의를 띄우면
  **제대로 만든 사이트일수록 경고가 뜨는** 꼴이 되고, 어르신은 경고에 무뎌진다.
  다만 도메인 등록일(`domain_age`)만으로는 저위험을 주지 마라 — 오래된 도메인도
  사고팔린다. 공식 도메인 표에서 확인된 경우에만이다.

**저위험** — 내용을 실제로 확인했고 위에 해당하지 않는 것.
평범한 쇼핑몰·기사·회사 소개는 저위험이다.

## 유형 (type)
등급과 별개로, **무엇 때문에 그 등급인지**를 아래에서 하나 고른다.
보호자 화면이 "사칭이었는지 개인정보 요구였는지"를 한눈에 보여주는 데 쓴다.

- `impersonation` — 다른 회사·기관 사칭
- `credentials` — 비밀번호·주민번호·카드번호 요구
- `apk` — 앱스토어 밖에서 앱 파일을 내려받게 함
- `personal_info` — 이름·전화번호 등 개인정보 입력 폼
- `payment` — 사업자 정보 없는 결제·가입 유도
- `urgency` — 당첨·무료·한정 같은 급한 행동 유도
- `investment` — 투자·고수익·재테크·부업 권유
- `contentfarm` — 콘텐츠팜, 미끼 링크
- `unverifiable` — 페이지를 확인할 수 없음
- `none` — 저위험. 해당 없음

## 규칙
- reason과 advice는 어르신이 읽는다. 한 문장씩, 쉬운 말로.
- evidence에는 페이지 원문에서 **그대로 옮긴** 판단 근거 문장을 넣는다.
  고위험인데 근거를 옮길 수 없으면 그것은 고위험이 아니다.
- 페이지를 가져오지 못해 주소만 보고 판정할 때는 고위험을 내리지 않는다.
- "공식 페이지 같다"는 저위험의 근거가 되지 않는다. 개인정보 폼·유도 문구가
  있으면 공식이어도 해당 등급을 준다.
- 해당하는 유형이 여럿이면 **가장 위험한 것 하나**만 고른다 (위 목록의 순서가 곧 우선순위다).
- **risk가 LOW가 아니면 type은 절대 `none`이 될 수 없다.** 중위험·고위험을 준 이유가
  곧 유형이다. reason에 "개인정보를 입력해야 한다"라고 썼으면 type은 `personal_info`다.
  `none`은 오직 저위험에서만 쓴다.
"""

# 유형 목록은 CRITERIA와 **동일 필수.** 스키마로 강제 → 모델의 임의 이름 생성 불가,
# 보호자 화면도 알 수 없는 값과 조우 없음
AD_TYPES = [
    "impersonation", "credentials", "apk", "personal_info", "payment",
    "urgency", "investment", "contentfarm", "unverifiable", "none",
]

SCHEMA = {
    "type": "object",
    "properties": {
        "risk": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
        "type": {"type": "string", "enum": AD_TYPES},
        "reason": {"type": "string"},
        "advice": {"type": "string"},
        "evidence": {"type": "string"},
    },
    "required": ["risk", "type", "reason", "advice", "evidence"],
    "additionalProperties": False,
}

# 화면에 그대로 띄울 우리말 이름
TYPE_LABEL = {
    "impersonation": "사칭",
    "credentials": "비밀번호·주민번호 요구",
    "apk": "앱 파일 내려받기",
    "personal_info": "개인정보 요구",
    "payment": "결제 유도",
    "urgency": "당첨·무료 유도",
    "investment": "투자 권유",
    "contentfarm": "콘텐츠팜",
    "unverifiable": "확인 불가",
    "blocklist": "악성 목록 등재",
    "none": "",
}

# ── 요청/응답 ─────────────────────────────────────────────────────────────────


class JudgeIn(BaseModel):
    url: str
    # 누른 광고의 href (트래커 주소). url은 리다이렉트가 끝난 도착지라 광고 식별번호
    # 없음 — 여기서 식별키를 뽑아 판정과 함께 저장해야 이후 같은 광고 식별 가능
    click_url: str | None = None
    # 캐시(광고 식별키·광고주 사이트) 건너뛰고 새로 판정. eval.py --fresh 전용 — 소요 시간 측정은
    # 캐시 적중이면 무의미. 차단 목록은 그대로 적용(그것도 판정의 일부)
    fresh: bool = False
    # NDJSON 스트리밍 응답. 한 줄씩: 저위험이 확정되는 즉시 {"early":true,"risk":"LOW","site":…} →
    # 마지막 줄 JudgeOut. 앱은 첫 줄에서 쉴드를 걷고 둘째 줄로 기록·캐시. 빠른 길·실패는 마지막 줄만
    stream: bool = False


class PeekIn(BaseModel):
    urls: list[str]


class JudgeOut(BaseModel):
    risk: str      # LOW | MEDIUM | HIGH | UNKNOWN
    # 이 등급의 원인 (AD_TYPES 중 하나). 보호자 화면에 유형으로 표시
    type: str = "none"
    reason: str
    advice: str
    # 판정이 붙은 사이트 = 최종 도착지의 등록 도메인. 앱이 사전 표시(테두리 색)를
    # 이 열쇠로 기억해야 같은 광고주의 다른 광고 식별 가능
    site: str | None = None
    # 서버가 실제로 도착한 등록 도메인 — 공용 호스팅(imweb.me·cafe24.com)이어도 채움. 앱이 "클릭 즉시
    # 요청"의 답과 폰의 도착지를 대조하는 데만 씀(site는 공용이면 비어 대조 불가 → 재요청·이중 비용)
    at: str | None = None


@app.on_event("startup")
def _on_start() -> None:
    """서버 기동 시 목록부터 준비. 백그라운드 실행이라 첫 요청 차단 없음"""
    # 목록 히트를 site와 함께 저장하던 때(2026-08-18)의 기록 복구. 방치 시
    # 그 등록 도메인이 계속 캐시로 읽혀 회사 전체가 위험으로 물든 채 잔존
    with _db() as db:
        n = db.execute(
            "UPDATE judgments SET site=NULL, ad_key=NULL"
            " WHERE type='blocklist' AND (site IS NOT NULL OR ad_key IS NOT NULL)"
        ).rowcount
    if n:
        log.info(f"목록 히트 {n}건의 site를 지웠다(캐시 오염 정리)")
    # 장터 도메인(naver.com·coupang.com…)을 열쇠로 남긴 기록도 해제
    # 블로그 글 하나의 판정이 그 회사 전체로 번진 상태였음
    with _db() as db:
        marks = ",".join("?" * len(SHARED_SITES))
        m = db.execute(
            f"UPDATE judgments SET site=NULL WHERE site IN ({marks})",
            tuple(SHARED_SITES),
        ).rowcount
    if m:
        log.info(f"장터 도메인 {m}건의 site를 지웠다(캐시 오염 정리)")
    # 공식 도메인 개인정보 폼의 중위험 기록 열쇠 해제 — 기준 변경(공식 로그인·회원가입
    # 저위험, 2026-08-24) 전 판정의 재판정 유도. 상담 폼은 재판정 후 다시 중위험
    official = sorted({d for _, doms in judge_agent.BRANDS for d in doms})
    with _db() as db:
        omarks = ",".join("?" * len(official))
        k = db.execute(
            f"UPDATE judgments SET site=NULL, ad_key=NULL"
            f" WHERE risk='MEDIUM' AND type='personal_info' AND site IN ({omarks})",
            tuple(official),
        ).rowcount
    if k:
        log.info(f"공식 도메인 개인정보 폼 {k}건의 캐시 열쇠를 지웠다(기준 변경 재판정)")
    _cache.clear()
    refresh_blocklist()


@app.get("/health")
def health():
    """살아있는지 + 잠든 서버를 깨우는 용도. 발표 전에 이 주소를 한 번 열 것."""
    return {
        "ok": True,
        "model": MODEL,
        "mode": "oneshot" if USE_ONESHOT else ("agent" if USE_AGENT else "single"),
        "llm_stream": USE_LLM_STREAM,
        "push": _sa_path() is not None,
        "blocklist": blocklist_count(),
    }


# ── 보호자 알림(FCM) ──────────────────────────────────────────────────────────
#
# 푸시는 폰→폰 직접 전송 불가. 신뢰된 서버만 FCM 요청 가능, 그 자격 증명이
# 서비스 계정 키. 키를 앱에 넣으면 앱을 뜯어 아무에게나 알림 발송 가능 →
# 키는 **서버에만** 보관
#
# 다만 서버는 **수신자를 모름.** 알아내려면 파이어베이스 읽기 필요 →
# 서버가 모든 가족의 프로필·기록 읽기 자격을 갖게 되는 문제
# 그래서 반대 구조 — **보낼 주소는 앱이 실어 전송.** 어르신 폰은 어차피
# 자기 가족 문서 읽기 가능, 서버는 "이 주소로 이 문구"만 전달
# 서버에 잔존 데이터 없음, 서비스 계정 권한도 발송 하나로 축소 가능


class NotifyIn(BaseModel):
    token: str          # 받는 폰의 FCM 등록 토큰 (앱이 파이어베이스에서 읽어 전송)
    tag: str = "guardian"   # 알림 줄의 이름표. 같으면 새 줄이 아니라 그 줄이 갱신된다
    title: str
    line1: str = ""     # 누구·무슨 유형·어디
    line2: str = ""     # 판정 이유 그대로
    eventId: str = ""   # 이 알림이 가리키는 기록. 비면 보호자 폰이 목록을 연다
    body: str = ""      # 옛 앱 호환. line1이 비었을 때만 쓴다


def _sa_path() -> str | None:
    p = os.environ.get("FIREBASE_SA_PATH", "/home/firebase-sa.json")
    return p if os.path.exists(p) else None


_fcm_creds = None


def _fcm_token() -> tuple[str, str] | None:
    """FCM 발송용 액세스 토큰과 프로젝트 id. 키가 없으면 None(알림 기능만 비활성)"""
    global _fcm_creds
    path = _sa_path()
    if not path:
        return None
    try:
        import json as _json

        from google.auth.transport.requests import Request
        from google.oauth2 import service_account

        if _fcm_creds is None:
            _fcm_creds = service_account.Credentials.from_service_account_file(
                path, scopes=["https://www.googleapis.com/auth/firebase.messaging"]
            )
        if not _fcm_creds.valid:
            _fcm_creds.refresh(Request())
        project = _json.load(open(path, encoding="utf-8"))["project_id"]
        return _fcm_creds.token, project
    except Exception as e:
        log.warning(f"FCM 자격 준비 실패: {type(e).__name__}: {e}")
        return None


@app.post("/notify")
def notify(body: NotifyIn) -> dict:
    got = _fcm_token()
    if not got:
        return {"sent": False, "why": "서버에 알림 키가 없습니다"}
    access, project = got
    try:
        r = requests.post(
            f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
            headers={"Authorization": f"Bearer {access}"},
            # notification이 아니라 data로 보낸다. notification 페이로드는 앱이
            # 백그라운드일 때 안드로이드가 직접 트레이에 그리고 앱의 수신 코드를
            # 부르지 않는다 — 채널·이름표·이동 경로가 통째로 무시되는데, 하필 그
            # 상황이 알림이 필요한 유일한 상황이다. data만 실으면 앱이 깨어나 직접 그린다
            # (앱 쪽: PushService).
            json={
                "message": {
                    "token": body.token,
                    "data": {
                        "tag": body.tag,
                        "title": body.title,
                        "line1": body.line1 or body.body,
                        "line2": body.line2,
                        "eventId": body.eventId,
                    },
                    "android": {"priority": "high"},
                }
            },
            timeout=6,
        )
        ok = r.status_code == 200
        if not ok:
            log.warning(f"FCM 발송 실패 {r.status_code}: {r.text[:200]}")
        else:
            log.info(f"알림 보냄: {body.title}")
        return {"sent": ok}
    except Exception as e:
        log.warning(f"FCM 발송 예외: {type(e).__name__}: {e}")
        return {"sent": False}


@app.post("/judge")
def judge(body: JudgeIn):
    if not body.stream:
        return _judge(body)

    # 스트리밍 — 판정은 작업 스레드, 이 생성기는 줄이 생길 때마다 흘려보냄
    import json as _json
    import queue
    q: "queue.Queue[dict | None]" = queue.Queue()

    def on_early(risk: str, final_url: str | None) -> None:
        if risk == "LOW":   # 중·고위험은 reason까지 있어야 화면에 쓸 수 있음 — 먼저 보낼 이유 없음
            at = _site_of(final_url) if final_url else None
            q.put({"early": True, "risk": risk, "site": _shareable(at), "at": at})

    def work() -> None:
        try:
            q.put(dict(_judge(body, on_early=on_early)))
        except Exception as e:
            log.warning(f"judge(stream) 실패: {type(e).__name__}: {e}")
            q.put({"risk": "UNKNOWN", "type": "unverifiable", "reason": "안전한지 확인하지 못했습니다.",
                   "advice": "잘 모르겠으면 돌아가세요.", "site": None})
        finally:
            q.put(None)

    threading.Thread(target=work, name="judge-stream", daemon=True).start()

    def lines():
        while True:
            item = q.get()
            if item is None:
                return
            yield _json.dumps(item, ensure_ascii=False) + "\n"

    from fastapi.responses import StreamingResponse
    return StreamingResponse(lines(), media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


def _judge(body: JudgeIn, on_early=None) -> JudgeOut:
    started = time.monotonic()
    site = _site_of(body.url)

    # 빠른 길 0 — 이미 아는 악성 도메인. **페이지 열기·Claude 호출 없음.**
    # 목록 등재만으로 근거 충분 → verify(근거 검증)도 생략
    refresh_blocklist()
    hit = blocked_by(body.url) or (blocked_by(body.click_url) if body.click_url else None)
    if hit:
        v = {
            "risk": "HIGH",
            "reason": "악성 사이트로 신고된 곳이에요.",
            "advice": "들어가지 마시고 돌아가세요.",
            "evidence": f"차단 목록 {hit}",
            "type": "blocklist",
        }
        log.info(f"blocklist hit({hit}) -> HIGH {body.url[:80]}")
        # **site 미저장.** 저장 시 등록 도메인(naver.com) 단위로 캐시에 박혀
        # 하위 호스트 하나 때문에 그 회사 전체가 위험으로 오염 —
        # 실제 /peek에서 naver.com이 위험으로 표시된 사례. 목록 조회는 어차피
        # 매번 먼저 실행되니 캐시에 남길 이유도 없음. 기록은 /recent와 보호자 알림용으로만
        _remember(body.url, None, v, cached=False, took=time.monotonic() - started,
                  ad_key=None)
        return JudgeOut(**v, site=site)

    # 빠른 길 1 — 광고 식별키. 같은 광고의 이전 클릭 판정이 있으면 그대로 사용
    # 트래커 주소는 클릭마다 문자열이 달라도 식별키가 같아 여기서 적중
    key = _ad_key(body.click_url) if body.click_url else _ad_key(body.url)
    if key and not body.fresh:
        with _db() as db:
            row = db.execute(
                "SELECT risk, reason, advice, evidence, site, type FROM judgments"
                " WHERE ad_key=? AND risk!='UNKNOWN' ORDER BY id DESC LIMIT 1",
                (key,),
            ).fetchone()
        if row:
            v = {"risk": row[0], "reason": row[1], "advice": row[2], "evidence": row[3],
                 "type": row[5] or "none"}
            log.info(f"cache hit(ad_key) -> {v['risk']}")
            _remember(body.url, row[4], v, cached=True, took=time.monotonic() - started, ad_key=key)
            return JudgeOut(**v, site=row[4])

    # 빠른 길 2 — 광고주 사이트. adurl(구글이 명시한 목적지)은 식별키 유무와 무관하게 신뢰,
    # **겉 도메인**은 파라미터 없는 직링크일 때만 — 트래커를 매체 도메인으로 조회하면
    # 매체 사이트의 판정을 엉뚱하게 상속
    adv = _shareable(_adurl_site(body.url) or (None if key else _advertiser_site(body.url)))
    if body.fresh:
        adv = None
    if adv and adv not in _cache:
        with _db() as db:
            # **"확인 불가"는 캐시하지 않음** — 판정이 아니라 판정을 못 했다는 기록.
            # 캐시에 남기면 그 사이트는 영영 재판정 없음 — 다음번에는 될 수도 있는데
            # (페이지 복구, 도구·브랜드 표 확장) 캐시가 먼저 답해 기회 상실.
            # 실측(2026-08-23): axa.co.kr이 "확인 불가(중위험)"로 박혀 브랜드 표에 넣어도
            # 그 폰에서는 영원히 주의 표시
            row = db.execute(
                "SELECT risk, reason, advice, evidence, type FROM judgments"
                " WHERE site=? AND cached=0 AND risk!='UNKNOWN'"
                " AND COALESCE(type,'') != 'unverifiable' ORDER BY id DESC LIMIT 1",
                (adv,),
            ).fetchone()
        if row:
            _cache[adv] = {
                "risk": row[0], "reason": row[1], "advice": row[2], "evidence": row[3],
                "type": row[4] or "none",
            }
    if adv and adv in _cache:
        v = _cache[adv]
        log.info(f"cache hit {adv} -> {v['risk']}")
        _remember(body.url, adv, v, cached=True, took=time.monotonic() - started, ad_key=key)
        return JudgeOut(**v, site=adv)

    if USE_AGENT:
        return _judge_with_agent(body, site, key, started, on_early)

    page_text, final_url = _fetch(body.url)

    # 판정의 정체성 = "광고주 사이트가 위험한가". 입력 주소는 매체의 클릭 집계 링크
    # (cyad1.nate.com/click.kti/...)인 경우가 많아 그 도메인으로 저장하면 쿠팡 광고의
    # 판정이 nate.com 밑에 남고 다른 네이트 광고가 그것을 상속
    # 열쇠는 리다이렉트를 따라간 **최종 도착지의 도메인**
    final_site = _shareable(_site_of(final_url) if final_url else None)
    # 페이지 수신 실패 + 주소에 식별번호 있음(트래커) → 사이트 열쇠 없이 저장 —
    # 겉 도메인(매체)을 열쇠로 남기면 그 매체의 모든 광고가 이 판정을 상속
    store_site = _shareable(final_site or (None if _ad_key(body.url) else site))

    # 최종 도착지 기준 캐시 재확인 — 트래커 주소는 매번 달라도
    # 도착지가 같으면 LLM 재호출 불필요
    if final_site and final_site in _cache:
        v = _cache[final_site]
        log.info(f"cache hit(final) {final_site} -> {v['risk']}")
        _remember(body.url, final_site, v, cached=True, took=time.monotonic() - started, ad_key=key)
        return JudgeOut(**v, site=final_site)

    verdict = _ask_llm(body.url, final_url, page_text)
    verdict = _verify(verdict, page_text)

    # 판정 실패(UNKNOWN)는 그때의 사정이지 사이트의 성질이 아니므로 캐시 미저장
    if store_site and verdict["risk"] != "UNKNOWN":
        _cache[store_site] = verdict
    _remember(body.url, store_site, verdict, cached=False, took=time.monotonic() - started, ad_key=key)
    log.info(
        f"judge {body.url[:80]} -> {verdict['risk']} "
        f"({time.monotonic() - started:.1f}s, final={final_url[:60] if final_url else None})"
    )
    return JudgeOut(**verdict, site=store_site)


def _cacheable(v: dict) -> bool:
    """메모리 캐시에 남길 판정인지. 실패(UNKNOWN)와 "확인 불가"는 판정이 아니므로 제외 —
    DB 조회(/judge·/peek)의 COALESCE(type,'') != 'unverifiable' 조건과 같은 기준"""
    return v.get("risk") != "UNKNOWN" and (v.get("type") or "none") != "unverifiable"


def _cache_lookup(site: str) -> dict | None:
    """에이전트의 lookup_cache 도구. 메모리 캐시 → DB 순으로 이전 판정 조회"""
    if site in _cache:
        v = _cache[site]
        return {"risk": v["risk"], "type": v.get("type", "none"), "reason": v["reason"]}
    with _db() as db:
        row = db.execute(
            "SELECT risk, type, reason, at FROM judgments"
            " WHERE site=? AND cached=0 AND risk!='UNKNOWN' ORDER BY id DESC LIMIT 1",
            (site,),
        ).fetchone()
    return {"risk": row[0], "type": row[1] or "none", "reason": row[2], "at": row[3]} if row else None


def _judge_with_agent(body: JudgeIn, site: str | None, key: str | None, started: float,
                      on_early=None) -> JudgeOut:
    """빠른 길(차단 목록·캐시)을 지나온 요청을 에이전트에 위임

    페이지 읽기는 에이전트 담당 — 여기서는 읽지 않음. 예산(벽시계)은 요청
    수신 시각부터 계산 — 빠른 길에 쓴 시간도 폰의 대기 시간
    """
    pre_ms = int((time.monotonic() - started) * 1000)   # 빠른 길(차단 목록·캐시 조회)에 쓴 시간
    run = judge_agent.run_agent(
        client, MODEL, CRITERIA, body.url, body.click_url,
        judge_agent.Deps(db_path=DB_PATH, blocked_by=blocked_by, cache_lookup=_cache_lookup),
        started=started, oneshot=USE_ONESHOT, on_early=on_early if USE_LLM_STREAM else None,
    )
    run.trace.insert(0, {"tool": "fastpath", "args": {}, "ms": pre_ms, "summary": "차단 목록·캐시 조회", "phase": "pre"})
    verdict = _fill_type(run.verdict)
    final_site = _shareable(_site_of(run.final_url) if run.final_url else None)
    store_site = _shareable(final_site or (None if _ad_key(body.url) else site))
    if store_site and _cacheable(verdict):
        _cache[store_site] = verdict
    _remember(body.url, store_site, verdict, cached=False, took=time.monotonic() - started,
              ad_key=key, trace=run.trace)
    log.info(
        f"judge({'oneshot' if USE_ONESHOT else 'agent'}) {body.url[:80]} -> {verdict['risk']} "
        f"({time.monotonic() - started:.1f}s, tools={run.tool_calls}, llm={run.llm_calls})"
    )
    return JudgeOut(risk=verdict["risk"], type=verdict.get("type", "none"),
                    reason=verdict["reason"], advice=verdict["advice"], site=store_site,
                    at=_site_of(run.final_url) if run.final_url else None)


# 광고망 자기 도메인 — 광고주가 아니므로 조회 열쇠 불가
_AD_NETWORK = ("googleadservices.", "doubleclick.", "googlesyndication.", "criteo.")


def _adurl_site(url: str) -> str | None:
    """구글 광고 링크의 adurl= 안에 든 진짜 목적지. **항상 신뢰 가능** —
    겉 도메인 추정이 아니라 광고망이 명시한 도착지라 식별키 유무와 무관하게 조회에 사용"""
    m = re.search(r"[?&]adurl=([^&]+)", url)
    if not m:
        return None
    from urllib.parse import unquote
    return _site_of(unquote(m.group(1)))


def _advertiser_site(url: str) -> str | None:
    """클릭 없이 알 수 있는 광고주 사이트. adurl이 있으면 그것, 아니면 겉 도메인."""
    s = _adurl_site(url)
    if s:
        return s
    low = url.lower()
    host = re.match(r"https?://([^/?#]+)", low)
    if not host or any(n in host.group(1) for n in _AD_NETWORK):
        return None
    # 매체 트래커의 겉 도메인은 광고주가 아님 (_ad_key와 같은 판별) —
    # cyad1.nate.com을 nate.com으로 접으면 광고가 매체 판정을 상속
    if "cyad" in host.group(1) or "/click." in low:
        return None
    return _site_of(url)


@app.post("/peek")
def peek(body: PeekIn) -> dict:
    """화면에 뜬 광고들의 href로 **조회만**. LLM·페이지 열기 없음 — 무비용

    테두리 색 사전 결정 용도(저위험 파랑 / 중위험 노랑 / 고위험 빨강). 화면의 광고마다
    판정을 걸면 비용 폭발 + "판정은 클릭한 것만" 원칙 위배 → 클릭으로 이미
    판정된 기록만 반환. 조회 순서:
     1. 광고 식별키(ad_key) — 트래커 광고도 **누군가 한 번 클릭했으면** 식별
     2. 광고주 사이트 — 직링크·구글(adurl) 광고
    없으면 None. 트래커 선요청 없음 — 그것은 가짜 클릭
    """
    # 목록에 있는 곳은 **클릭 이력 없어도** 미리 빨강
    # 판정 기록에 기대는 다른 경로와 달리 여기는 이미 아는 것
    refresh_blocklist()
    on_list = blocked_many(body.urls[:20])

    out: dict[str, str | None] = {}
    with _db() as db:
        def by_site(site: str | None) -> str | None:
            site = _shareable(site)
            if not site:
                return None
            if site in _cache:
                return _cache[site]["risk"]
            # 확인 불가로는 테두리 색을 미리 칠하지 않음 (/judge와 같은 이유)
            row = db.execute(
                "SELECT risk FROM judgments WHERE site=? AND risk!='UNKNOWN'"
                " AND COALESCE(type,'') != 'unverifiable' ORDER BY id DESC LIMIT 1", (site,),
            ).fetchone()
            return row[0] if row else None

        for u in body.urls[:20]:
            if u in on_list:
                out[u] = "HIGH"
                continue
            key = _ad_key(u)
            row = key and db.execute(
                "SELECT risk FROM judgments WHERE ad_key=? AND risk!='UNKNOWN'"
                " ORDER BY id DESC LIMIT 1", (key,),
            ).fetchone()
            if row:
                out[u] = row[0]
                continue
            # adurl(구글이 명시한 목적지)은 식별키 유무와 무관하게 신뢰
            # 반면 **겉 도메인**은 파라미터 없는 직링크일 때만 — cyad1.nate.com 트래커를
            # nate.com으로 조회하면 네이트 기사 하나가 판정된 순간 **네이트의 모든 광고**가
            # 그 판정을 상속 (실제 전부 초록이 된 사고 발생)
            out[u] = by_site(_adurl_site(u)) if key else by_site(_advertiser_site(u))

    # 클릭 전 판정 — 모르는 광고 중 **목적지가 확실한 것만** 백그라운드 사전 판정
    # 다음 /peek(앱은 20초 뒤 재질의)에 답 존재, 클릭 시 캐시라 대기 없음
    for u in body.urls[:20]:
        if out.get(u) is None:
            _prejudge_enqueue(u)
    return out


# ── 클릭 전 판정 ─────────────────────────────────────────────────────────────
#
# 원칙 유지: **트래커 주소 선열기 금지** — 여는 순간 광고 클릭으로 집계
# 사전 조회 대상은 광고망이 명시한 목적지(구글 adurl)와 직링크뿐. 착지 페이지
# 읽기는 클릭 집계와 무관
#
# 비용 제한 셋: 같은 사이트 한 번(캐시·DB·진행 중 확인), 장터·광고망 제외,
# 하루 상한. 판정 자체는 /judge와 같은 에이전트 경로 — 기록·근거 검증도 동일
PREJUDGE_DAILY_CAP = int(os.environ.get("PREJUDGE_DAILY_CAP", "300"))
PREJUDGE_WORKERS = 2
_prejudge_lock = threading.Lock()
_prejudge_inflight: set[str] = set()
_prejudge_day = ["", 0]          # [날짜, 오늘 건수]
_prejudge_pool: "ThreadPoolExecutor | None" = None


def _prejudge_target(url: str) -> str | None:
    """미리 열어도 되는 진짜 목적지. 트래커·광고망·장터면 None."""
    m = re.search(r"[?&]adurl=([^&]+)", url)
    if m:
        from urllib.parse import unquote
        return unquote(m.group(1))
    low = url.lower()
    host = re.match(r"https?://([^/?#]+)", low)
    if not host:
        return None
    h = host.group(1)
    if any(n in h for n in _AD_NETWORK) or "cyad" in h or "/click" in low or "click_redirect" in low:
        return None
    if re.search(r"\.(png|jpe?g|gif|webp)(\?|$)", low):
        return None                     # 소재 이미지 — 페이지 아님
    site = _site_of(url)
    if not site or site in SHARED_SITES:
        return None
    return url


def _prejudge_enqueue(url: str) -> None:
    global _prejudge_pool
    target = _prejudge_target(url)
    if not target:
        return
    site = _site_of(target)
    if not site:
        return
    with _prejudge_lock:
        today = datetime.now(KST).strftime("%Y-%m-%d")
        if _prejudge_day[0] != today:
            _prejudge_day[0], _prejudge_day[1] = today, 0
        if _prejudge_day[1] >= PREJUDGE_DAILY_CAP or site in _prejudge_inflight or site in _cache:
            return
        with _db() as db:
            seen = db.execute(
                "SELECT 1 FROM judgments WHERE site=? AND cached=0 AND risk!='UNKNOWN' LIMIT 1", (site,)
            ).fetchone()
        if seen:
            return
        _prejudge_inflight.add(site)
        _prejudge_day[1] += 1
        if _prejudge_pool is None:
            from concurrent.futures import ThreadPoolExecutor
            _prejudge_pool = ThreadPoolExecutor(max_workers=PREJUDGE_WORKERS)
        _prejudge_pool.submit(_prejudge_run, target, site)


def _prejudge_run(target: str, site: str) -> None:
    started = time.monotonic()
    try:
        refresh_blocklist()
        hit = blocked_by(target)
        if hit:
            v = {"risk": "HIGH", "reason": "악성 사이트로 신고된 곳이에요.", "advice": "들어가지 마시고 돌아가세요.",
                 "evidence": f"차단 목록 {hit}", "type": "blocklist"}
            _remember(target, None, v, cached=False, took=time.monotonic() - started, ad_key=None,
                      trace=[{"tool": "prejudge", "summary": "차단 목록"}])
            return
        run = judge_agent.run_agent(
            client, MODEL, CRITERIA, target, None,
            judge_agent.Deps(db_path=DB_PATH, blocked_by=blocked_by, cache_lookup=_cache_lookup),
            started=started, oneshot=USE_ONESHOT,
        )
        verdict = _fill_type(run.verdict)
        store_site = _shareable(_site_of(run.final_url) if run.final_url else site)
        if store_site and _cacheable(verdict):
            _cache[store_site] = verdict
        _remember(target, store_site, verdict, cached=False, took=time.monotonic() - started, ad_key=None,
                  trace=[{"tool": "prejudge", "summary": "클릭 전"}] + run.trace)
        log.info(f"prejudge {target[:70]} -> {verdict['risk']} ({time.monotonic() - started:.1f}s)")
    except Exception as e:
        log.warning(f"prejudge 실패 {target[:70]}: {type(e).__name__}: {e}")
    finally:
        with _prejudge_lock:
            _prejudge_inflight.discard(site)


def _remember(url: str, site: str | None, v: dict, cached: bool, took: float,
              ad_key: str | None = None, trace: list | None = None) -> None:
    import json
    with _db() as db:
        db.execute(
            "INSERT INTO judgments(at, url, site, risk, type, reason, advice, evidence, cached, took_s, ad_key, trace)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S"),
                url, site, v["risk"], v.get("type", "none"), v["reason"], v["advice"],
                v.get("evidence", ""), int(cached), round(took, 2),
                # 클릭 주소의 광고 식별키를 판정과 **함께** 저장
                # 이 매핑 덕에 목적지 없는 트래커 광고도 두 번째 만남부터 식별 가능
                ad_key,
                json.dumps(trace, ensure_ascii=False) if trace else None,
            ),
        )


@app.get("/recent")
def recent():
    """판정 내역을 사람이 읽을 표로. 시연 확인용 — 브라우저로 열기"""
    from fastapi.responses import HTMLResponse

    with _db() as db:
        total = db.execute("SELECT COUNT(*) FROM judgments").fetchone()[0]
        items = db.execute(
            "SELECT at, url, risk, reason, cached, took_s, type, trace FROM judgments"
            " ORDER BY id DESC LIMIT 100"
        ).fetchall()

    def path_of(trace: str | None) -> str:
        if not trace:
            return ""
        try:
            import json
            steps = json.loads(trace)
            # ms 포함 — 어느 단계가 느린지 로그 없이 확인 (SQLite·페이지·Claude)
            return " → ".join(f"{t['tool']}({t.get('summary', '')}, {t.get('ms', 0)}ms)" for t in steps)
        except Exception:
            return ""

    color = {"LOW": "#2E7D32", "MEDIUM": "#F5A623", "HIGH": "#D0021B", "UNKNOWN": "#888"}
    label = {"LOW": "저위험", "MEDIUM": "중위험", "HIGH": "고위험", "UNKNOWN": "미확인"}
    rows = "".join(
        f"<tr><td>{at[5:]}</td>"
        f"<td style='color:{color.get(risk, '#888')};font-weight:700'>{label.get(risk, risk)}</td>"
        f"<td>{TYPE_LABEL.get(kind or 'none', kind or '')}</td>"
        f"<td>{html.escape(reason)}</td>"
        f"<td>{'캐시' if cached else f'{took}s'}</td>"
        f"<td class=u>{html.escape(url[:120])}<div class=e>{html.escape(path_of(trace))}</div></td></tr>"
        for at, url, risk, reason, cached, took, kind, trace in items
    ) or "<tr><td colspan=6>아직 판정한 것이 없습니다</td></tr>"
    return HTMLResponse(
        "<meta charset=utf-8><meta name=viewport content='width=device-width'>"
        "<title>광고 알리미 — 판정 내역</title>"
        "<style>body{font-family:sans-serif;margin:16px}table{border-collapse:collapse;width:100%}"
        "td,th{border-bottom:1px solid #ddd;padding:6px 8px;text-align:left;font-size:14px}"
        ".u{color:#888;font-size:12px;word-break:break-all}.e{color:#2a6;font-size:11px;margin-top:2px}</style>"
        f"<h2>판정 내역 (전체 {total}건 중 최근 100건)</h2>"
        f"<table><tr><th>시각</th><th>판정</th><th>유형</th><th>이유</th><th>소요</th><th>주소</th></tr>{rows}</table>"
    )


# ── 페이지 가져오기 ───────────────────────────────────────────────────────────


_MOBILE_UA = (
    # 모바일 광고 랜딩페이지는 모바일 UA가 아니면 다른 내용 제공 가능
    "Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
)

# 자바스크립트·meta 리다이렉트. requests는 HTTP 리다이렉트만 추적하므로
# 이런 페이지는 본문이 통째로 비어 "확인 못 함(중위험)" — 실측: 네이버 블로그
# 광고 착지가 185바이트짜리 `top.location.replace(...)` 한 줄
_JS_REDIRECT = re.compile(
    r"""location\.(?:replace|assign)\(\s*['"]([^'"]+)['"]"""
    r"""|location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""
    r"""|http-equiv=["']refresh["'][^>]*url=([^"'>\s]+)""",
    re.I,
)


def _get(url: str):
    return requests.get(
        url,
        timeout=FETCH_TIMEOUT_S,
        headers={"User-Agent": _MOBILE_UA},
        allow_redirects=True,
    )


def _text_of(html_doc: str) -> str:
    soup = BeautifulSoup(html_doc, "html.parser")
    for tag in soup(["script", "style", "noscript"]):
        tag.decompose()
    return re.sub(r"\s+", " ", soup.get_text(" ")).strip()


def _fetch(url: str) -> tuple[str | None, str | None]:
    """페이지 본문 텍스트와 (리다이렉트를 따라간) 최종 주소. 실패하면 (None, None)."""
    try:
        r = _get(url)
        text = _text_of(r.text)
        # 본문이 사실상 비면 자바스크립트·meta 리다이렉트 한 번 추적
        # 한 번만 — 무한 추적은 지연, 실측상 한 홉이면 충분
        if len(text) < 200:
            m = _JS_REDIRECT.search(r.text)
            if m:
                nxt = next(g for g in m.groups() if g)
                nxt = html.unescape(nxt).replace("\\/", "/")
                nxt = urljoin(str(r.url), nxt)
                log.info(f"스크립트 리다이렉트 따라감 -> {nxt[:90]}")
                r = _get(nxt)
                text = _text_of(r.text)
        return text[:MAX_PAGE_CHARS], str(r.url)
    except Exception as e:
        log.info(f"fetch 실패 {url[:80]}: {type(e).__name__}")
        return None, None


# ── LLM 판정 ─────────────────────────────────────────────────────────────────


def _ask_llm(url: str, final_url: str | None, page_text: str | None) -> dict:
    if page_text:
        user = (
            f"광고를 눌러 도착한 주소: {url}\n"
            f"리다이렉트 후 최종 주소: {final_url}\n\n"
            f"페이지 본문(앞부분):\n{page_text}"
        )
    else:
        user = (
            f"광고를 눌러 도착한 주소: {url}\n\n"
            "페이지를 가져오지 못했다. 주소만 보고 판정하라. 고위험은 내리지 마라."
        )
    try:
        resp = client.messages.create(
            model=MODEL,
            max_tokens=500,
            system=CRITERIA,
            output_config={"format": {"type": "json_schema", "schema": SCHEMA}},
            messages=[{"role": "user", "content": user}],
        )
        import json

        v = json.loads(resp.content[0].text)
        return {
            "risk": v["risk"],
            "reason": v["reason"],
            "advice": v["advice"],
            "evidence": v.get("evidence", ""),
        }
    except Exception as e:
        # LLM 장애에도 서버 응답 필수. 차단·열기 결정은 앱이 아니라 사용자 몫 —
        # UNKNOWN은 앱에서 "확인하지 못했습니다 + 선택지"로 표시
        log.warning(f"LLM 실패: {type(e).__name__}: {e}")
        return {
            "risk": "UNKNOWN",
            "reason": "안전한지 확인하지 못했습니다.",
            "advice": "잘 모르겠으면 돌아가세요.",
            "evidence": "",
        }


def _fill_type(v: dict) -> dict:
    """비어 온 유형을 이유 문장에서 역추적

    중위험·고위험인데 `none` 선택 사례 실재(실측: 보험 상담 페이지 —
    reason에는 "이름·전화번호를 입력해야 합니다"라고 쓰고 type은 none). 프롬프트로
    막았지만 모델이 또 빠뜨려도 보호자 화면이 빈칸이 되지 않도록 여기서 보정
    추측이 아니라 **모델 자신이 쓴 문장**에서 고르므로 새 사실 생성 없음
    """
    if v.get("risk") == "LOW":
        return {**v, "type": "none"}
    if v.get("type") and v["type"] != "none":
        return v
    reason = v.get("reason", "")
    table = [
        ("impersonation", ("사칭", "가장한", "위장")),
        ("credentials", ("비밀번호", "주민등록번호", "주민번호", "카드번호")),
        ("apk", ("apk", "설치 파일", "앱 파일")),
        ("personal_info", ("개인정보", "전화번호", "연락처", "이름을 입력", "상담 신청")),
        ("payment", ("결제", "구매", "가입을 유도", "사업자 정보")),
        ("urgency", ("당첨", "무료", "한정", "경품", "이벤트 응모")),
        ("investment", ("투자", "수익", "재테크", "부업")),
        ("contentfarm", ("콘텐츠팜", "미끼", "광고성 글")),
        ("unverifiable", ("확인하지 못", "확인할 수 없", "가져오지 못")),
    ]
    for kind, words in table:
        if any(w in reason for w in words):
            log.info(f"유형이 비어 이유 문장에서 되짚음 -> {kind}")
            return {**v, "type": kind}
    return {**v, "type": v.get("type") or "none"}


def _verify(v: dict, page_text: str | None) -> dict:
    """근거 없는 고위험 → 중위험 강등

    고위험은 사용자의 선택권을 빼앗는 판정이라 LLM의 말만으로 확정 불가.
    evidence가 실제 페이지 원문에 있어야만 인정. 페이지의 무한한 변형을
    다 알 필요 없음 — "근거를 대지 못하면 선택권을 뺏지 않는다"만 강제
    """
    v = _fill_type(v)
    if v["risk"] != "HIGH":
        return v
    ev = v.get("evidence", "").strip()
    grounded = bool(ev) and page_text is not None and ev in page_text
    if not grounded:
        log.info("고위험인데 근거가 페이지에 없다 -> MEDIUM으로 내림")
        v = {**v, "risk": "MEDIUM"}
    return v


# **여러 사람이 나눠 쓰는 곳.** 등록 도메인이 "광고주"가 아니라 "장터"
# blog.naver.com 글 하나가 중위험이면 등록 도메인은 naver.com이라
# 네이버로 가는 모든 광고가 그 판정을 상속 — 실제 네이버·쿠팡이
# 노랑으로 물든 사례. 이런 곳은 도메인을 캐시 열쇠로 미사용
# (판정 기록은 그대로 보존, 광고 식별키(ad_key)로 식별하는 길도 그대로)
SHARED_SITES = {
    "naver.com", "daum.net", "kakao.com", "nate.com", "coupang.com",
    "tistory.com", "blogspot.com", "blogger.com", "wordpress.com",
    "cafe24.com", "modoo.at", "imweb.me", "notion.site", "linktr.ee",
    "google.com", "youtube.com", "facebook.com", "instagram.com",
    "x.com", "twitter.com", "11st.co.kr", "gmarket.co.kr", "auction.co.kr",
    # 분양 랜딩페이지 호스팅 — 광고주마다 경로만 다름. 한 캠페인(경찰청 언급)의 판정이
    # 다른 캠페인(아파트 분양)에 붙은 실측(2026-08-23 eval)
    "mapy.co.kr",
}


def _shareable(site: str | None) -> str | None:
    """캐시 열쇠로 쓸 수 있는 사이트만 반환. 장터면 None."""
    return None if (not site or site in SHARED_SITES) else site


def _site_of(url: str) -> str | None:
    """캐시 열쇠 — 등록 도메인. 앱의 WebNode.siteOf와 같은 규칙(co.kr류는 세 조각)."""
    m = re.match(r"https?://([^/?#]+)", url.lower())
    if not m:
        return None
    host = m.group(1).split(":")[0].strip(".")
    parts = host.split(".")
    if len(parts) <= 2:
        return host
    two_level = {"co", "or", "ne", "go", "ac", "re", "pe"}
    take = 3 if len(parts[-1]) <= 3 and parts[-2] in two_level else 2
    return ".".join(parts[-take:])
