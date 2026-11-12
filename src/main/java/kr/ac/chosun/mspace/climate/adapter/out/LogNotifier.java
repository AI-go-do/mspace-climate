package kr.ac.chosun.mspace.climate.adapter.out;

import kr.ac.chosun.mspace.climate.application.Alert;
import kr.ac.chosun.mspace.climate.application.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 지금 알림 어댑터: 화면(AlertLog)에 보이고 로그에 종류만 남긴다. 학생 알림함은 core 알림 API가 생기면 어댑터를 더한다.
 * 로그에는 방 번호·종류만 (개인정보 없음)
 */
public class LogNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LogNotifier.class);

    @Override
    public void send(Alert a) {
        log.info("알림 방={} 대상={} 지표={} 종류={}", a.roomId(), a.audience(), a.metric(), a.kind());
    }
}
