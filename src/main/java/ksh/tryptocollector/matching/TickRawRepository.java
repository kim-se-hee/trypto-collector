package ksh.tryptocollector.matching;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TickRawRepository {

    private final QueryApi queryApi;

    @Value("${influxdb.bucket}")
    private String bucket;

    public Optional<BigDecimal> findFirstMatchingPrice(String exchange, String symbol,
                                                       String side, BigDecimal orderPrice,
                                                       Instant since) {
        String priceCondition = "BUY".equals(side)
                ? "<= " + orderPrice.toPlainString()
                : ">= " + orderPrice.toPlainString();

        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s)
                  |> filter(fn: (r) => r._measurement == "ticker_raw")
                  |> filter(fn: (r) => r.exchange == "%s")
                  |> filter(fn: (r) => r.symbol == "%s")
                  |> filter(fn: (r) => r._field == "price")
                  |> filter(fn: (r) => r._value %s)
                  |> first()
                """, bucket, since, exchange, symbol, priceCondition);

        List<FluxTable> tables = queryApi.query(flux);
        if (tables.isEmpty() || tables.getFirst().getRecords().isEmpty()) {
            return Optional.empty();
        }

        FluxRecord record = tables.getFirst().getRecords().getFirst();
        return Optional.of(BigDecimal.valueOf((double) record.getValue()));
    }
}
