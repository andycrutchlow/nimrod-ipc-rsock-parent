package com.nimrodtechs.rsock.test.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;


public class MarketData {


    public void setStock(String stock) {
        this.stock = stock;
    }

    public void setCurrentPrice(int currentPrice) {
        this.currentPrice = currentPrice;
    }

    public void setBdPrice(BigDecimal bdPrice) {
        this.bdPrice = bdPrice;
    }

    private String stock;
    private int currentPrice;
    private BigDecimal bdPrice;

    public MarketData() {
    }

    public MarketData(String stock, int currentPrice, BigDecimal bdPrice) {
        this.stock = stock;
        this.currentPrice = currentPrice;
        this.bdPrice = bdPrice;
    }


    public static MarketData fromException(Exception e) {
        MarketData marketData = new MarketData();
        marketData.setStock(e.getMessage());
        return marketData;
    }

    @Override
    public String toString() {
        return "MarketData{" +
                "stock='" + stock + '\'' +
                ", currentPrice=" + currentPrice +
                ", bdPrice=" + bdPrice +
                '}';
    }
}