package com.nimrodtechs.ipcrsock.serialization;

import java.io.ByteArrayOutputStream;

public final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {

    public ReusableByteArrayOutputStream(int size) {
        super(size);
    }

    public byte[] buffer() {
        return this.buf;
    }

    public int size() {
        return this.count;
    }
}