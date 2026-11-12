package kr.ac.chosun.mspace.climate.domain;

import java.time.Instant;

/** 1분 값 하나 */
public record Sample(Instant at, double value) {
}
