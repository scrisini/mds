package com.mds.math;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Immutable fixed-point decimal backed by a {@code long} count of units of {@code 10^-scale}.
 *
 * <p>All instances share one scale and rounding mode, fixed once per JVM by
 * {@link #configure(int, RoundingMode)} (wired to {@code mds.decimal.*}); results of every
 * operation are rounded to that scale. Arithmetic stays on primitive longs: products are formed
 * exactly in 128 bits and divided back down with a single rounding step, so results equal exact
 * arithmetic rounded once, without allocating a {@link BigDecimal}. Representable
 * range is {@code ±Long.MAX_VALUE / 10^scale}, e.g. about ±92 billion at scale 8; operations
 * whose result leaves that range throw {@link ArithmeticException}.
 *
 * <p>{@code BigDecimal} only appears at the edges: {@link #of(BigDecimal)} for values coming
 * from XChange, and {@link #toBigDecimal()}.
 */
@JsonSerialize(using = DecimalJsonSerializer.class)
public final class Decimal implements Comparable<Decimal> {

    public static final int MAX_SCALE = 18;
    public static final int DEFAULT_SCALE = 8;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private static final long[] POW10 = new long[MAX_SCALE + 1];

    static {
        POW10[0] = 1;
        for (int i = 1; i <= MAX_SCALE; i++) {
            POW10[i] = POW10[i - 1] * 10;
        }
    }

    private record Settings(int scale, long factor, RoundingMode rounding) {
    }

    private static volatile Settings settings;

    /** Zero is the same at every scale, so it is safe to create before configuration. */
    public static final Decimal ZERO = new Decimal(0);

    private final long units;

    private Decimal(long units) {
        this.units = units;
    }

    // ---- configuration ----

    /**
     * Fixes the scale and rounding mode for the lifetime of the JVM. Must run before the first
     * non-zero value is created; calling it again with different settings, or after defaults
     * were locked in by first use, throws {@link IllegalStateException}.
     */
    public static synchronized void configure(int scale, RoundingMode rounding) {
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be between 0 and " + MAX_SCALE + ", was " + scale);
        }
        Objects.requireNonNull(rounding, "rounding");
        Settings requested = new Settings(scale, POW10[scale], rounding);
        if (settings == null) {
            settings = requested;
        } else if (!settings.equals(requested)) {
            throw new IllegalStateException("Decimal precision is already fixed at scale %d, %s"
                    .formatted(settings.scale(), settings.rounding()));
        }
    }

    public static int scale() {
        return settings().scale();
    }

    public static RoundingMode rounding() {
        return settings().rounding();
    }

    /** Largest representable value at the configured scale. */
    public static Decimal max() {
        return new Decimal(Long.MAX_VALUE);
    }

    private static Settings settings() {
        Settings s = settings;
        if (s == null) {
            synchronized (Decimal.class) {
                if (settings == null) {
                    settings = new Settings(DEFAULT_SCALE, POW10[DEFAULT_SCALE], DEFAULT_ROUNDING);
                }
                s = settings;
            }
        }
        return s;
    }

    /** Test hook: forget the configured settings. Never call while values are live. */
    static synchronized void resetForTests() {
        settings = null;
    }

    // ---- factories ----

    public static Decimal of(long whole) {
        return new Decimal(Math.multiplyExact(whole, settings().factor()));
    }

    /** Value from raw units of {@code 10^-scale}. */
    public static Decimal ofUnits(long units) {
        return new Decimal(units);
    }

    /** Rounds to the configured scale; throws {@link ArithmeticException} if out of range. */
    public static Decimal of(BigDecimal value) {
        Settings s = settings();
        int shift = s.scale() - value.scale();
        // fast path: value already has at most our scale and its digits fit in a long
        if (shift >= 0 && shift <= MAX_SCALE && value.precision() <= 18) {
            return new Decimal(Math.multiplyExact(value.unscaledValue().longValue(), POW10[shift]));
        }
        return new Decimal(value.setScale(s.scale(), s.rounding()).unscaledValue().longValueExact());
    }

    /**
     * Nearest value at the configured scale. Doubles carry ~15-17 significant digits, so only
     * use this for values that were stored as doubles, such as InfluxDB fields.
     */
    public static Decimal ofDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Not a finite number: " + value);
        }
        return of(BigDecimal.valueOf(value));
    }

    /** {@link #of(BigDecimal)}, mapping null to null. */
    public static Decimal ofNullable(BigDecimal value) {
        return value == null ? null : of(value);
    }

    public static Decimal parse(String value) {
        return of(new BigDecimal(value));
    }

    // ---- arithmetic ----

    public Decimal add(Decimal other) {
        return new Decimal(Math.addExact(units, other.units));
    }

    public Decimal subtract(Decimal other) {
        return new Decimal(Math.subtractExact(units, other.units));
    }

    public Decimal multiply(Decimal other) {
        Settings s = settings();
        return new Decimal(multiplyDivide(units, other.units, s.factor(), s.rounding()));
    }

    public Decimal multiply(long factor) {
        return new Decimal(Math.multiplyExact(units, factor));
    }

    public Decimal divide(Decimal divisor) {
        if (divisor.units == 0) {
            throw new ArithmeticException("Division by zero");
        }
        Settings s = settings();
        return new Decimal(multiplyDivide(units, s.factor(), divisor.units, s.rounding()));
    }

    public Decimal divide(long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return new Decimal(divideRounded(units, divisor, settings().rounding()));
    }

    public Decimal negate() {
        return new Decimal(Math.negateExact(units));
    }

    public Decimal abs() {
        return units < 0 ? negate() : this;
    }

    public Decimal min(Decimal other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Decimal max(Decimal other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public int signum() {
        return Long.signum(units);
    }

    public boolean isZero() {
        return units == 0;
    }

    // ---- conversion ----

    public long units() {
        return units;
    }

    public double doubleValue() {
        return units / (double) settings().factor();
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(units, settings().scale());
    }

    /** Plain notation without trailing zeros, e.g. {@code 83402.5}, {@code -0.001}, {@code 12}. */
    @Override
    public String toString() {
        Settings s = settings();
        if (units == Long.MIN_VALUE) {
            return toBigDecimal().stripTrailingZeros().toPlainString();
        }
        long abs = Math.abs(units);
        long whole = abs / s.factor();
        long fraction = abs % s.factor();
        StringBuilder out = new StringBuilder(24);
        if (units < 0) {
            out.append('-');
        }
        out.append(whole);
        if (fraction != 0) {
            String digits = Long.toString(fraction);
            int end = digits.length();
            while (digits.charAt(end - 1) == '0') {
                end--;
            }
            out.append('.');
            for (int i = digits.length(); i < s.scale(); i++) {
                out.append('0');
            }
            out.append(digits, 0, end);
        }
        return out.toString();
    }

    @Override
    public int compareTo(Decimal other) {
        return Long.compare(units, other.units);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Decimal other && units == other.units;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(units);
    }

    /** {@code numerator / denominator} rounded with {@code mode}. */
    static long divideRounded(long numerator, long denominator, RoundingMode mode) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        if (remainder == 0) {
            return quotient;
        }
        int sign = (numerator < 0) == (denominator < 0) ? 1 : -1;
        boolean away = roundAwayFromZero(mode, sign, (quotient & 1) != 0,
                Math.abs(remainder), Math.abs(denominator));
        return away ? quotient + sign : quotient;
    }

    /**
     * {@code a * b / d} computed exactly in 128 bits and rounded once with {@code mode}.
     * Throws {@link ArithmeticException} if the result doesn't fit in a long.
     */
    static long multiplyDivide(long a, long b, long d, RoundingMode mode) {
        if (d == 0) {
            throw new ArithmeticException("Division by zero");
        }
        int sign = Long.signum(a) * Long.signum(b) * Long.signum(d);
        if (sign == 0) {
            return 0;
        }
        // magnitudes as unsigned; Math.abs(Long.MIN_VALUE) is 2^63 read unsigned, which is correct
        long x = Math.abs(a);
        long y = Math.abs(b);
        long v = Math.abs(d);
        long high = Math.unsignedMultiplyHigh(x, y);
        long low = x * y;

        long quotient;
        if (high == 0) {
            quotient = Long.divideUnsigned(low, v);
        } else if (Long.compareUnsigned(high, v) < 0) {
            quotient = divideUnsigned128(high, low, v);
        } else {
            throw new ArithmeticException("Decimal overflow");
        }
        long remainder = low - quotient * v; // exact: the true remainder is < v

        if (remainder != 0 && roundAwayFromZero(mode, sign, (quotient & 1) != 0, remainder, v)) {
            quotient++;
            if (quotient == 0) {
                throw new ArithmeticException("Decimal overflow");
            }
        }
        // quotient is an unsigned magnitude: at most 2^63 - 1 if positive, 2^63 if negative
        if (sign > 0 ? quotient < 0 : Long.compareUnsigned(quotient, Long.MIN_VALUE) > 0) {
            throw new ArithmeticException("Decimal overflow");
        }
        return sign > 0 ? quotient : -quotient;
    }

    /**
     * Whether a truncated quotient with the given sign and parity, and unsigned remainder
     * ({@code 0 < remainder < divisor}), must move one unit away from zero.
     */
    private static boolean roundAwayFromZero(RoundingMode mode, int sign, boolean odd, long remainder,
                                             long divisor) {
        // compare the remainder with half the divisor without overflowing
        int vsHalf = Long.compareUnsigned(remainder, divisor - remainder);
        return switch (mode) {
            case UP -> true;
            case DOWN -> false;
            case CEILING -> sign > 0;
            case FLOOR -> sign < 0;
            case HALF_UP -> vsHalf >= 0;
            case HALF_DOWN -> vsHalf > 0;
            case HALF_EVEN -> vsHalf > 0 || (vsHalf == 0 && odd);
            case UNNECESSARY -> throw new ArithmeticException("Rounding necessary");
        };
    }

    /**
     * Unsigned {@code (high:low) / divisor}; requires {@code high < divisor} (unsigned) so the
     * quotient fits in 64 bits. Knuth's algorithm D on 32-bit digits, after Hacker's Delight
     * {@code divlu}.
     */
    static long divideUnsigned128(long high, long low, long divisor) {
        final long base = 1L << 32;
        final long mask = base - 1;
        int shift = Long.numberOfLeadingZeros(divisor);
        long v = divisor << shift;
        long vn1 = v >>> 32;
        long vn0 = v & mask;
        long un32 = shift == 0 ? high : (high << shift) | (low >>> (64 - shift));
        long un10 = low << shift;
        long un1 = un10 >>> 32;
        long un0 = un10 & mask;

        long q1 = Long.divideUnsigned(un32, vn1);
        long rhat = un32 - q1 * vn1;
        while (Long.compareUnsigned(q1, base) >= 0
                || Long.compareUnsigned(q1 * vn0, (rhat << 32) | un1) > 0) {
            q1--;
            rhat += vn1;
            if (Long.compareUnsigned(rhat, base) >= 0) {
                break;
            }
        }

        long un21 = (un32 << 32) + un1 - q1 * v;
        long q0 = Long.divideUnsigned(un21, vn1);
        rhat = un21 - q0 * vn1;
        while (Long.compareUnsigned(q0, base) >= 0
                || Long.compareUnsigned(q0 * vn0, (rhat << 32) | un0) > 0) {
            q0--;
            rhat += vn1;
            if (Long.compareUnsigned(rhat, base) >= 0) {
                break;
            }
        }
        return (q1 << 32) | q0;
    }
}
