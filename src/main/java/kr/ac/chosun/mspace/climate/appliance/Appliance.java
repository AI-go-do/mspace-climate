package kr.ac.chosun.mspace.climate.appliance;

/**
 * 기기 공통 인터페이스 (FR-DEV-106). 가상·삼성·LG 적외선·스마트 플러그 등 어댑터가 이것을 구현한다.
 * 새 기종은 이 인터페이스를 구현한 어댑터 하나 + 설정 한 줄이면 된다. 파사드·오디 코드는 그대로다.
 */
public interface Appliance {

    String id();

    int roomId();

    ApplianceKind kind();

    /** 화면에 보일 제조사 (예: 가상, SAMSUNG, LG) */
    String brand();

    /** 어댑터 이름 (예: virtual, ir, tasmota) */
    String adapter();

    /** 실제 기기(또는 가상)에 명령을 보낸다. 보내지 못하면 ApplianceException */
    void send(ApplianceCommand command);
}
