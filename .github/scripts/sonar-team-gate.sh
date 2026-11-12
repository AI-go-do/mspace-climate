#!/usr/bin/env bash
# 팀 규칙 품질 게이트 (TEAM.md 7장 "main은 새 코드 커버리지 80% 이상").
# 학교 Sonar 기본 게이트(Sonar way)의 통과·실패 대신, 방금 올린 분석의 새 코드 값을 읽어 팀 기준으로 판정한다.
# 막는 것: 커버리지 미달, 중복 초과, 새 버그, 새 취약점
# 막지 않는 것: 코드 스멜(스타일·복잡도), 보안 핫스팟 검토 — 대시보드에 남기고 시연 뒤에 줄여 간다
# 필요한 환경변수: SONAR_TOKEN, SONAR_HOST_URL, SONAR_PROJECT, MIN_COVERAGE, MAX_DUPLICATION
# 토큰 값은 출력하지 않는다
set -euo pipefail

# 주소 끝의 / 는 뺀다. 끝에 / 가 있으면 //api/... 가 되어 JSON이 아닌 응답이 온다 (core 11-06 #82)
SONAR_HOST_URL=${SONAR_HOST_URL%/}
api() { curl -sf -m 30 -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/$1"; }

# 1) 스캐너가 남긴 작업 id로 서버 처리가 끝나기를 기다린다 (최대 5분)
report=target/sonar/report-task.txt
[ -f "$report" ] || { echo "$report 가 없어요: Sonar 분석이 올라가지 않았어요"; exit 1; }
task_id=$(grep '^ceTaskId=' "$report" | cut -d= -f2- || true)
[ -n "$task_id" ] || { echo "$report 에 ceTaskId가 없어요"; exit 1; }
status=PENDING
# 요청이 매번 시간 초과돼도 전체 5분을 넘지 않게, 횟수가 아니라 경과 시간으로 끝낸다
deadline=$((SECONDS + 300))
while [ "$SECONDS" -lt "$deadline" ]; do
  # 한 번 못 읽어도(네트워크 순간 끊김) 멈추지 않고 다시 묻는다
  # 응답이 JSON이 아니면 앞부분을 남겨 원인을 보이게 한다 (5분 동안 조용히 기다리지 않게)
  if ! body=$(api "ce/task?id=$task_id"); then
    echo "참고: ce/task를 읽지 못했어요 (다시 물어요)" >&2; status=PENDING
  else
    status=$(echo "$body" | jq -r '.task.status // empty' 2>/dev/null) || status=
    [ -n "$status" ] || { echo "참고: ce/task 응답에 상태가 없어요: $(echo "$body" | head -c 120)" >&2; status=PENDING; }
  fi
  case "$status" in
    SUCCESS) break ;;
    FAILED|CANCELED) echo "Sonar 서버가 분석을 처리하지 못했어요: $status"; exit 1 ;;
  esac
  sleep 5
done
[ "$status" = SUCCESS ] || { echo "Sonar 분석 처리 시간이 5분을 넘었어요 (마지막 상태: $status)"; exit 1; }
analysis_id=$(api "ce/task?id=$task_id" | jq -r '.task.analysisId // empty')
[ -n "$analysis_id" ] || { echo "분석 id를 읽지 못했어요"; exit 1; }

# 2) 값 읽기. 공용 토큰은 measures/component·분석 목록·ce/component가 모두 403이다 (11-06 #81 실측).
# 읽을 수 있는 두 곳만 쓴다
#  - 커버리지·중복: qualitygates/project_status?analysisId= 의 조건 실제값. 분석 id로 묻기 때문에
#    그 사이 다른 분석(develop push 등)이 끝나도 이 분석의 값을 읽는다
#  - 버그·취약점 수: 새 코드 이슈 목록(issues/search)에서 유형 BUG·VULNERABILITY 수. 코드 스멜은 세지 않는다
status=$(api "qualitygates/project_status?analysisId=$analysis_id") \
  || { echo "게이트 조건을 읽지 못했어요 (qualitygates/project_status)"; exit 1; }
