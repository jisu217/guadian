## GuADian Firebase 설정 기준

가족 계정 「ad-alert-family」 콘솔 설정 순서 (최초 1회)

- 보안 규칙 [[firebase/firestore.rules]](../firebase/firestore.rules)

### Table of Contents

1. [설정 순서](#1-설정-순서)
2. [문제 해결](#2-문제-해결)

---

### 1. 설정 순서

| 순서 | 작업 | 값 |
| :-: | :-- | :-- |
| 1 | 프로젝트 생성 | 예: `ad-alert-family` |
| 2 | Android 앱 등록 | 패키지 `com.flyai.adalert` (SHA-1 생략 가능) |
| 3 | 설정 파일 | `app/google-services.json` (커밋 금지) |
| 4 | Authentication | 이메일/비밀번호 활성화 |
| 5 | Firestore | 위치 `asia-northeast3` |
| 6 | 보안 규칙 | `firebase/firestore.rules` 붙여넣기 — 로그인 필수, 자기 가족 문서만 접근 |
| 7 | 빌드 확인 | `./gradlew assembleDebug` |

---

### 2. 문제 해결

| 증상 | 확인 |
| :-- | :-- |
| 빌드 실패 | `app/google-services.json` 위치 |
| 로그인 실패 | Authentication 이메일/비밀번호 활성화 |
| 기록 안 보임 | Firestore 규칙 · 로그인 uid |
| 알림 안 옴 | 앱 알림 권한 · 보호자 `fcmToken` · 서버 `/health`의 `push` |

---
