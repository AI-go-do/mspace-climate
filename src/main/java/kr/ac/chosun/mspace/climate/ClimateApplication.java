package kr.ac.chosun.mspace.climate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 방 공기 이상 탐지·냉난방 자동 제어 서비스 (오디 공기 관리) */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ClimateApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClimateApplication.class, args);
    }
}