cond() { echo "$status" | jq -r --arg k "$1" '[.projectStatus.conditions[]? | select(.metricKey == $k) | .actualValue][0] // empty'; }
coverage=$(cond new_coverage)
duplication=$(cond new_duplicated_lines_density)
issues=$(cond new_violations)
count_type() {
  local out code
  out=$(curl -sS -m 30 -u "$SONAR_TOKEN:" -w '\n%{http_code}' \
        "$SONAR_HOST_URL/api/issues/search?componentKeys=$SONAR_PROJECT&inNewCodePeriod=true&resolved=false&types=$1&ps=1") \
    || { echo "참고: 유형 $1 이슈 수 요청이 끝까지 오지 않았어요 (위 curl 오류 참고)" >&2; return 0; }
  code=$(echo "$out" | tail -n1)
  if [ "$code" != "200" ]; then
    echo "참고: 유형 $1 이슈 수를 읽지 못했어요 (상태 $code: $(echo "$out" | sed '$d' | head -c 200))" >&2
    return 0
  fi
  echo "$out" | sed '$d' | jq -r '.total // empty'
}
# 실패할 때 어느 파일·줄인지 로그에 바로 보이게 최대 10건을 적는다 (학교 Sonar 화면은 밖에서 안 열릴 수 있음)
list_type() {
  api "issues/search?componentKeys=$SONAR_PROJECT&inNewCodePeriod=true&resolved=false&types=$1&ps=10" \
    | jq -r '.issues[]? | "  - \(.component | sub("^[^:]*:"; "")):\(.line // "?") [\(.rule)] \(.message)"' 2>/dev/null \
    || echo "  (목록을 읽지 못했어요)"
}
bugs=$(count_type BUG)
vulns=$(count_type VULNERABILITY)

echo "새 코드 커버리지: ${coverage:-없음}% (기준 ${MIN_COVERAGE}% 이상)"
echo "새 코드 중복: ${duplication:-없음}% (기준 ${MAX_DUPLICATION}% 이하)"
echo "새 버그: ${bugs:-못 읽음}건, 새 취약점: ${vulns:-못 읽음}건 (있으면 실패)"
echo "새 코드 이슈 전체: ${issues:-0}건 (코드 스멜은 막지 않음, 대시보드에서 줄여 가기)"

fail=0
# 값이 빠진 채로 통과하지 않게, 못 읽은 값은 0으로 치지 않고 실패한다
if [ -z "$coverage" ]; then
  echo "실패: 새 코드 커버리지 값이 없어요 (서버 게이트 조건에 없거나 JaCoCo 보고서가 안 올라갔을 수 있음)"; fail=1
elif awk -v a="$coverage" -v b="$MIN_COVERAGE" 'BEGIN{exit !(a < b)}'; then
  echo "실패: 새 코드 커버리지가 ${MIN_COVERAGE}%보다 낮아요"; fail=1
fi
if [ -z "$duplication" ]; then
  echo "실패: 새 코드 중복 값이 없어요"; fail=1
elif awk -v a="$duplication" -v b="$MAX_DUPLICATION" 'BEGIN{exit !(a > b)}'; then
  echo "실패: 새 코드 중복이 ${MAX_DUPLICATION}%보다 높아요"; fail=1
fi
if [ -z "$bugs" ]; then
  echo "실패: 새 버그 수를 읽지 못했어요"; fail=1
elif [ "$bugs" != "0" ]; then
  echo "실패: 새 코드에 버그가 ${bugs}건 있어요. 대시보드 Issues에서 유형 Bug를 보세요"; list_type BUG; fail=1
fi
if [ -z "$vulns" ]; then
  echo "실패: 새 취약점 수를 읽지 못했어요"; fail=1
elif [ "$vulns" != "0" ]; then
  echo "실패: 새 코드에 취약점이 ${vulns}건 있어요. 대시보드 Issues에서 유형 Vulnerability를 보세요"; list_type VULNERABILITY; fail=1
fi
[ "$fail" = 0 ] && echo "팀 규칙 품질 게이트 통과"
exit "$fail"
