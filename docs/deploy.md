## GuADian 판정 서버 배포 기준

`server/` 코드를 Azure App Service에 올리는 방법

- 서버 코드 [[server/main.py]](../server/main.py)
- 판정 기준 [[judge.md]](judge.md)
- Firebase 설정 [[firebase.md]](firebase.md)

### Table of Contents

1. [배포 대상](#1-배포-대상)
2. [배포 순서](#2-배포-순서)
3. [건드리면 안 되는 것](#3-건드리면-안-되는-것)
4. [되돌리기](#4-되돌리기)
5. [문제 해결](#5-문제-해결)

---

### 1. 배포 대상

| 항목 | 값 |
| :-- | :-- |
| App Service | `adalert-judge` |
| 리소스 그룹 | `adalert-rg` |
| 지역 | Korea Central |
| 주소 | `https://adalert-judge.azurewebsites.net` |
| 런타임 | `PYTHON\|3.10` |
| 시작 명령 | `python -m uvicorn main:app --host 0.0.0.0 --port 8000` |
| 배포 방식 | **zip push (수동)** — GitHub Actions 연동 없음 |

앱의 서버 주소는 [`HttpJudge.kt`](../app/src/main/java/com/flyai/adalert/HttpJudge.kt)의 `BASE_URL`에 하드코딩. 주소가 바뀌면 앱도 다시 빌드해야 함

| 환경변수 | 용도 |
| :-- | :-- |
| `ANTHROPIC_API_KEY` | 판정 LLM 호출. **앱(APK)에는 없음 — 서버에만** |
| `FIREBASE_SA_PATH` | 보호자 알림(FCM)용 서비스 계정 키 경로 |
| `SCM_DO_BUILD_DURING_DEPLOYMENT` | `true` — Azure가 `requirements.txt`를 설치 |

---

### 2. 배포 순서

```bash
az login                      # 최초 1회

# 1. 올릴 파일만 zip으로. server/ 폴더 채로가 아니라 파일이 zip 루트에 오게
cd server
zip ../deploy.zip main.py judge_agent.py requirements.txt eval.py eval_set.json

# 2. 배포 (약 1분)
az webapp deploy -n adalert-judge -g adalert-rg --src-path ../deploy.zip --type zip
```

`smoke*.py` · `test_*.py`는 서버 구동에 불필요하므로 제외

| 순서 | 확인 | 기대 |
| :-: | :-- | :-- |
| 1 | `curl .../health` | `{"ok":true, "push":true, "blocklist":26만+}` |
| 2 | `curl .../recent` | 표 머리글이 `시각 · 판정 · 유형 · 이유 · 소요 · 주소` |
| 3 | `python eval.py --server https://adalert-judge.azurewebsites.net` | 판정이 돌아옴 · 맞춤 수가 직전 배포와 같거나 높음 |
| 4 | 같은 명령에 `--fresh --stream` | 캐시 없이 새 판정 — 소요 중앙값·p90·12초 초과 건수 (2026-08-24 7차 기준: 맞춤 37/45 · 중앙값 3.6s · p90 5.4s · 최대 7.3s · 12초 초과 0 — 왕복 1회 모드 · 차수별 이력은 [judge.md §7](judge.md#7-차수별-측정)) · 저위험 선행 통보가 결론보다 먼저 오는지 |

`push`가 `false`면 Firebase 키를 못 찾은 것 — [3장](#3-건드리면-안-되는-것) 참고

---

### 3. 건드리면 안 되는 것

배포는 `/home/site/wwwroot`만 덮어씀. **`/home` 바로 아래 파일은 배포에 지워지지 않음** — 아래 둘이 거기 있는 이유

| 경로 | 무엇 | 지워지면 |
| :-- | :-- | :-- |
| `/home/firebase-sa.json` | FCM 서비스 계정 키 | 보호자 알림 전부 중단 (`/health`의 `push`가 `false`) |
| `/home/judgments.db` | 판정 기록 · 도메인 등록일 | 판정 캐시 소멸 → 같은 광고를 매번 LLM에 재질의 |
| `/home/blocklist.db` | 차단 목록 (2026-08-24 판정 기록과 분리 — 목록 손상이 기록을 인질 잡던 사고 재발 방지) | 무해 — 자동 재수신, 그동안 빠른 길만 비활성 |

서비스 계정 키는 **비밀번호에 준하는 값**. 저장소에 커밋 금지(`.gitignore` 등재), 로컬에도 남기지 말 것

---

### 4. 되돌리기

배포 직전 상태를 받아 둘 수 있음

```bash
TOKEN=$(az account get-access-token --resource https://management.azure.com --query accessToken -o tsv)
curl -H "Authorization: Bearer $TOKEN" \
  "https://adalert-judge.scm.azurewebsites.net/api/zip/site/wwwroot/" -o wwwroot-backup.zip
```

다만 `wwwroot`에는 Oryx 빌드 산출물(`output.tar.zst`)이 들어 있어 그대로 복원하기 까다로움.
**되돌릴 때는 이전 소스를 다시 zip으로 배포하는 쪽이 확실함**

---

### 5. 문제 해결

| 증상 | 확인 |
| :-- | :-- |
| `/health`가 안 열림 | App Service `state`가 `Running`인지 · 시작 명령이 위 표와 같은지 |
| `push`가 `false` | `FIREBASE_SA_PATH`가 가리키는 파일이 실제로 있는지 |
| 판정이 전부 `UNKNOWN` | `ANTHROPIC_API_KEY` 유효한지 |
| 고친 코드가 반영 안 됨 | `/recent` 표 머리글로 어느 코드가 도는지 확인 · 배포 이력의 `active` 확인 |
| 배포는 됐는데 500 | `SCM_DO_BUILD_DURING_DEPLOYMENT=true`인지 (`requirements.txt` 미설치 시 import 실패) |
| 판정이 10초 넘게 걸림 | `/recent` 소요 열 · 경로에 `예산 소진(미실행)`이 잦으면 페이지 수신이 느린 것 — 서버 코드가 아니라 착지 사이트 문제 |
| 저위험인데 앱이 문장까지 기다림 | `curl -N -d '{"url":...,"stream":true}' .../judge`로 첫 줄이 둘째 줄보다 먼저 오는지 — 한꺼번에 오면 프록시가 응답을 모으는 것(App Service 설정 확인) |
| 한 사이트의 판정이 다른 광고에 붙음 | 여러 광고주가 경로만 다르게 쓰는 호스팅(mapy.co.kr류) → `main.py`의 `SHARED_SITES`에 추가 |

배포 이력

```bash
az rest --method get --url "https://management.azure.com/subscriptions/<구독>/resourceGroups/adalert-rg/providers/Microsoft.Web/sites/adalert-judge/deployments?api-version=2022-03-01"
```

---
