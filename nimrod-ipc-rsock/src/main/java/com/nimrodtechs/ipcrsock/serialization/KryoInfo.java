package com.nimrodtechs.ipcrsock.serialization;


import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;

public class KryoInfo {

    public static final int KRYO_INITIAL_DATA_SIZE = 64 * 1024;

    public Kryo getKryo() {
        return kryo;
    }

    public Output getOutput() {
        return output;
    }
    public Input getInput() {
        return input;
    }
    public ReusableByteArrayOutputStream getOutputStream() {
        return outputStream;

    }

    private final Kryo kryo;
    private final ReusableByteArrayOutputStream outputStream;
    private final Output output;
    private final Input input;

    KryoInfo(final Kryo kryo,
             final ReusableByteArrayOutputStream outputStream,
             final Output output,
             final Input input) {
        this.kryo = kryo;
        this.outputStream = outputStream;
        this.output = output;
        this.input = input;
    }

}
