# AI-go-do 팀 공통 맥락 (TEAM.md)

> 상태: **초안** (2026-11-02, 영준 작성). 팀 전원 확인 후 확정
> 모든 저장소 루트에 같은 내용으로 둡니다. 각 저장소의 `CLAUDE.md` 첫 줄에 `@TEAM.md`를 넣으면 Claude Code가 이 맥락을 읽습니다.
> **원본은 [AI-go-do/docs](https://github.com/AI-go-do/docs)입니다.** 이 파일은 요약이고, 내용이 다르면 docs 저장소를 따릅니다. 고칠 때는 docs를 먼저 고치고 이 파일을 맞춥니다.

## 1. 무엇을 만드나

조선대 IT융합대학 M-space(회의·스터디 방 5개)를 **사람(교학팀) 없이도** 예약하고, **예약만으로 문을 여는** 예약·출입 시스템입니다.

- 지금 방식: 교학팀 명부 작성 → 학생증 맡김 → 열쇠 → 사용 → 반납. 18시 이후·시험 기간에는 관리가 끊깁니다
- 설문에서 가장 큰 불편은 "비었는지 직접 가 봐야 안다". 열쇠 없이 예약만으로 이용하는 데 다수가 찬성 (설문은 11/9까지 수집 중)
- 출품: IT Festival SW중심대학 캡스톤디자인 경진대회 (작년 기준: 서류·5분 영상 11/21 16:00 마감, 본 행사 11/28). 11/20 수업 발표
- 학교 시범운영은 답이 없어 기다리지 않습니다. **키패드(키 없는 입실)가 기본**이고, 학교가 원하면 시범운영을 선택지로 둡니다

## 2. 팀과 저장소

| 사람 | 맡는 것 | 저장소 |
|---|---|---|
| 지영 | 예약 코어: 예약 규칙, 동시 예약 처리, 신고·노쇼 | `core` |
| 수연 | 키패드·센서(ESP32·아두이노), 재실·환기 | IoT 저장소 (이름 정하면 기록) |
| 재웅 | 인프라·CI/CD, 학교 서버 배포, 팀 문서 | `infra`, `docs`, `Scrum` |
| 영준 | AI 비서 + MCP 서버 | [`mspace-assistant`](https://github.com/AI-go-do/mspace-assistant) |
| (정해야 함) | 화면 | `front` |

- 조직: https://github.com/AI-go-do
- 문서: [docs](https://github.com/AI-go-do/docs) · 스크럼·스프린트 기록: [Scrum Discussions](https://github.com/AI-go-do/Scrum/discussions)
- 저장소를 새로 만들 때는 팀에 먼저 알립니다. 이름은 각자 정해 이 표에 기록합니다

## 3. 일정과 버전

| 기간 | 버전 | 한 줄 |
|---|---|---|
| 11/2–11/5 | v0.1 | 예약 한 바퀴: 메일 로그인 → 예약 → 6자리 번호 → 키패드 체크인 → 퇴실 |
| 11/6–11/10 | v0.2 | 사람 없이 돌아감: 자동 퇴실, 노쇼, 즉시 시작, 연장하기(1회), 알림, 방 현황, 재실·환기 센서 |
| 11/11–11/13 | v0.3 | 운영·AI: 신고, 사진 체크인·아웃, 관리자 판정, AI 비서(MCP) |
| **11/13** | — | **기능 동결.** 이후 `main` 고정 |
| 11/14–11/19 | — | 발표 준비 |
| 11/20 | — | 발표 |

- 자세한 범위와 수용 기준: [요구사항 명세](https://github.com/AI-go-do/docs/blob/main/requirements/requirements.md)

## 4. 예약 규칙 (요약)

| 규칙 | 값 |
|---|---|
| 단위 | 30분 (정각·30분) |
| 1회 최대 | 2시간 |
| 미리 예약 | **시작 시각**이 오늘부터 7일 안. 자정 넘김 허용(7일째 23:00–01:00 가능) |
| 대표자 | 끝나지 않은 예약(`BOOKED`·`IN_USE`) 동시 1건. 하루 횟수 제한 없음 |
| 참여자 | 횟수 제한 없음, 같은 시간 두 방 금지 |
| 방 인원 | 1–4번 방 3–5명, 5번 방 5–8명 |
| 즉시 시작 | 누른 시각부터, 끝은 30분 칸으로 올림 (14:17 → 최대 16:30) |
| 노쇼 | 미리 예약은 시작 + 15분, 즉시 시작은 10분 안에 체크인 없으면 |
| 체크인 | 6자리 번호(대표자만 받음). 시작 10분 전–종료 전 유효, 5번 틀리면 1분 잠금 |
| 퇴실 | 버튼(즉시) 또는 종료 시각(자동). 퇴실하면 방이 바로 빔 |

- 겹침·대표자 1건은 **DB 제약**으로 막습니다 (`no_room_overlap`, `one_active_per_owner`). 동시 요청 100건에서 중복 0건
- 숫자(7일·2시간 등)는 코드에 박지 않고 설정값으로 둡니다
- 상태: `BOOKED` → `IN_USE` → `CHECKED_OUT` / `AUTO_CHECKED_OUT` / `AWAY_RELEASED`(자리 비움, v0.2), 그 밖에 `CANCELED`, `NO_SHOW`

## 5. 구조

```
학생 폰(React PWA) ─▶ nginx ─▶ front
                             └▶ core(Spring Boot) ─▶ PostgreSQL
문 앞 키패드(ESP32·아두이노) ─▶ core   (POST /api/access/verify)
방 안 센서(v0.2) ─▶ core
AI 비서·MCP(v0.3) ─▶ core API만 호출
```

- **기능은 core API로만 엽니다.** 키패드·센서·비서는 DB에 직접 붙지 않습니다
- 바깥 장치·서비스는 인터페이스(포트) 뒤에 둡니다

| 인터페이스 | 지금 (시연) | 나중 (도입) |
|---|---|---|
| `IdentityVerifier` | 학교 메일 인증 | 학교 학사 DB |
| `AccessDevice` | 키패드 모형 | 실제 도어락·QR |
| `NotificationChannel` | 메일 | 텔레그램·푸시 |
| `PresenceDetector` | 레이더·조도·CO2 규칙 | 다른 센서 |
| `LlmClient` | Gemini | Ollama (Jetson 보드 B) |

- 장치가 같은 신호를 두 번 보내도 `eventId`로 한 번만 처리합니다
- 기록에 `created_via`(WEB / AGENT / DEVICE)를 남기고, 상태 변경은 도메인 이벤트로 냅니다
- **AI 비서 규칙:** 권한 검사는 core가 하고, 비서는 사용자 본인 토큰(JWT scope로 제한)으로만 부릅니다. 예약·취소 같은 쓰기는 사용자 확인 뒤에만. 시연은 가짜 데이터로만 합니다

## 6. 기술 스택

| 영역 | 선택 |
|---|---|
| 프론트 | React · Vite · TypeScript · Tailwind · PWA · TanStack Query |
| 백엔드 | Java 21 · Spring Boot 4 · **Maven** |
| DB | PostgreSQL · Flyway (`ddl-auto: validate`) |
| 장치 | ESP32·아두이노 (부품 오기 전에는 우노 + 노트북 USB) |
| Jetson | Python |
| AI 비서 | Java 21 · Spring Boot 4 · Maven (core와 같은 스택, 11/2 결정) |
| CI | GitHub Actions · Testcontainers(PostgreSQL, H2 안 씀) · SonarQube(학교 Community 서버) · CodeRabbit(참고용) |
| 배포 | Docker · docker compose · GHCR · nginx · 학교 실습 서버 |

## 7. 작업 방식

**브랜치 (간소화 Git Flow)**

| 브랜치 | 용도 | 배포 |
|---|---|---|
| `main` | 버전이 끝났을 때만. 시연용 | 시연 서버 |
| `develop` | 매일 합치는 곳 | 개발 서버 (자동) |
| `feat/12-reservation-create` 등 | `develop`에서 따서 1–2일 안에 합침 | — |

- `main`, `develop` 직접 push 금지
- 커밋: `feat: 예약 생성 API 추가` (feat, fix, refactor, test, docs, chore) + `Refs #12`
- PR: 제목은 커밋 형식, 본문에 요구사항 ID(`FR-RSV-001`)와 `Closes #12`. 400줄 안. **매일 15시까지**
- 합치기: 빌드·테스트 통과 + 팀원 1명 승인 → `develop`은 Squash merge, `main`은 Merge commit([컨벤션](https://github.com/AI-go-do/docs/blob/main/conventions.md)). `main`은 새 코드 커버리지 80% 이상
- 리뷰 댓글 머리말: `[필수]` `[제안]` `[질문]`
- 이슈 제목: `[FR-RSV-001] 예약 생성`, 라벨은 종류 + 버전(`v0.1`)

**스크럼**
- 매일 아침, 한 명당 슬라이드로 "어제 한 것 / 트러블슈팅 / 왜 그렇게 결정했나 / 오늘 할 것·도움 요청". 화면 연결해서 다 같이
- 기록은 [Scrum Discussions](https://github.com/AI-go-do/Scrum/discussions)에 템플릿으로 (`[스크럼] N주차 N일차 MM/DD(요일)`, 공통은 "A vs B -> 결정")
- 스프린트는 주 단위: 시작일에 목표·할 일, 마지막 날 회고
- 15시까지 구현하고 PR, 오후는 리뷰·코드 읽기·다음 날 준비

**포트폴리오 원칙**
- 한 명당 끝까지 맡은 서비스 1개, 트러블슈팅 기록 2건 이상, 결과 숫자 1개, 발표 영상 시연 1분
- 기여도는 PR과 리뷰로 남깁니다. 커밋 작성자 이메일을 본인 GitHub 이메일로 맞춥니다

## 8. 코드 규칙 (핵심만)

전체는 [팀 컨벤션](https://github.com/AI-go-do/docs/blob/main/conventions.md).

| 영역 | 규칙 |
|---|---|
| 패키지 | `kr.ac.chosun.mspace.<기능>.{domain, application, adapter.in.web, adapter.out}`. 기능끼리는 서로의 `application`만 부른다 |
| API | `/api/` + 복수 명사 + kebab-case. 상태 변경은 `POST /{id}/<동사>`. JSON camelCase |
| 시간 | ISO-8601 `+09:00`. 날짜 계산은 `Asia/Seoul`, DB는 `timestamptz` |
| 오류 | `{ "code": "...", "message": "..." }`. 코드는 `RESERVATION_OVERLAP`처럼 |
| 상태 코드 | 400 입력 오류 · 401 로그인 안 함 · 403 권한 없음 · 404 없음 · 409 충돌 |
| API 문서 | springdoc OpenAPI 자동 생성(아직 core에 없음, 그 전까지는 [API 명세](https://github.com/AI-go-do/docs/blob/main/api/README.md)가 기준). 프론트 타입과 MCP 도구가 이것을 근거로 한다 |
| DB | snake_case, `…_at`, `is_…`, 상태는 대문자 문자열 + CHECK. 테이블은 `schema-v2.sql` 기준 |
| 테스트 | `@DisplayName` 한국어, given/when/then, 수용 기준 1개 = 테스트 1개 이상 |

## 9. 배포와 환경

| 환경 | 어디서 | 언제 |
|---|---|---|
| 로컬 | 각자 노트북, `docker compose up` | 개발 |
| 개발 서버 | 학교 실습 서버 (재웅 계정) | `develop`에 합치면 자동 |
| 시연 서버 | 학교 실습 서버 (재웅 계정) | `main`에 합치면 (버전 완료) |

- 흐름: PR → 빌드·테스트 → `develop` 합침 → 이미지 → GHCR → 학교 서버 `docker compose pull && up -d` → `/actuator/health`
- 재웅 포트 8410–8419 (8410 시연 입구, 8411 개발 입구, DB는 외부 노출 안 함). 다른 팀원은 각자 포트 범위에서 개인 테스트
- 되돌리기: 이미지 태그를 이전 버전으로 바꾸고 `docker compose up -d`
- 시연 대비: 학교 서버·와이파이가 죽어도 노트북에서 전체를 띄울 수 있게 연습

## 10. 절대 하지 않는 것

- 비밀번호·키·`.env`를 커밋하기. 저장소에는 `.env.example`만, 값은 GitHub Actions Secrets → 서버 `.env`
- 접속 정보(계정·비밀번호)를 공개 저장소나 채팅방에 올리기
- `main`, `develop`에 직접 push
- 이미 합쳐진 Flyway 마이그레이션 파일 고치기 (새 번호 파일로)
- 테스트를 끄거나 건너뛰어 빌드 통과시키기
- 실제 학생 개인정보를 테스트·로그·시연 데이터에 넣기
- 돈 쓰기: **사비 0원 원칙.** 유료 클라우드(AWS 등)는 쓰지 않는다
- **Jetson 보드 A** 사용 (보드 B만)
- 교학팀 허락 없이 학교 방에 장치 설치

## 11. 아직 정할 것

| 항목 | 상태 |
|---|---|
| `front` 저장소 담당, 화면 공통 틀 | 정해지지 않음 |
| IoT 저장소 이름 | 수연 |
| 학교 서버 사용 조건 (11/20까지 가능? HTTPS? Actions SSH 배포 허용?) | 재웅 확인 |
| Jetson 보드 B에 맡길 것 (CO2 예측 / 비서용 Ollama / 규칙 답변) | 팀 결정 대기 |
| 설문 추첨 개수·금액·비용 분담 | 11/9 마감 전 |
| 수연 부품비 분담 | 금액 기록 후 |
