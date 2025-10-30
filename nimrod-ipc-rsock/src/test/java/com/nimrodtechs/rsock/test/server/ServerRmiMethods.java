package com.nimrodtechs.rsock.test.server;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmi;
import com.nimrodtechs.ipcrsock.annotations.NimrodRmiService;

@NimrodRmiService
public class ServerRmiMethods {
    @NimrodRmi
    public String testServerRmi(String input) {
        return "hello from andy";
    }
}
