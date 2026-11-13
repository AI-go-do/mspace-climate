package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 가상 학생이 core에 하는 일(StudentSimulator의 바깥 출구). 실제 학생과 같은 공개 API만 쓴다:
 * 예약·지금 바로 쓰기·취소·연장·퇴실은 그 학생 본인 토큰으로, 입실은 문 앞 QR 장치 토큰으로.
 * 실패하면 {@link Refused}(core가 거절한 코드) 또는 다른 RuntimeException(연결 실패)
 */
public interface StudentDesk {

    /** 가상 학생 한 명. memberId는 core 회원 id, studentId는 참여자로 넣을 학번 */
    record Student(long memberId, String studentId) {
    }

    /** core가 만든 예약. accessCode는 대표자에게만 오는 입실 번호 */
    record Booked(long id, int roomId, Instant startAt, Instant endAt, String status, String accessCode, int headcount) {
    }

    /** 방 하나의 지금 상태(예약 기준). status: AVAILABLE·RESERVED·IN_USE·BLOCKED */
    record RoomNow(String status, Instant currentEndAt) {
    }

    /** core가 4xx로 거절함. code는 core 오류 코드(예: RESERVATION_OVERLAP, USER_SUSPENDED) */
    class Refused extends RuntimeException {
        private final int status;
        private final String code;

        public Refused(int status, String code) {
            super(status + " " + code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    Booked book(Student owner, int roomId, Instant startAt, Instant endAt, List<String> participants, String key);

    Booked startNow(Student owner, int roomId, Instant endAt, List<String> participants, String key);

    void cancel(Student owner, long reservationId);

    /** 문 앞 QR. 열리면 true, 거절(아직 아님·틀림)이면 false */
    boolean checkIn(int roomId, String accessCode, String eventId);

    void checkout(Student owner, long reservationId);

    Booked extend(Student owner, long reservationId, Instant endAt, String key);

    /** 이 학생이 대표자인 끝나지 않은 예약(BOOKED·IN_USE). 다시 켜졌을 때 이어 가려고 */
    List<Booked> active(Student owner);

    /** 방마다 지금 상태 */
    Map<Integer, RoomNow> rooms();
}
