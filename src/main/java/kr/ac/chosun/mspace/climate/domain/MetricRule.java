package kr.ac.chosun.mspace.climate.domain;

/**
 * 지표 하나의 고정 숫자(설정값). 방마다 다른 경계는 기준선이 정하고, 이 값은 그 경계가 넘지 못하는 안전 한계다.
 *
 * @param hardMin  아래 안전 한계 (null이면 아래 경계 없음, 예: CO2)
 * @param hardMax  위 안전 한계 (CO2 1000ppm = 팀 규칙 FR-DEV-104)
 * @param minBand  평균에서 경계까지 최소 거리 (흩어짐이 아주 작은 방에서 경계가 너무 붙지 않게)
 * @param spikeJump 1분 사이 이만큼 넘게 뛰면 센서 이상 후보
 */
public record MetricRule(Double hardMin, Double hardMax, double minBand, double spikeJump) {
}
