package kr.ac.chosun.mspace.climate.application;

/**
 * 알림 보내기 포트. 지금은 기록(화면에서 보임)만. 학생 알림함·웹 푸시는 core 알림 API가 생기면 어댑터를 더한다.
 */
public interface Notifier {

    void send(Alert alert);

    /** 학생에게 실제로 닿는 어댑터면 true. 지금 기록 어댑터는 false(화면 판단 카드에 "알림함 연결 전"으로 보임) */
    default boolean reachesStudents() {
        return false;
    }
}
