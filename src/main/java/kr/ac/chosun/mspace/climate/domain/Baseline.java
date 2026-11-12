package kr.ac.chosun.mspace.climate.domain;

/**
 * 한 방·한 지표의 기준선. 평균(mean)과 흩어짐(std)은 실측값에서, 경계(lower·upper)는 평균 ± k·흩어짐을 안전 한계 안으로 자른 값.
 * learning=true면 값이 모자라 안전 한계만 쓴다(방이 생긴 지 얼마 안 됨).
 * scope: HOUR(이 시간대 값만으로) / ALL(시간대 값이 모자라 전체로) / NONE(값 없음)
 */
public record Baseline(Double mean, Double std, Double lower, Double upper, int samples, boolean learning,
                       String scope) {
}
