package com.nimrodtechs.rsock.test.server;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiService;
import com.nimrodtechs.rsock.test.common.ServerRmiInterface;

@NimrodRmiService
public class ServerRmiMethods implements ServerRmiInterface {
    public String testServerRmi(String input) throws Exception {
        return "hello from andy";
    }
}
