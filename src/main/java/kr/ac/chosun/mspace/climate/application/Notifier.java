package kr.ac.chosun.mspace.climate.application;

/**
 * 알림 보내기 포트. 지금은 기록(화면에서 보임)만. 학생 알림함·웹 푸시는 core 알림 API가 생기면 어댑터를 더한다.
 */
public interface Notifier {

    void send(Alert alert);
}
