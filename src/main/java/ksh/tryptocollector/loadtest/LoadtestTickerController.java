package ksh.tryptocollector.loadtest;

import ksh.tryptocollector.exchange.TickerSinkProcessor;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/loadtest")
@RequiredArgsConstructor
public class LoadtestTickerController {

    private final TickerSinkProcessor tickerSinkProcessor;

    @PostMapping("/ticker")
    public ResponseEntity<Void> feedTicker(@RequestBody NormalizedTicker ticker) {
        tickerSinkProcessor.process(ticker, System.nanoTime());
        return ResponseEntity.accepted().build();
    }
}
