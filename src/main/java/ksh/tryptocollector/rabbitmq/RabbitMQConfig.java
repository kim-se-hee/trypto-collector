package ksh.tryptocollector.rabbitmq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String TICKER_EXCHANGE = "ticker.exchange";

    @Bean
    public FanoutExchange tickerExchange() {
        return new FanoutExchange(TICKER_EXCHANGE);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.warn("시세 이벤트 발행 nack: {}", cause);
            }
        });
        return template;
    }
}
