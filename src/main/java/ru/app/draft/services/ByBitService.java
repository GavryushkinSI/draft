package ru.app.draft.services;

import com.alibaba.fastjson.JSON;
import com.bybit.api.client.domain.CategoryType;
import com.bybit.api.client.domain.TradeOrderType;
import com.bybit.api.client.domain.account.AccountType;
import com.bybit.api.client.domain.account.request.AccountDataRequest;
import com.bybit.api.client.domain.institution.LendingDataRequest;
import com.bybit.api.client.domain.position.request.PositionDataRequest;
import com.bybit.api.client.domain.trade.Side;
import com.bybit.api.client.restApi.*;
import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.bybit.api.client.security.HmacSHA256Signer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.protobuf.Timestamp;
import lombok.extern.log4j.Log4j2;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.app.draft.exception.OrderNotExecutedException;
import ru.app.draft.models.*;
import org.springframework.stereotype.Service;
import ru.app.draft.models.Order;
import ru.app.draft.utils.DateUtils;
import ru.tinkoff.piapi.contract.v1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bybit.api.client.constant.Util.generateTransferID;
import static ru.app.draft.store.Store.*;

@SuppressWarnings("ALL")
@Service
@Log4j2
public class ByBitService extends AbstractTradeService {

    private final TelegramBotService telegramBotService;
    private final MarketDataStreamService streamService;
    private final BybitApiTradeRestClient orderRestClient;
    private final BybitApiPositionRestClient positionRestClient;
    private final BybitApiMarketRestClient marketRestClient;
    private final BybitApiLendingRestClient lendingRestClient;

    private final BybitApiAccountRestClient accountClient;

    private final ObjectMapper mapper;

    private static final String PING_DATA = "{\"op\":\"ping\"}";

    public ByBitService(TelegramBotService telegramBotService, MarketDataStreamService streamService, BybitApiTradeRestClient orderRestClient, BybitApiPositionRestClient positionRestClient, BybitApiMarketRestClient marketRestClient, BybitApiLendingRestClient lendingRestClient, BybitApiAccountRestClient accountClient, ObjectMapper mapper) {
        super(telegramBotService, streamService);
        this.telegramBotService = telegramBotService;
        this.streamService = streamService;
        this.orderRestClient = orderRestClient;
        this.positionRestClient = positionRestClient;
        this.marketRestClient = marketRestClient;
        this.lendingRestClient = lendingRestClient;
        this.accountClient = accountClient;
        this.mapper = mapper;
    }


    @Override
    public void sendSignal(Strategy strategy) {
        Map<String, Object> accountMap = getPositionInfo(null);
        //accountMap.get("result").get("list").get(0).get("coin").get(0).get("equity")
        UserCache userCache = USER_STORE.get(strategy.getUserName());
        List<Strategy> strategyList = userCache.getStrategies();
        Strategy changingStrategy = strategyList
                .stream()
                .filter(str -> str.getName().equals(strategy.getName())).findFirst().get();

        if (!changingStrategy.getIsActive()) {
            return;
        }

        BigDecimal position = strategy.getQuantity().subtract(changingStrategy.getCurrentPosition());
        OrderDirection direction;
        BigDecimal executionPrice = null;
        ErrorData errorData = changingStrategy.getErrorData();
        try {
            if (strategy.getDirection().equals("buy")) {
                if (strategy.getQuantity().compareTo(changingStrategy.getCurrentPosition()) <= 0) {
                    throw new OrderNotExecutedException(String.format("Неверный порядок ордеров, либо дублирование ордера: %s, %s!", "покупка", strategy.getQuantity()));
                }
                direction = OrderDirection.ORDER_DIRECTION_BUY;
            } else if (strategy.getDirection().equals("sell")) {
                if (strategy.getQuantity().compareTo(changingStrategy.getCurrentPosition()) >= 0) {
                    throw new OrderNotExecutedException(String.format("Неверный порядок ордеров, либо дублирование ордера: %s, %s!", "продажа", strategy.getQuantity()));
                }
                direction = OrderDirection.ORDER_DIRECTION_SELL;
            } else {
                if (changingStrategy.getCurrentPosition().compareTo(BigDecimal.ZERO) < 0) {
                    direction = OrderDirection.ORDER_DIRECTION_BUY;
                } else {
                    direction = OrderDirection.ORDER_DIRECTION_SELL;
                }
            }

            if (changingStrategy.getConsumer().contains("terminal")) {
                Map<String, Object> result = sendOrder(direction, position.toString(), changingStrategy.getTicker());
                executionPrice = LAST_PRICE.get(changingStrategy.getFigi()).getPrice();

            } else if (changingStrategy.getConsumer().contains("test")) {
                executionPrice = LAST_PRICE.get(changingStrategy.getFigi()).getPrice();
            }
        } catch (Exception e) {
            errorData.setMessage("Ошибка: " + e.getMessage());
            errorData.setTime(DateUtils.getCurrentTime());
            changingStrategy.setErrorData(errorData);
        }

        String time = DateUtils.getCurrentTime();
        updateStrategyCache(strategyList, strategy, changingStrategy, executionPrice, userCache, position, time);
    }

    @Override
    Map<String, Object> getPositionInfo(String ticker) {
        var positionListRequest = PositionDataRequest.builder().category(CategoryType.LINEAR).symbol(ticker).build();
        return (Map<String, Object>) positionRestClient.getPositionInfo(positionListRequest);
    }

