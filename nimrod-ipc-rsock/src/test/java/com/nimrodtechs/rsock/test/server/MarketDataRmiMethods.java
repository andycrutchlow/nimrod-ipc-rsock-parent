package com.nimrodtechs.rsock.test.server;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiService;
import com.nimrodtechs.rsock.test.common.MarketDataRmiInterface;
import com.nimrodtechs.rsock.test.model.MarketData;
import com.nimrodtechs.rsock.test.model.MarketDataRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@NimrodRmiService
public class MarketDataRmiMethods implements MarketDataRmiInterface {

    public static BigDecimal randomBigDecimalInRange(double min, double max, int scale) {
        double random = ThreadLocalRandom.current().nextDouble(min, max);
        return BigDecimal.valueOf(random).setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public MarketData getMarketData(String stock) throws Exception {
        return new MarketData(stock,1,randomBigDecimalInRange(1.00, 1.99, 2));
    }

    @Override
    public List<MarketData> getMarketDataList(int count) throws Exception {
        List<MarketData> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MarketData("stock"+i, 1, randomBigDecimalInRange(1.00, 1.99, 2)));
        }
        return list;
    }

    @Override
    public Map<String, MarketData> getMarketDataMap(MarketDataRequest request) throws Exception {
        MarketData marketData = new MarketData(request.getStock(), 1, randomBigDecimalInRange(1.00, 1.99, 2));
        Map<String, MarketData> marketDataMap = Map.of(
                request.getStock(), marketData
        );
        return marketDataMap;
    }
}
