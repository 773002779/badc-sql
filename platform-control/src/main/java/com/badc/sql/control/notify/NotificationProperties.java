package com.badc.sql.control.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 通知渠道与重试的配置口径（{@code badcsql.notify.*}）。
 *
 * <p>对应配置键：
 * <ul>
 *   <li>{@code badcsql.notify.wecom.enabled}、{@code corp-id}、{@code agent-id}、{@code secret}、
 *       {@code base-url}——企微应用消息；{@code base-url} 默认企微官方地址，测试指向本机桩；</li>
 *   <li>{@code badcsql.notify.mail.enabled}、{@code from}——邮件渠道；</li>
 *   <li>{@code badcsql.notify.retry.max-attempts=3}、{@code base-backoff-millis=500}——投递重试口径；</li>
 *   <li>{@code badcsql.notify.link-base}——消息里"短链"的基地址（控制台前端，R36）。<b>默认空</b>：
 *       空表示不填 {@code link} 这个键，而不是发一个点不开的相对路径；配错会给出死链，
 *       所以只接受显式配置，绝不猜一个域名。</li>
 * </ul>
 *
 * <p><b>真实密钥一律不写进 {@code application.yml}</b>：{@code secret} / 邮件口令走环境变量或部署期注入，
 * 配置文件里只放空默认值。默认全部渠道关闭（{@code enabled=false}），没配置就谁也不发，
 * 而不是拿半截配置去连企微/SMTP 然后刷一屏失败。
 *
 * <p>绑定方式与 {@code RiskProperties} 相同：{@code @EnableConfigurationProperties(NotificationProperties.class)}
 * 注册，record 走构造器绑定。因此<b>本类型只能有一个构造器</b>——多一个（例如给单测用的无参构造器）
 * 会让 Boot 退化成 JavaBean 绑定，没有 setter 的 record 一个字段也绑不上，配置静默失效。
 * 单测要默认值走 {@link #defaults()}。
 *
 * <p>嵌套组在 {@code badcsql.notify} 整段缺省时可能被绑成 {@code null}，而 {@code NotificationProperties}
 * 的紧凑构造器会把 {@code null} 换成带默认值的实例：否则 {@code isConfigured()} 第一次被调用就 NPE。
 * 组内字段用 {@code @DefaultValue} 兜住"只配了组内一项"的情况。
 */
@ConfigurationProperties(prefix = "badcsql.notify")
public record NotificationProperties(Wecom wecom, Mail mail, Retry retry,
        @DefaultValue("") String linkBase) {

    /** 企微 API 默认地址；测试通过 {@code base-url} 指向本机桩。 */
    public static final String DEFAULT_WECOM_BASE_URL = "https://qyapi.weixin.qq.com";

    /** 默认最多尝试 3 次（首次 + 2 次重试）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 默认首次退避 500ms，之后按 {@code base * 2^(n-1)} 递增。 */
    public static final int DEFAULT_BASE_BACKOFF_MILLIS = 500;

    /** 短链基地址的默认值：<b>空</b>。空表示"不填 link 键"，而不是填一个打不开的路径。 */
    public static final String DEFAULT_LINK_BASE = "";

    /** 嵌套组缺省时换成默认实例，绝不让 sender 拿到 {@code null} 组；短链基地址空串归一。 */
    public NotificationProperties {
        wecom = wecom != null ? wecom : Wecom.defaults();
        mail = mail != null ? mail : Mail.defaults();
        retry = retry != null ? retry : Retry.defaults();
        linkBase = linkBase == null ? DEFAULT_LINK_BASE : linkBase.strip();
    }

    /** 全默认口径：三个渠道全关，重试 3 次 / 500ms，短链基地址为空（不生成 link）。 */
    public static NotificationProperties defaults() {
        return new NotificationProperties(Wecom.defaults(), Mail.defaults(), Retry.defaults(), DEFAULT_LINK_BASE);
    }

    /**
     * 企业微信应用消息。
     *
     * @param enabled 是否启用；false 时该渠道记 SKIPPED，不尝试连接
     * @param corpId  企业 ID
     * @param agentId 应用 agentid
     * @param secret  应用 secret（不要写进仓库配置）
     * @param baseUrl 企微 API 根地址（测试指向本机桩）
     */
    public record Wecom(
            boolean enabled,
            @DefaultValue("") String corpId,
            @DefaultValue("0") long agentId,
            @DefaultValue("") String secret,
            @DefaultValue(DEFAULT_WECOM_BASE_URL) String baseUrl) {

        /** 关闭状态 + 空凭证 + 官方地址。 */
        public static Wecom defaults() {
            return new Wecom(false, "", 0L, "", DEFAULT_WECOM_BASE_URL);
        }
    }

    /**
     * 邮件。
     *
     * @param enabled 是否启用；false 时该渠道记 SKIPPED，不碰 SMTP
     * @param from    发件人地址
     */
    public record Mail(boolean enabled, @DefaultValue("") String from) {

        /** 关闭状态 + 无发件人。 */
        public static Mail defaults() {
            return new Mail(false, "");
        }
    }

    /**
     * 投递重试口径（Task 5 使用）。
     *
     * @param maxAttempts       最多尝试次数（含首次）
     * @param baseBackoffMillis 首次退避毫秒数，第 n 次退避为 {@code base * 2^(n-1)}
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("500") int baseBackoffMillis) {

        /** 3 次 / 500ms。 */
        public static Retry defaults() {
            return new Retry(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF_MILLIS);
        }
    }
}
