package ksh.tryptocollector.collector;

import ksh.tryptocollector.client.websocket.BithumbWebSocketHandler;
import ksh.tryptocollector.client.websocket.UpbitWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimePriceCollector {

    private final UpbitWebSocketHandler upbitWebSocketHandler;
    private final BithumbWebSocketHandler bithumbWebSocketHandler;

    public void connectUpbit() {
        upbitWebSocketHandler.connect().subscribe();
        log.info("업비트 WebSocket 연결 시작");
    }

    public void connectBithumb() {
        bithumbWebSocketHandler.connect().subscribe();
        log.info("빗썸 WebSocket 연결 시작");
    }

    public void connectBinance() {
        log.info("바이낸스 WebSocket 연결 — 핸들러 미구현");
    }
}
