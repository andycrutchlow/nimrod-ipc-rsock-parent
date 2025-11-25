package com.nimrodtechs.rsock.test.common;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;
import com.nimrodtechs.rsock.test.model.MarketData;
import com.nimrodtechs.rsock.test.model.MarketDataRequest;

import java.util.List;
import java.util.Map;

@NimrodRmiInterface(serviceName = "server1")
public interface MarketDataRmiInterface {
    public MarketData getMarketData(String stock) throws Exception;

    public List<MarketData> getMarketDataList(int count) throws Exception;

    public Map<String,MarketData> getMarketDataMap(MarketDataRequest request) throws Exception;

}
