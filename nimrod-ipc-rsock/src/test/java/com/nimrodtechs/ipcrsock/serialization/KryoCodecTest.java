package com.nimrodtechs.ipcrsock.serialization;

import com.nimrodtechs.ipcrsock.common.PublisherPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Separate sender and receiver pools, each size one, force real wire metadata and state reuse. */
@Timeout(20)
class KryoCodecTest {
    private final KryoEncoder encoder = new KryoEncoder(pool());
    private final KryoDecoder decoder = new KryoDecoder(pool());
    private static final DefaultDataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;
    private static final MimeType MIME = MimeType.valueOf("application/x-kryo");

    public record SecurityFilterRecord(String dvId, String cusip, String isin, String figi,
                                       String universe_code, String industryCode,
                                       String securityTypeCode, String marketSectorCode) {}

    private static KryoCommon pool() { return new KryoCommon(1, new KryoProperties(), List.of()); }
    private static SecurityFilterRecord record(int i) {
        return new SecurityFilterRecord("dv-" + i, "cusip-" + i, "isin-" + i, "figi-" + i,
                "universe-" + i, "industry-" + i, "security-type-" + i, "market-sector-" + i);
    }
    private DataBuffer encode(Object value, Class<?> declared) {
        return encoder.encodeValue(value, BUFFERS, ResolvableType.forClass(declared), MIME, Map.of());
    }
    private Object decode(DataBuffer buffer, Class<?> declared) {
        return decoder.decode(buffer, ResolvableType.forClass(declared), MIME, Map.of());
    }

    @Test void concreteRecordAndArray() {
        assertEquals(record(0), decode(encode(record(0), SecurityFilterRecord.class), SecurityFilterRecord.class));
        var records = new SecurityFilterRecord[]{record(0)};
        assertArrayEquals(records, (Object[]) decode(encode(records, records.getClass()), records.getClass()));
    }

    @ParameterizedTest @ValueSource(ints = {1, 100, 1000, 50000})
    void concreteArrayListCanBeReadAsListInterface(int count) {
        List<SecurityFilterRecord> records = new ArrayList<>(IntStream.range(0, count).mapToObj(KryoCodecTest::record).toList());
        assertEquals(records, decode(encode(records, List.class), List.class));
    }

    @Test void runtimeTypeAndReferencesSurviveAllDecoderEntryPoints() {
        var shared = record(0);
        List<SecurityFilterRecord> records = new ArrayList<>(List.of(shared, shared));
        var type = ResolvableType.forClass(List.class);
        for (int i = 0; i < 3; i++) {
            List<?> decoded = decoder.deserialize(encoder.serialize(records), List.class);
            assertEquals(records, decoded); assertSame(decoded.get(0), decoded.get(1));
            assertEquals(records, decoder.decodeToMono(Mono.just(encode(records, List.class)), type, MIME, Map.of()).block());
            assertEquals(List.of(records), decoder.decode(Flux.just(encode(records, List.class)), type, MIME, Map.of()).collectList().block());
            assertEquals(records, decode(encode(records, Object.class), Object.class));
        }
    }

    @Test void streamingEncoderUsesRuntimeTypeAndRetainsEmittedBuffers() {
        var first = new ArrayList<>(List.of(record(1)));
        var second = new ArrayList<>(List.of(record(2)));
        var third = new ArrayList<>(List.of(record(3)));
        List<DataBuffer> retained = encoder.encode(Flux.just(first, second, third), BUFFERS,
                ResolvableType.forClass(List.class), MIME, Map.of()).collectList().block();
        assertNotNull(retained);
        assertEquals(first, decode(retained.get(0), List.class));
        assertEquals(second, decode(retained.get(1), List.class));
        assertEquals(third, decode(retained.get(2), List.class));
    }

    @Test void incompatibleRequestedTypeIsRejectedAndPoolRemainsReusable() {
        assertThrows(DecodingException.class, () -> decode(encode(new ArrayList<>(List.of(record(0))), List.class), Object[].class));
        assertEquals(record(1), decode(encode(record(1), SecurityFilterRecord.class), SecurityFilterRecord.class));
    }

    @Test void nullRootAndNestedPubSubPayload() {
        assertNull(decoder.deserialize(encoder.serialize(null), Object.class));
        assertNull(decode(encode(null, List.class), List.class));
        var message = new PublisherPayload<>(123L, "security.filters", new ArrayList<>(List.of(record(0))));
        var received = decoder.deserialize(encoder.serialize(message), PublisherPayload.class);
        assertEquals(message.getTimestamp(), received.getTimestamp());
        assertEquals(message.getSubject(), received.getSubject());
        assertEquals(message.getPayload(), received.getPayload());
    }
}
