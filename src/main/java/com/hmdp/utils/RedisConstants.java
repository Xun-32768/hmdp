package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 600L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    // 秒杀时间窗口，值格式为 "{beginEpochMilli}:{endEpochMilli}"
    public static final String SECKILL_TIME_KEY = "seckill:time:";
    // 秒杀相关 Redis key 的兜底存活时间（分钟），实际以 end_time 为准
    public static final Long SECKILL_KEY_TTL_MINUTES = 24 * 60L;
    // 订单未支付超时关单时间（分钟）
    public static final Long SECKILL_ORDER_TIMEOUT_MINUTES = 15L;
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String BLOG_OUTBOX_KEY = "blog:outbox:";
    public static final String FOLLOW_KEY = "follows:";
    public static final String FAMOUS_FOLLOW_KEY = "famousFollows:";
    public static final String FANS_KEY = "fans:";

    /* ==================== Feed 流：推拉结合 ==================== */
    /** 收件箱（推模式产物）：只保存小博主推来的笔记，仅对活跃用户存在 */
    public static final int FEED_MAX_LEN = 500;
    /** 发件箱（拉模式数据源）：保存大V博主本人最近发布的笔记，大V靠它被粉丝拉取（小V不维护发件箱） */
    public static final int BLOG_OUTBOX_MAX_LEN = 100;
    /** 收件箱滑动 TTL（天），每次写入刷新，长期不活跃自然淘汰 */
    public static final long FEED_INBOX_TTL_DAYS = 7L;
    /** 发件箱 TTL（天） */
    public static final long BLOG_OUTBOX_TTL_DAYS = 30L;
    /** 大V晋升阈值：粉丝数达到该值晋升大V（走拉模式） */
    public static final int FAMOUS_UPGRADE_THRESHOLD = 3500;
    /** 大V降级阈值：粉丝数跌破该值降级为小V（走推模式） */
    public static final int FAMOUS_DOWNGRADE_THRESHOLD = 2500;
    /** 兼容历史常量定义（默认判定界限） */
    public static final int FAMOUS_FAN_THRESHOLD = 3000;
    /** 关注人数上限 */
    public static final long FOLLOW_MAX_LIMIT = 500L;
    /** Feed 滚动分页每页条数 */
    public static final long FEED_PAGE_SIZE = 3L;
    /** 博主粉丝数缓存（String），发博时判定大V/小博主走缓存，避免高频 COUNT 查询 */
    public static final String USER_FANS_KEY = "user:fans:";
    /** 粉丝数缓存 TTL（小时），关注/取关时主动失效 */
    public static final long USER_FANS_TTL_HOURS = 24L;
    /**
     * 冷用户方案B收件箱重建互斥锁（String，SETNX）。
     * 当收件箱不存在时，加分布式互斥锁同步查库双源归并，同时异步回填收件箱，防止高并发打穿 MySQL。
     */
    public static final String FEED_REBUILD_LOCK_KEY = "lock:feed:rebuild:";
    /** 重建互斥锁 TTL（秒） */
    public static final long FEED_REBUILD_LOCK_TTL_SECONDS = 10L;
    /**
     * 收件箱重建标记（String，SETNX 去重）。
     * 收件箱为空时每次刷流都会尝试触发重建，没有该标记会给 MQ 投递大量重复消息。
     */
    public static final String FEED_REBUILD_MARK_KEY = "feed:rebuild:mark:";
    /** 重建触发标记 TTL（分钟）：投递重建消息后短期内不再重复触发 */
    public static final long FEED_REBUILD_TRIGGER_TTL_MINUTES = 1L;
    /** 重建完成后的标记 TTL（分钟）：即使没有可重建的数据也抑制重复触发 */
    public static final long FEED_REBUILD_MARK_TTL_MINUTES = 10L;

    // 密码登录防暴力破解
    public static final String LOGIN_PASSWORD_ERR_KEY = "login:password:err:";
    public static final Long LOGIN_PASSWORD_ERR_TTL = 5L; // 锁定时间（分钟）
    public static final int LOGIN_PASSWORD_MAX_RETRY = 5; // 最大连续输错次数
}
