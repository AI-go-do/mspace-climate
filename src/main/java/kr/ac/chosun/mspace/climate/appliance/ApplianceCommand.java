package kr.ac.chosun.mspace.climate.appliance;

/**
 * 기기 명령 하나. 에어컨만 mode·targetC를 쓴다.
 * core·화면·오디(자동)는 이 모양 하나만 안다. 브랜드마다 다른 신호는 어댑터가 만든다 (FR-DEV-106, Q-25 제안 모양)
 */
public record ApplianceCommand(Power power, Mode mode, Integer targetC) {

    public enum Power { ON, OFF }

    public enum Mode { COOL, HEAT, DRY }

    public static ApplianceCommand on() {
        return new ApplianceCommand(Power.ON, null, null);
    }

    public static ApplianceCommand off() {
        return new ApplianceCommand(Power.OFF, null, null);
    }

    public static ApplianceCommand aircon(Mode mode, int targetC) {
        return new ApplianceCommand(Power.ON, mode, targetC);
    }
}
