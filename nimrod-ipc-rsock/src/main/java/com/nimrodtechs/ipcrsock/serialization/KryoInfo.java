package com.nimrodtechs.ipcrsock.serialization;


import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;

import java.io.ByteArrayOutputStream;

public class KryoInfo {

    public static final int MAX_MESSAGE_SIZE = 1024000;

    public Kryo getKryo() {
        return kryo;
    }

    public Output getOutput() {
        return output;
    }
    public Input getInput() {
        return input;
    }
    public ByteArrayOutputStream getOutputStream() {
        return outputStream;

    }

    private final Kryo kryo;
    private final ByteArrayOutputStream outputStream;
    private final Output output;
    private final Input input;

    KryoInfo(final Kryo kryo,
             final ByteArrayOutputStream outputStream,
             final Output output,
             final Input input) {
        this.kryo = kryo;
        this.outputStream = outputStream;
        this.output = output;
        this.input = input;
    }

}
