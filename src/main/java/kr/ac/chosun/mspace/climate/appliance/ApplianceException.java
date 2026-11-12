package kr.ac.chosun.mspace.climate.appliance;

/** 기기에 명령을 보내지 못함 (적외선 노드·플러그가 꺼짐 등) */
public class ApplianceException extends RuntimeException {

    public ApplianceException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApplianceException(String message) {
        super(message);
    }
}
