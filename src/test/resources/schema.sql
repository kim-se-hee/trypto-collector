CREATE TABLE IF NOT EXISTS exchange_market (
    exchange_id   BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    PRIMARY KEY (exchange_id),
    UNIQUE KEY uk_exchange_market_name (name)
);

CREATE TABLE IF NOT EXISTS coin (
    coin_id  BIGINT       NOT NULL AUTO_INCREMENT,
    symbol   VARCHAR(50)  NOT NULL,
    PRIMARY KEY (coin_id),
    UNIQUE KEY uk_coin_symbol (symbol)
);

CREATE TABLE IF NOT EXISTS exchange_coin (
    exchange_coin_id BIGINT NOT NULL AUTO_INCREMENT,
    exchange_id      BIGINT NOT NULL,
    coin_id          BIGINT NOT NULL,
    PRIMARY KEY (exchange_coin_id),
    UNIQUE KEY uk_exchange_coin (exchange_id, coin_id),
    CONSTRAINT fk_ec_exchange FOREIGN KEY (exchange_id) REFERENCES exchange_market (exchange_id),
    CONSTRAINT fk_ec_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id)
);

CREATE TABLE IF NOT EXISTS orders (
    order_id         BIGINT         NOT NULL AUTO_INCREMENT,
    exchange_coin_id BIGINT         NOT NULL,
    side             VARCHAR(10)    NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    price            DECIMAL(30, 8) NOT NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_ec FOREIGN KEY (exchange_coin_id) REFERENCES exchange_coin (exchange_coin_id)
);
