package ksh.tryptocollector.rabbitmq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String TICKER_EXCHANGE = "ticker.exchange";
    public static final String ENGINE_INBOX_QUEUE = "engine.inbox";

    @Bean
    public FanoutExchange tickerExchange() {
        return new FanoutExchange(TICKER_EXCHANGE);
    }

    @Bean
    public Queue engineInboxQueue() {
        return new Queue(ENGINE_INBOX_QUEUE, true, false, false);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MeterRegistry registry) {
        Counter nackCounter = Counter.builder("rabbitmq.nack.count")
                .description("브로커 메시지 수신 거부 횟수")
                .register(registry);

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                nackCounter.increment();
                log.warn("시세 이벤트 발행 nack: {}", cause);
            }
        });
        return template;
    }
}
