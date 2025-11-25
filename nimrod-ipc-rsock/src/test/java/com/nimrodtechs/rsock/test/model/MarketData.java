package com.nimrodtechs.rsock.test.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class MarketData {

    private String stock;
    private int currentPrice;
    private BigDecimal bdPrice;

    public static MarketData fromException(Exception e) {
        MarketData marketData = new MarketData();
        marketData.setStock(e.getMessage());
        return marketData;
    }
}