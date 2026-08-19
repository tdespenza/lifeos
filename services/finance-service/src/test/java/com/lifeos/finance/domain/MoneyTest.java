package com.lifeos.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Currency correctness is validated before a value reaches an immutable posting or goal. */
class MoneyTest {

    @Test
    void acceptsOnlySupportedUppercaseIso4217CodesAndPositiveIntegerMinorUnits() {
        assertThat(Money.requireCurrency("USD")).isEqualTo("USD");
        assertThat(Money.requirePositiveMinor(1L, "amountMinor")).isEqualTo(1L);

        assertThatThrownBy(() -> Money.requireCurrency("usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.requireCurrency("ZZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.requirePositiveMinor(0L, "amountMinor"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
