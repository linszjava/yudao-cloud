package cn.iocoder.yudao.gateway.jackson;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer;
import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeDeserializer;
import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Gateway 的 Jackson 统一配置
 *
 * <p>统一 Gateway 中所有 JSON 序列化/反序列化的规则，解决以下问题：
 * <ul>
 *     <li>1. Long 精度丢失：JS 的 Number.MAX_SAFE_INTEGER 为 2^53-1，而 Java Long 最大为 2^63-1。
 *         雪花 ID 通常超过 JS 安全范围，因此序列化时将 Long → String，避免前端精度丢失。</li>
 *     <li>2. LocalDateTime 时间格式统一：统一使用 epoch 毫秒时间戳传输，而非 ISO 字符串</li>
 *     <li>3. JsonUtils 静态工具类初始化：将定制过的 ObjectMapper 注入到 JsonUtils，
 *         保证代码中调用 JsonUtils.toJsonString() 时也遵循同样的规则</li>
 *     <li>4. WebFlux 编解码器统一：Gateway 基于 WebFlux，其编解码器体系默认不使用 Spring Boot 的 ObjectMapper，
 *         此处强制 WebFlux 的 JSON 编解码器也使用同一个定制过的 ObjectMapper</li>
 * </ul>
 *
 * @author linsz
 */
@Configuration
@Slf4j
public class GatewayJacksonAutoConfiguration {

    /**
     * 从 Builder 源头定制 ObjectMapper 的序列化/反序列化规则
     *
     * <p>使用 serializerByType / deserializerByType 而非 addSerializer，
     * 避免 Jackson 的 handledType() 校验问题
     *
     * <p>规则说明：
     * <ul>
     *     <li>Long/long → 序列化为字符串数字，防止前端 JS 精度丢失</li>
     *     <li>LocalDate → 标准日期格式（yyyy-MM-dd）</li>
     *     <li>LocalTime → 标准时间格式（HH:mm:ss）</li>
     *     <li>LocalDateTime → epoch 毫秒时间戳（序列化和反序列化双向支持）</li>
     * </ul>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer ldtEpochMillisCustomizer() {
        return builder -> builder
                // Long -> String（解决雪花 ID 精度丢失）
                .serializerByType(Long.class, NumberSerializer.INSTANCE)
                .serializerByType(Long.TYPE, NumberSerializer.INSTANCE)
                // LocalDate / LocalTime（标准格式）
                .serializerByType(LocalDate.class, LocalDateSerializer.INSTANCE)
                .deserializerByType(LocalDate.class, LocalDateDeserializer.INSTANCE)
                .serializerByType(LocalTime.class, LocalTimeSerializer.INSTANCE)
                .deserializerByType(LocalTime.class, LocalTimeDeserializer.INSTANCE)
                // LocalDateTime < - > EpochMillis
                .serializerByType(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE)
                .deserializerByType(LocalDateTime.class, TimestampLocalDateTimeDeserializer.INSTANCE);
    }

    /**
     * 以 Jackson Module Bean 形式暴露序列化规则
     *
     * <p>Spring Boot 会自动将该 Module 注册到所有 ObjectMapper 实例中，
     * 作为 Builder Customizer 的补充，确保所有途径创建的 ObjectMapper 都具备统一规则
     */
    @Bean
    public Module timestampSupportModuleBean() {
        SimpleModule m = new SimpleModule("TimestampSupportModule");
        // Long -> String（解决雪花 ID 精度丢失）
        m.addSerializer(Long.class, NumberSerializer.INSTANCE);
        m.addSerializer(Long.TYPE, NumberSerializer.INSTANCE);
        // LocalDate / LocalTime（标准格式）
        m.addSerializer(LocalDate.class, LocalDateSerializer.INSTANCE);
        m.addDeserializer(LocalDate.class, LocalDateDeserializer.INSTANCE);
        m.addSerializer(LocalTime.class, LocalTimeSerializer.INSTANCE);
        m.addDeserializer(LocalTime.class, LocalTimeDeserializer.INSTANCE);
        // LocalDateTime < - > EpochMillis
        m.addSerializer(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE);
        m.addDeserializer(LocalDateTime.class, TimestampLocalDateTimeDeserializer.INSTANCE);
        return m;
    }

    /**
     * 初始化全局 JsonUtils 静态工具类
     *
     * <p>将 Spring Boot 创建的主 ObjectMapper（已包含所有定制规则）注入到 JsonUtils 中，
     * 使得代码中通过 JsonUtils.toJsonString() / JsonUtils.parseObject() 调用时，
     * 也遵循同样的序列化/反序列化规则
     */
    @Bean
    @SuppressWarnings("InstantiationOfUtilityClass")
    public JsonUtils jsonUtils(ObjectMapper objectMapper) {
        JsonUtils.init(objectMapper);
        log.debug("[init][初始化 JsonUtils 成功]");
        return new JsonUtils();
    }

    /**
     * 统一 WebFlux 的 JSON 编解码器
     *
     * <p>Gateway 基于 WebFlux（Netty），其编解码器体系（ServerCodecConfigurer）
     * 默认会创建独立的 ObjectMapper，不会自动使用 Spring Boot 定制过的 ObjectMapper。
     * 此处强制覆盖默认编解码器，确保 WebFlux 处理请求/响应时的 JSON 行为与全局一致
     */
    @Bean
    public CodecCustomizer unifyJackson(ObjectMapper om) {
        return configurer -> {
            Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(om);
            Jackson2JsonEncoder encoder = new Jackson2JsonEncoder(om);
            configurer.defaultCodecs().jackson2JsonDecoder(decoder);
            configurer.defaultCodecs().jackson2JsonEncoder(encoder);
        };
    }
}
