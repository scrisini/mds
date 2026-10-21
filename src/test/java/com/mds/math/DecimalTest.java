package com.mds.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class DecimalTest {

    // precision is JVM-global; other test classes may already have locked in the defaults
    @BeforeEach
    @AfterEach
    void resetPrecision() {
        Decimal.resetForTests();
    }

    @Test
    void usesDefaultScaleWhenNotConfigured() {
        assertThat(Decimal.scale()).isEqualTo(8);
        assertThat(Decimal.parse("1.123456789")).hasToString("1.12345679");
    }

    @ParameterizedTest
    @CsvSource({
            "83402.5, 83402.5",
            "12, 12",
            "-0.001, -0.001",
            "0.00000001, 0.00000001",
            "1.10, 1.1",
            "0, 0"})
    void formatsPlainWithoutTrailingZeros(String input, String expected) {
        assertThat(Decimal.parse(input)).hasToString(expected);
    }

    @Test
    void arithmetic() {
        Decimal a = Decimal.parse("83402.12");
        Decimal b = Decimal.parse("0.5");

        assertThat(a.add(b)).isEqualTo(Decimal.parse("83402.62"));
        assertThat(a.subtract(b)).isEqualTo(Decimal.parse("83401.62"));
        assertThat(a.multiply(b)).isEqualTo(Decimal.parse("41701.06"));
        assertThat(a.divide(b)).isEqualTo(Decimal.parse("166804.24"));
        assertThat(Decimal.of(10).divide(3)).isEqualTo(Decimal.parse("3.33333333"));
        assertThat(a.negate().abs()).isEqualTo(a);
        assertThat(a.max(b)).isSameAs(a);
    }

    @Test
    void handlesProductsWiderThan64Bits() {
        // 90,000 * 1,000,000 = 9e10 fits at scale 8, but its units product (9e12 * 1e14) needs 128 bits
        Decimal price = Decimal.parse("90000");
        Decimal volume = Decimal.parse("1000000");

        assertThat(price.multiply(volume)).isEqualTo(Decimal.parse("90000000000"));
        assertThat(Decimal.parse("90000000000").divide(Decimal.parse("1000000"))).isEqualTo(price);
    }

    @Test
    void rejectsValuesOutsideRange() {
        assertThatThrownBy(() -> Decimal.parse("100000000000")).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Decimal.max().add(Decimal.ofUnits(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Decimal.of(1).divide(Decimal.ZERO)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void honoursConfiguredScaleAndRounding() {
        Decimal.configure(2, RoundingMode.DOWN);

        assertThat(Decimal.parse("1.239")).hasToString("1.23");
        assertThat(Decimal.of(2).divide(3)).hasToString("0.66");
        assertThat(Decimal.parse("1.5").toBigDecimal()).isEqualTo(new BigDecimal("1.50"));
    }

    @Test
    void precisionCannotChangeOnceFixed() {
        Decimal.configure(4, RoundingMode.HALF_UP);
        Decimal.configure(4, RoundingMode.HALF_UP); // same settings are fine

        assertThatIllegalStateException().isThrownBy(() -> Decimal.configure(6, RoundingMode.HALF_UP));
    }

    @ParameterizedTest
    @EnumSource(value = RoundingMode.class, mode = EnumSource.Mode.EXCLUDE, names = "UNNECESSARY")
    void longRoundingMatchesBigDecimal(RoundingMode mode) {
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            long numerator = random.nextLong() >> random.nextInt(40);
            long denominator = (random.nextLong() >> random.nextInt(60)) | 1;
            long expected = BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), 0, mode).longValueExact();
            assertThat(Decimal.divideRounded(numerator, denominator, mode))
                    .as("%d / %d %s", numerator, denominator, mode)
                    .isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RoundingMode.class, mode = EnumSource.Mode.EXCLUDE, names = "UNNECESSARY")
    void wideMultiplyDivideMatchesBigInteger(RoundingMode mode) {
        Random random = new Random(7);
        int checked = 0;
        while (checked < 20_000) {
            long a = random.nextLong() >> random.nextInt(63);
            long b = random.nextLong() >> random.nextInt(63);
            long d = (random.nextLong() >> random.nextInt(63));
            if (d == 0) {
                continue;
            }
            BigDecimal exact = new BigDecimal(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)))
                    .divide(BigDecimal.valueOf(d), 0, mode);
            boolean fits = exact.toBigIntegerExact().bitLength() < 64;
            if (fits) {
                assertThat(Decimal.multiplyDivide(a, b, d, mode))
                        .as("%d * %d / %d %s", a, b, d, mode)
                        .isEqualTo(exact.longValueExact());
            } else {
                assertThatThrownBy(() -> Decimal.multiplyDivide(a, b, d, mode))
                        .as("%d * %d / %d %s overflows", a, b, d, mode)
                        .isInstanceOf(ArithmeticException.class);
            }
            checked++;
        }
    }

    @Test
    void multiplyAndDivideAgreeWithBigDecimalAtScale() {
        Random random = new Random(3);
        for (int i = 0; i < 20_000; i++) {
            Decimal price = Decimal.ofUnits(random.nextLong(1, 20_000_000_000_000L));   // up to 200k
            Decimal volume = Decimal.ofUnits(random.nextLong(1, 100_000_000_000_000L)); // up to 1M
            BigDecimal expected = price.toBigDecimal().multiply(volume.toBigDecimal());
            if (expected.compareTo(Decimal.max().toBigDecimal()) > 0) {
                continue;
            }
            assertThat(price.multiply(volume).toBigDecimal())
                    .isEqualTo(expected.setScale(8, RoundingMode.HALF_EVEN));
            assertThat(price.divide(volume).toBigDecimal())
                    .isEqualTo(price.toBigDecimal().divide(volume.toBigDecimal(), 8, RoundingMode.HALF_EVEN));
        }
    }
}
