package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "D:\\nginx\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    /**
     * 业务时区。
     * <p>数据库里的 {@code begin_time}/{@code end_time} 是「不带时区的字面量」，
     * 必须用固定时区解释成 epoch 毫秒，否则 JVM 默认时区一变（例如容器内是 UTC），
     * 整个秒杀活动时间窗口会静默偏移 8 小时。</p>
     */
    public static final ZoneId BIZ_ZONE_ID = ZoneId.of("Asia/Shanghai");

    /** 按业务时区取当前时间，避免依赖 JVM 默认时区 */
    public static LocalDateTime bizNow() {
        return LocalDateTime.now(BIZ_ZONE_ID);
    }
}
