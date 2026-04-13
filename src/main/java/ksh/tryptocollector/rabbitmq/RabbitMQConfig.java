package ksh.tryptocollector.rabbitmq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String TICKER_EXCHANGE = "ticker.exchange";
    public static final String MATCHED_ORDERS_EXCHANGE = "matched.orders";
    public static final String MATCHED_ORDERS_QUEUE = "matched.orders";
    public static final String MATCHED_ORDERS_ROUTING_KEY = "matched.orders";
    public static final String MATCHED_ORDERS_DLX = "matched.orders.dlx";
    public static final String MATCHED_ORDERS_DLQ = "matched.orders.dlq";

    private static final String X_DELIVERY_LIMIT = "x-delivery-limit";
    private static final int DELIVERY_LIMIT = 2;

    @Bean
    public FanoutExchange tickerExchange() {
        return new FanoutExchange(TICKER_EXCHANGE);
    }

    @Bean
    public DirectExchange matchedOrdersExchange() {
        return new DirectExchange(MATCHED_ORDERS_EXCHANGE);
    }

    @Bean
    public Queue matchedOrdersQueue() {
        return QueueBuilder.durable(MATCHED_ORDERS_QUEUE)
                .quorum()
                .withArgument(X_DELIVERY_LIMIT, DELIVERY_LIMIT)
                .deadLetterExchange(MATCHED_ORDERS_DLX)
                .deadLetterRoutingKey(MATCHED_ORDERS_DLQ)
                .build();
    }

    @Bean
    public Binding matchedOrdersBinding(Queue matchedOrdersQueue, DirectExchange matchedOrdersExchange) {
        return BindingBuilder.bind(matchedOrdersQueue)
                .to(matchedOrdersExchange)
                .with(MATCHED_ORDERS_ROUTING_KEY);
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
