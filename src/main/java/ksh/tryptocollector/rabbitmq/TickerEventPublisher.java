package ksh.tryptocollector.rabbitmq;

import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.model.TickerEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class TickerEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public TickerEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(NormalizedTicker ticker) {
        try {
            TickerEvent event = TickerEvent.from(ticker);
            byte[] body;
            try {
                body = objectMapper.writeValueAsBytes(event);
            } catch (JacksonException e) {
                log.error("시세 이벤트 직렬화 실패: {}", event, e);
                return;
            }
            Message message = MessageBuilder.withBody(body)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .build();
            rabbitTemplate.send(RabbitMQConfig.TICKER_EXCHANGE, "", message);
        } catch (Exception e) {
            log.error("시세 이벤트 발행 실패: {}/{}", ticker.exchange(),
                    ticker.base() + "/" + ticker.quote(), e);
        }
    }
}
