package kr.ac.chosun.mspace.climate.appliance;

/** 기기 종류. 브랜드·조작 방식(가상·적외선·플러그)은 종류가 아니라 어댑터가 가른다 */
public enum ApplianceKind {
    /** 에어컨(냉방·난방·제습) */
    AIRCON,
    /** 공기청정기. CO2는 못 낮춘다(먼지·냄새만) */
    PURIFIER,
    /** 환풍기·서큘레이터. CO2를 낮추는 건 환기뿐이다 */
    VENTILATOR
}
