# mspace-climate

오디의 **방 공기 관리**예요. 방마다 온도·습도·CO2의 평소 값(기준선)을 실측으로 잡고, 기준에 **닿기 전에** 추세로 미리 알아채서 알리고, 에어컨·공기청정기를 알아서 켜고 꺼요. 관리자는 보기만 해요(무인 운영).

요구사항: FR-DEV-101(수집 + 이상치 탐지), FR-DEV-104(환기 알림), FR-DEV-106(가상 에어컨·공기청정기, 파사드·어댑터), FR-DEV-107(시뮬레이션). 담당: 재웅(11/12, 수연 님 대신).

## 흐름

```
교육장 센서 → 교육장 MQTT 브로커(data.withio.net:1883) ─┬ climate가 직접 구독 (실습실 센서, 받은 그 순간)
                                                      ├ 보드 A 시계열 모델 결과(aip/team5/jaeung/inference/<공간>/state) → climate 직접 구독
                                                      └ 젯슨(device room_env.py) → core POST /api/sensor-events (저장)
                                                                     └ gateway가 core가 받아 준 값만 사본 → climate /internal/sensor-events
climate: 1분마다  재실 읽기(core /api/rooms/status) → 기준선·예측·이상 탐지 → 알림 → 기기 파사드 → 조치 기록
관리자 화면 ← gateway /api/climate/** ← climate
```

- **MQTT 직접 구독**(계정이 있으면 켜짐): 실습실 센서 자리(`spot`)를 설정 `climate.mqtt.spots`대로 방에 붙여요. 실습실 CO2 센서는 4개뿐이라(나머지는 온습도만) 1~2분 간격 3개를 1~3번 방에 붙이고, 4·5번 방은 1번 방 값으로 만들어요(`GET /api/climate/sensors`로 확인). 이렇게 받는 방은 만든 값·게이트웨이 사본 공기 값을 쓰지 않아요(재실 이벤트는 받음)
- **녹화 재생**(센서가 멈췄을 때): 실습실 LoRa 센서가 11/8 11:34부터 아무것도 안 보내요(브로커·InfluxDB 둘 다 그 뒤 0건, 11/12 확인). 그동안은 실습실에서 실제로 잰 1분 기록(10/21~11/8, `replay/lab-1min.csv.gz`)을 **N주 전 같은 요일·같은 시각** 값으로 1분마다 넣어요. 응답·실시간 값에 `source: REPLAY`로 밝혀요. 켜질 때 지난 2시간을 채워 그래프가 비지 않게 해요. 어떤 방에 실제 값이 오면 그 뒤 10분 동안 그 방 재생 값은 버려요(센서가 살아나면 저절로 실제 값). 판단·예측은 들어온 값으로 지금 새로 계산해요(녹화된 판단이 아님). 끄려면 `CLIMATE_REPLAY_ENABLED=false`
- MQTT가 꺼져 있으면 실제 공기 센서는 실습실 하나라 **1번 방만 실제 값**이에요. 2~5번 방은 1번 방 실제 값 + 방마다 작은 차이 + 느린 흔들림으로 만들어요(`climate.derive`, 응답에 `source: DERIVED`). 실제 센서를 다는 방은 설정에서 빼면 그 방 값을 그대로 써요
- 재실은 2~5번 방도 진짜예요(core 방 현황: 센서 재실, 모르면 예약 사용 중)

## 판단 (오디)

| 단계 | 방법 | 설정 |
|---|---|---|
| 기준선 | 방·지표마다 **같은 시간대(±1시간)** 실측 평균·흩어짐(최근 7일). 경계 = 평균 ± 2.5×흩어짐, 안전 한계 안으로 자름 | `keep`, `k`, `rules` |
| 학습 중 | 1분 값 30개가 모이기 전에는 안전 한계만(CO2 1000ppm은 팀 규칙) | `min-samples` |
| 예측 | 최근 30분 직선 추세 → 경계까지 남은 분. 30분 뒤까지 5분 간격 예측선 | `trend-window` |
| 미리 알림 | 15분 안에 닿을 것 같으면 `RISING_SOON` (넘으면 `OVER`) | `lead` |
| 센서 이상 | 1분 사이 크게 뜀(`SPIKE`), 20분 그대로(`STUCK`), 10분 끊김(`OFFLINE`) | `rules.*.spike-jump`, `stuck`, `offline` |

**기기 조작 순서 (안전장치 먼저)**
1. 자동 끈 방은 알림만
2. 빈 방 10분 → 다 끔
3. 사람이 있을 때만 켬 (재실 모르면 안 켬)
4. 온도 곧 넘음 → 냉방, 곧 밑돎 → 난방. 목표 = 이 방 평소 평균(24~26 / 20~22도 안)
5. 습도는 온도 조작이 없을 때만 제습
6. CO2: 공기청정기는 CO2를 못 낮춰서 **방 사용자에게 환기 알림** + 환풍기(있으면). 공기청정기는 창문 열 때 먼지용
7. 평소 값으로 돌아오면 끔. 사람이 손으로 바꾼 기기는 30분 동안 안 건드림
8. 같은 방·같은 알림은 30분에 한 번

