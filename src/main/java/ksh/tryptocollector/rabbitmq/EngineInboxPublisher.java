package ksh.tryptocollector.rabbitmq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;


@Slf4j
@Component
@RequiredArgsConstructor
public class EngineInboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void publish(NormalizedTicker ticker) {
        try {
            Map<String, Object> payload = Map.of(
                "exchange", ticker.exchange(),
                "displayName", ticker.base(),
                "tradePrice", ticker.lastPrice(),
                "tickAt", LocalDateTime.ofInstant(Instant.ofEpochMilli(ticker.tsMs()), ZoneId.systemDefault()).toString()
            );
            byte[] body;
            try {
                body = objectMapper.writeValueAsBytes(payload);
            } catch (JacksonException e) {
                log.error("tick 직렬화 실패: {}", payload, e);
                return;
            }
            Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageProperties.DEFAULT_DELIVERY_MODE)
                .setHeader("event_type", "TickReceived")
                .build();
            rabbitTemplate.send("", RabbitMQConfig.ENGINE_INBOX_QUEUE, message);
            Counter.builder("engine.inbox.tick.publish")
                .tag("exchange", ticker.exchange())
                .register(meterRegistry)
                .increment();
        } catch (Exception e) {
            log.error("engine.inbox 발행 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
    }
}
