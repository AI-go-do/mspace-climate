# mspace-climate 작업 가이드

@TEAM.md

- 담당: 재웅(11/12, 수연 대신 관리자 센서·자동 제어를 맡음). 답은 한국어 해요체로 짧게
- 하는 일·구조·설정은 README.md. 요구사항 기준은 AI-go-do/docs `requirements/requirements.md`(FR-DEV-101·104·106·107)
- core(지영)·device(수연)·assistant(영준) 코드는 직접 고치지 않아요. 필요한 변경은 요청 문서나 그 레포 PR로
- 숫자는 코드에 박지 않고 `application.yml`의 `climate.*`로
- 기기를 더할 때는 `Appliance` 어댑터 하나 + 설정 한 줄. 파사드·ClimateAgent에 브랜드별 if를 늘리지 않아요
- 로그에 사용자 정보·토큰·센서 원문 본문을 남기지 않아요. 방 번호·종류만
- 0.0.0.0 바인딩·포트 공개 금지(게이트웨이 뒤에서만). 비밀값은 .env·Secrets에만
