package com.joinsoft.common.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * 日期工具，输出格式与原项目保持一致（yyyy-MM-dd HH:mm:ss）。
 */
public final class DateUtils {

    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    private DateUtils() {
    }

    public static String format(Date date) {
        return date == null ? null : new SimpleDateFormat(PATTERN).format(date);
    }

    public static Date now() {
        return new Date();
    }

    public static Date plusMinutes(Date date, int minutes) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.MINUTE, minutes);
        return calendar.getTime();
    }

    public static Long betweenMillis(Date start, Date end) {
        if (start == null || end == null) {
            return null;
        }
        return end.getTime() - start.getTime();
    }
}
