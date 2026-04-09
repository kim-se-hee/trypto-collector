package ksh.tryptocollector.matching;

import ksh.tryptocollector.rabbitmq.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchedOrderPublisher {

    private static final long ACK_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public boolean publish(MatchedOrderMessage message) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(message);
        } catch (JacksonException e) {
            log.error("매칭 메시지 직렬화 실패: {}", message, e);
            return false;
        }

        CorrelationData correlationData = new CorrelationData();
        rabbitTemplate.send(
                RabbitMQConfig.MATCHED_ORDERS_EXCHANGE,
                RabbitMQConfig.MATCHED_ORDERS_ROUTING_KEY,
                MessageBuilder.withBody(body)
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build(),
                correlationData);

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm != null && confirm.ack()) {
                return true;
            }
            log.warn("매칭 메시지 NACK: reason={}", confirm != null ? confirm.reason() : "unknown");
            return false;
        } catch (TimeoutException e) {
            log.warn("매칭 메시지 ACK 타임아웃 ({}초)", ACK_TIMEOUT_SECONDS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("매칭 메시지 ACK 대기 중 인터럽트");
            return false;
        } catch (ExecutionException e) {
            log.error("매칭 메시지 ACK 대기 중 오류", e);
            return false;
        }
    }
}
