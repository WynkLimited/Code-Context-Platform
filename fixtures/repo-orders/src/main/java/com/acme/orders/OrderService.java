package com.acme.orders;

import com.acme.common.Money;
import java.math.BigDecimal;

public class OrderService {
    public BigDecimal total(BigDecimal amount, BigDecimal rate) {
        return Money.convert(amount, rate);
    }
}
