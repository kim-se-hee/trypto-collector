package ksh.tryptocollector.tick;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TickRawWriter {
    private static final String MEASUREMENT = "ticker_raw";

    private final WriteApiBlocking writeApiBlocking;

    public void write(NormalizedTicker ticker) {
        Point point = Point.measurement(MEASUREMENT)
                .addTag("exchange", ticker.exchange())
                .addTag("symbol", ticker.base() + "/" + ticker.quote())
                .addField("price", ticker.lastPrice().doubleValue())
                .time(ticker.tsMs(), WritePrecision.MS);
        writeApiBlocking.writePoint(point);
    }
}
