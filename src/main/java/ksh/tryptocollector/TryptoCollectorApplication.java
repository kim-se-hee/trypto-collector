package ksh.tryptocollector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TryptoCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TryptoCollectorApplication.class, args);
    }

}
