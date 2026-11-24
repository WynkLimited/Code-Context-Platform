package com.acme.common;

import java.math.BigDecimal;

public final class Money {
    private Money() {}
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate);
    }
}
