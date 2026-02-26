package ksh.tryptocollector.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RealtimePriceCollector {

    public void connectUpbit() {
        log.info("업비트 WebSocket 연결 — 핸들러 미구현");
    }

    public void connectBithumb() {
        log.info("빗썸 WebSocket 연결 — 핸들러 미구현");
    }

    public void connectBinance() {
        log.info("바이낸스 WebSocket 연결 — 핸들러 미구현");
    }
}
