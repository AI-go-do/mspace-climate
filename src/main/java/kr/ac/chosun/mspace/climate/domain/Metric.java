package kr.ac.chosun.mspace.climate.domain;

/** 방 공기 지표 세 가지. 교육장 센서(CO2 이벤트 하나에 온도·습도가 같이 옴)가 재는 것 */
public enum Metric {
    CO2("ppm"),
    TEMPERATURE("°C"),
    HUMIDITY("%");

    private final String unit;

    Metric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}
