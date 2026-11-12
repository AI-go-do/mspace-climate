package kr.ac.chosun.mspace.climate.appliance;

/**
 * 가상 기기 (FR-DEV-106: 실제 기기는 조작 API가 없어 가상으로). 아무 데도 보내지 않고, 상태는 파사드가 기억해 화면에 보인다.
 */
public record VirtualAppliance(String id, int roomId, ApplianceKind kind) implements Appliance {

    @Override
    public String brand() {
        return "가상";
    }

    @Override
    public String adapter() {
        return "virtual";
    }

    @Override
    public void send(ApplianceCommand command) {
        // 가상 기기는 보낼 곳이 없다. 상태 기록은 파사드가 한다
    }
}