    void getTickersInfo() {
        var insProductInfoRequest = LendingDataRequest.builder().build();
        var insProductInfo = lendingRestClient.getInsProductInfo(insProductInfoRequest);
    }

    Map<String, Object> getAccountInfo() {
        var walletBalanceRequest = AccountDataRequest.builder().accountType(AccountType.CONTRACT).coin("USDT").build();
        var walletBalanceData = accountClient.getWalletBalance(walletBalanceRequest);
        return (Map<String, Object>) walletBalanceData;
    }

    LinkedHashMap<String, Object> sendOrder(OrderDirection direction, String quantity, String ticker) {
        var newOrderRequest = TradeOrderRequest.builder()
                .category(CategoryType.LINEAR)
                .symbol(ticker)
                .side(direction == OrderDirection.ORDER_DIRECTION_BUY ? Side.BUY : Side.SELL)
                .orderType(TradeOrderType.MARKET)
                .qty(String.valueOf(Math.abs(Double.parseDouble(quantity))))
                .build();

        var response = (LinkedHashMap<String, Object>) orderRestClient.createOrder(newOrderRequest);
        if (!Objects.equal(response.get("retCode"), 0)) {
            throw new OrderNotExecutedException(String.format("Ошибка исполнения ордера %s, %s, %s", ticker, direction.name(), quantity));
        }
//        try {
//            Thread.sleep(3000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException("Был прерван текущий поток!");
//        }
//        var positionDataRequest = PositionDataRequest.builder().category(CategoryType.LINEAR).symbol(ticker).orderId(((Map<String, Object>) response.get("result")).get("orderId").toString()).build();
//        var result = (LinkedHashMap<String, Object>) positionRestClient.getExecutionList(positionDataRequest);
//        return (LinkedHashMap<String, Object>) ((List) ((LinkedHashMap<String, Object>) result.get("result")).get("list")).get(0);
        return response;
    }

    public Ticker getFigi(List<String> tickers) {
        return TICKERS_BYBIT.get("tickers").stream().filter(i -> i.getValue().equals(tickers.get(0))).findFirst().get();
    }


    public void setStreamPublic() {
        Request request = new Request.Builder().url("wss://stream.bybit.com/v5/public/linear").build();
        OkHttpClient publicClient = new OkHttpClient.Builder().build();
        Map<String, Object> subscribeMsg = new LinkedHashMap<>();
        subscribeMsg.put("op", "subscribe");
        subscribeMsg.put("req_id", generateTransferID());
        subscribeMsg.put("args", List.of("tickers.BTCUSDT", "tickers.ETHUSDT"));
        WebSocket webSocket = publicClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                log.info(t.getMessage());
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                //log.info("BYBIT:{}",text);
                if (text != null) {
                    try {
                        Map<String, Object> result = (Map<String, Object>) JSON.parse(text);
                        String topic = (String) result.get("topic");
                        Timestamp time = getTimeStamp((Long) result.get("ts"));
                        if (topic.contains("tickers")) {
                            String ticker = topic.split("\\.")[1];
                            Map<String, Object> data = ((Map<String, Object>) result.get("data"));
                            //Object bid = data.get("bid1Price");
                            //Object ask = data.get("ask1Price");
                            BigDecimal lastPrice = BigDecimal.valueOf(Double.parseDouble((String) data.get("lastPrice")));
                            updateLastPrice(ticker, lastPrice, time);
                        }
                    } catch (Exception e) {
                        //log.info("error");
                    }
                }
            }

            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                webSocket.send(JSON.toJSONString(subscribeMsg));
                log.info("success_bybit_public_stream");
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                super.onClosed(webSocket, code, reason);
            }
        });
    }

    public void setStreamPrivate() {
        OkHttpClient privateClient = new OkHttpClient.Builder().build();
        Request request = new Request.Builder().url("wss://stream.bybit.com/v5/private?max_alive_time=1000m").build();
        Map<String, Object> subscribeMsg = new LinkedHashMap<>();
        subscribeMsg.clear();
        subscribeMsg.put("op", "subscribe");
        subscribeMsg.put("req_id", generateTransferID());
        subscribeMsg.put("args", List.of("wallet"));
        WebSocket privateWebSocket = privateClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                try {
                    webSocket.send(createAuthMessage());
                    webSocket.send(JSON.toJSONString(subscribeMsg));
                } catch (Exception ex) {

                }
                log.info("success_bybit_private_stream");
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                super.onMessage(webSocket, text);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                super.onClosed(webSocket, code, reason);
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                log.info(t.getMessage());
            }
        });
    }

    private Timestamp getTimeStamp(Long timeInMs) {
        long seconds = timeInMs / 1000;
        long nanos = (timeInMs % 1000) * 1000;
        return Timestamp.newBuilder().setSeconds(seconds).setNanos((int) nanos).build();
    }

    private String createAuthMessage() {
        long expires = Instant.now().toEpochMilli() + 10000;
        String val = "GET/realtime" + expires;
        String signature = HmacSHA256Signer.auth(val, "3t9kEGBf2hrOnz9zz4ukpVdYVJU9tiU2MaPv");

        var args = List.of("0SPHD7IM7JF4iNE5DK", expires, signature);
        var authMap = Map.of("req_id", generateTransferID(), "op", "auth", "args", args);
        return JSON.toJSONString(authMap);
    }
}