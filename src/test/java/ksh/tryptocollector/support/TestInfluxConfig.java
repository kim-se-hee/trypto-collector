package ksh.tryptocollector.support;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 테스트에서 InfluxDB 빈을 전부 Mockito 목 객체로 대체한다.
 * 메인 {@code InfluxDBConfig}는 test 프로파일에서 비활성화된다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfluxConfig {

    @Bean
    public InfluxDBClient influxDBClient() {
        return Mockito.mock(InfluxDBClient.class);
    }

    @Bean
    public WriteApiBlocking writeApiBlocking() {
        return Mockito.mock(WriteApiBlocking.class);
    }

    @Bean
    public QueryApi queryApi() {
        return Mockito.mock(QueryApi.class);
    }
}
