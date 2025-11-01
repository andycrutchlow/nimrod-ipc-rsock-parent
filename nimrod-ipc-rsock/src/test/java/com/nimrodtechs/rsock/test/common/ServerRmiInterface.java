package com.nimrodtechs.rsock.test.common;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;

@NimrodRmiInterface(serviceName = "server1")
public interface ServerRmiInterface {
    public String testServerRmi(String input) throws Exception;
}
