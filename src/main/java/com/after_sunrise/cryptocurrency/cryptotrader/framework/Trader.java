package com.after_sunrise.cryptocurrency.cryptotrader.framework;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;

import static lombok.AccessLevel.PRIVATE;

/**
 * @author takanori.takase
 * @version 0.0.1
 */
public interface Trader extends Controllable {

    @Getter
    @Builder
    @ToString
    @AllArgsConstructor(access = PRIVATE)
    class Request {

        public static final String ALL = "*";

        private String site;

        private String instrument;

        private Instant currentTime;

        private Instant targetTime;

        private BigDecimal tradingSpread;

        private BigDecimal tradingExposure;

        private BigDecimal tradingSplit;

    }

    void trade();

}
