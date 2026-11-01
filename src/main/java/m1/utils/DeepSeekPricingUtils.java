package m1.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * DeepSeek API 高峰计价判断工具类。
 *
 * <p>计费规则（DeepSeek 官方）：
 * <ul>
 *   <li>高峰时段：北京时间周一至周五（非法定节假日）09:00-12:00、14:00-18:00。</li>
 *   <li>空闲时段：周末全天、法定节假日全天、工作日非高峰时段。</li>
 *   <li>边界规则：左闭右开，即 12:00:00、18:00:00 整点已属于空闲时段。</li>
 * </ul>
 *
 * <p>特殊约定：
 * <ul>
 *   <li>周末优先于节假日判断：即使某周六/周日因调休上班，DeepSeek 仍按周末闲时计费。</li>
 *   <li>法定节假日数据无法用标准 Java 库推算，需由调用方通过 {@link HolidayChecker} 注入；
 *       传 {@code null} 表示不做节假日判断。</li>
 * </ul>
 *
 * <p>本类无状态、线程安全。
 */
public final class DeepSeekPricingUtils {

    /** DeepSeek 计费基准时区：北京时间（UTC+8）。所有判断均先换算到该时区。 */
    public static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 上午高峰开始：09:00（含）。 */
    private static final LocalTime MORNING_START = LocalTime.of(9, 0);
    /** 上午高峰结束：12:00（不含）。 */
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    /** 下午高峰开始：14:00（含）。 */
    private static final LocalTime AFTERNOON_START = LocalTime.of(14, 0);
    /** 下午高峰结束：18:00（不含）。 */
    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);

    /** 工具类禁止实例化。 */
    private DeepSeekPricingUtils() {
        throw new AssertionError("No instances.");
    }

    /**
     * 节假日判断器。入参为北京时间日期，返回该日期是否为法定节假日。
     *
     * <p>实现示例：{@code date -> holidaySet.contains(date)}。
     * 传入 {@code null} 等价于"不做节假日判断"。
     */
    @FunctionalInterface
    public interface HolidayChecker {
        /**
         * @param beijingDate 北京时间日期
         * @return {@code true} 表示该日为法定节假日（全天闲时）
         */
        boolean isHoliday(LocalDate beijingDate);
    }

    /**
     * 计价时段枚举。
     */
    public enum Period {
        /** 上午高峰：09:00-12:00。 */
        PEAK_MORNING,
        /** 下午高峰：14:00-18:00。 */
        PEAK_AFTERNOON,
        /** 空闲：周末、节假日、工作日非高峰时段。 */
        OFF_PEAK
    }

    /**
     * 判断给定时刻是否处于高峰计价期。
     *
     * @param dateTime        待判断的时间，工具类内部会自动换算为北京时间
     * @param holidayChecker  节假日判断器，可为 {@code null}（表示不判断节假日）
     * @return {@code true} 表示高峰计价，{@code false} 表示空闲计价
     */
    public static boolean isPeak(LocalDateTime dateTime, HolidayChecker holidayChecker) {
        return getPeriod(dateTime, holidayChecker) != Period.OFF_PEAK;
    }

    /**
     * 判断当前时刻是否处于高峰计价期。
     *
     * @param holidayChecker 节假日判断器，可为 {@code null}
     * @return {@code true} 表示高峰计价
     */
    public static boolean isPeakNow(HolidayChecker holidayChecker) {
        return isPeak(LocalDateTime.now(), holidayChecker);
    }

    /**
     * 获取给定时刻的计价时段。
     *
     * @param dateTime        待判断的时间，工具类内部会自动换算为北京时间
     * @param holidayChecker  节假日判断器，可为 {@code null}
     * @return 计价时段，不会为 {@code null}
     */
    public static Period getPeriod(LocalDateTime dateTime, HolidayChecker holidayChecker) {
        Objects.requireNonNull(dateTime, "dateTime");

        // 统一换算为北京时间。注：dateTime 若由 LocalDateTime.now() 产生，则按其系统默认时区解释，
        // 若调用方明确知道时间所在时区，建议自行转换为 Instant 语义后再传入对应本地时间。
        ZonedDateTime beijing = dateTime.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(BILLING_ZONE);

        LocalDate date = beijing.toLocalDate();
        DayOfWeek dayOfWeek = date.getDayOfWeek();

        // 1. 周末全天闲时（优先级最高，调休上班的周末也按闲时）。
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return Period.OFF_PEAK;
        }

        // 2. 法定节假日全天闲时（holidayChecker 为 null 时跳过）。
        if (holidayChecker != null && holidayChecker.isHoliday(date)) {
            return Period.OFF_PEAK;
        }

        // 3. 工作日高峰时段判断（左闭右开）。
        LocalTime time = beijing.toLocalTime();
        if (!time.isBefore(MORNING_START) && time.isBefore(MORNING_END)) {
            return Period.PEAK_MORNING;
        }
        if (!time.isBefore(AFTERNOON_START) && time.isBefore(AFTERNOON_END)) {
            return Period.PEAK_AFTERNOON;
        }
        return Period.OFF_PEAK;
    }
}