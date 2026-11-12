package kr.ac.chosun.mspace.climate.application;

import java.util.List;

/**
 * 판단 에이전트 포트 (재웅 11/12 "실제로 판단"). 새 이상이 생긴 방 하나를 맡겨 켤 기기를 고르게 한다.
 * 규칙(ClimateAgent)은 눈·계산기·안전망이고, 이 포트 뒤의 모델은 "무엇을 켤지와 왜"만 고른다.
 * 결과는 비동기로 Listener에 온다. 안 되면(키 없음·한도·시간 초과·오류) fallback으로 규칙이 대신 켠다.
 */
public interface Judge {

    /** 지금 맡길 수 있는지(키가 있는지). false면 규칙만으로 켠다 */
    boolean enabled();

    /** 방 하나를 맡긴다. 한도를 넘었으면 바로 listener.fallback */
    void ask(Request request, Listener listener);

    /** 맡길 일: 방 번호·이름과 그 방에서 새로 연 판단들(탐지·예측 문장) */
    record Request(int roomId, String roomName, List<String> decisionIds, List<String> findings) {
    }

    interface Listener {
        /** 도구를 하나 부를 때마다 (화면의 "제어" 단계를 "…하는 중"으로) */
        void progress(Request r, String text);

        /** 끝남. did = 실제로 바꾼 기기("환풍기 켬" 등, 없으면 빈 목록), reason = 모델이 말한 이유 한 문장 */
        void done(Request r, List<String> did, String reason);

        /** 맡기지 못함. 규칙이 대신 켠다 */
        void fallback(Request r, String why);
    }
}