예측은 `Forecaster` 포트 뒤에 있어요. 젯슨 시계열 모델(Chronos-2, 실습 모델)은 같은 포트 어댑터로 더하면 돼요.

## 기기 파사드 (FR-DEV-106)

```
ClimateAgent / 관리자 화면 ──▶ ClimateFacade ("1번 방 냉방 25도")
                                  ├─ VirtualAppliance   (가상, 화면에만)
                                  ├─ IrAircon           (ESP32 적외선 노드, SAMSUNG·LG·… 프로토콜 이름만 다름)
                                  └─ TasmotaPlug        (스마트 플러그: 공기청정기·환풍기 전원)
```

새 기종은 `Appliance`를 구현한 어댑터 하나 + `application.yml`의 `climate.appliances` 한 줄이면 돼요. 파사드·오디 코드는 그대로예요. 설정이 비면 방마다 가상 에어컨(`ac-N`)·공기청정기(`purifier-N`).

## API (게이트웨이 뒤, 로그인 필요)

| 메서드·경로 | 내용 | 권한 |
|---|---|---|
| `GET /api/climate/rooms` | 방마다 지금 값·기준선·예측(`etaMinutes`)·이상·기기 상태·`source` | 로그인 |
| `GET /api/climate/rooms/{roomId}/series?minutes=180` | 그래프: 1분 값 + 기준선 띠(`lower`·`upper`) + 예측 점선 | 로그인 |
| `GET /api/climate/log?limit=50` | 오디 조치 기록(누가·언제·왜) + 알림 | 로그인 |
| `GET /api/climate/stream` | 실시간 연결(SSE). `reading`(새 센서 값, 받은 그 순간) · `decision`(오디 판단 카드가 바뀜) · `tick`(1분 판단 끝). 20초마다 ping. 화면은 `fetch`로 읽음(EventSource는 Authorization 헤더를 못 붙임) | 로그인 |
| `GET /api/climate/decisions?limit=20&roomId=` | 오디 판단 카드: `DETECT` 탐지 → `PREDICT` 예측 → `NOTIFY` 알림 → `CONTROL` 제어 → `VERIFY` 다시 확인. 단계마다 `RUNNING`·`DONE`·`SKIPPED`·`FAILED` + 한 줄 이유. 다시 확인은 값이 평소로 돌아올 때까지 `RUNNING`(30분 넘으면 `FAILED`로 닫고 새 카드) | 로그인 |
| `GET /api/climate/sensors` | MQTT로 본 센서(자리·붙은 방·마지막 값, 기기 번호는 끝 4자리)와 보드 A 모델 최신 결과(60분 예측·이상 점수·재실·환기 확률) | 로그인 |
| `POST /api/climate/rooms/{roomId}/appliances/{id}/commands` | `{"power":"ON","mode":"COOL","targetC":25}` | 관리자(core가 판단) |
| `PUT /api/climate/rooms/{roomId}/auto` | `{"enabled":false}` 오디 자동 제어 끔 | 관리자 |

- `POST /internal/sensor-events`: 게이트웨이 사본 전용. 게이트웨이 밖으로 열지 않아요
- 시각은 `+09:00`, 오류는 `{code, message}`
- 관리자인지는 그 사람 토큰으로 core 관리자 API를 불러 확인해요(5분 기억). climate는 관리자·공용 토큰을 갖지 않아요

## 저장

DB를 따로 두지 않아요. 센서 원본은 core DB(`sensor_event`)에 있고, climate는 기준선용 최근 7일 1분 값(방 5개 × 지표 3개 ≈ 15만 칸)만 메모리에 들고 5분마다 `${CLIMATE_DATA_DIR}/climate-state.json`(도커 볼륨)에 남겨요. 재시작하면 이어서 읽어요.

## 설정 (환경 변수)

| 이름 | 기본값 | 설명 |
|---|---|---|
| `JWT_SECRET` | (필수) | core와 같은 값 |
| `CORE_URI` | `http://core:8080` | 재실·관리자 확인 |
| `CLIMATE_DATA_DIR` | `/data` | 스냅숏 폴더 |
| `PORT` · `MANAGEMENT_PORT` | `8095` · `8096` | 밖으로 열지 않음 |
| `CLIMATE_MQTT_USERNAME` · `CLIMATE_MQTT_PASSWORD` | (비움) | 교육장 브로커 계정. 비면 직접 구독을 안 해요(.env에만, 저장소에 안 넣음) |
| `CLIMATE_REPLAY_ENABLED` | `true` | 센서가 멈췄을 때 실측 녹화 재생(실제 값이 오는 방은 실제 값) |
| `CLIMATE_MQTT_URI` | `tcp://data.withio.net:1883` | 브로커 |
| `CLIMATE_MQTT_CLIENT_ID` | `mspace-climate-<임의>` | 같은 이름이 둘이면 브로커가 서로 끊어요(dev·prod·보드 A 수집기) |

숫자(15분·2.5·30분 등)는 `application.yml`의 `climate.*`에 있어요. 기준값 대부분은 첫 판 임시값이라 며칠 실측 뒤 맞춰요.

## 실행

```bash
./mvnw verify                                  # 테스트
JWT_SECRET=... CLIMATE_DATA_DIR=./data ./mvnw spring-boot:run   # :8095
```
