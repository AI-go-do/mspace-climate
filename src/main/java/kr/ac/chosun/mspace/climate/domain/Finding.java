package kr.ac.chosun.mspace.climate.domain;

/** 이상 탐지 결과 하나. metric이 null이면 방 전체(센서 끊김 등) */
public record Finding(Kind kind, Metric metric, String message, Integer etaMinutes) {

    public enum Kind {
        /** 경계를 넘음 */
        OVER,
        /** 아래 경계 밑 */
        UNDER,
        /** 지금 추세면 곧 위 경계에 닿음 (미리 알림의 핵심) */
        RISING_SOON,
        /** 지금 추세면 곧 아래 경계에 닿음 */
        FALLING_SOON,
        /** 평소(기준선)와 크게 다른 값. 센서 이상일 수 있음 */
        SPIKE,
        /** 값이 오래 그대로. 센서가 멈췄을 수 있음 */
        STUCK,
        /** 센서 값이 끊김 */
        OFFLINE
    }

    /** 기기를 움직일 만한 것(센서 이상 쪽은 알림만) */
    public boolean actionable() {
        return kind == Kind.OVER || kind == Kind.UNDER || kind == Kind.RISING_SOON || kind == Kind.FALLING_SOON;
    }
}
