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

    /**
     * 실제로 나올 수 있는 값 범위로 자른다(예측선이 직선이라 30분 뒤 CO2가 -200ppm으로 나오던 문제).
     * CO2는 바깥 공기(약 400ppm) 밑으로, 습도는 0~100% 밖으로 안 간다
     */
    public double clamp(double v) {
        return switch (this) {
            case CO2 -> Math.max(400, v);
            case HUMIDITY -> Math.max(0, Math.min(100, v));
            case TEMPERATURE -> v;
        };
    }

    /** 화면 글자용 숫자: CO2·습도는 정수, 온도는 소수 한 자리(.0은 뺌) */
    public String format(double v) {
        if (this == TEMPERATURE) {
            double r = Math.round(v * 10) / 10.0;
            return r == Math.rint(r) ? Long.toString((long) r) : String.format("%.1f", r);
        }
        return Long.toString(Math.round(v));
    }

    /** 1분당 변화: 작으면 소수 둘째 자리까지 (온도 0.06°C/분이 "0.0"으로 보이지 않게) */
    public String formatRate(double perMinute) {
        double a = Math.abs(perMinute);
        if (a >= 10) {
            return Long.toString(Math.round(a));
        }
        String s = a >= 1 ? String.format("%.1f", a) : String.format("%.2f", a);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
