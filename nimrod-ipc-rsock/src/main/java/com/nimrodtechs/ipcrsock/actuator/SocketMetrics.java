package com.nimrodtechs.ipcrsock.actuator;

import com.nimrodtechs.ipcrsock.publisher.PublisherSocketImpl;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Example of usage :
 * Operation	Example URL	Effect
 * Default start (30 s interval)	/actuator/ipcrsock/allocLogOn	Starts background allocator logging every 30 s
 * Custom start (10 s interval)	/actuator/ipcrsock/allocLogOn/10	Starts logging every 10 s
 * Stop	/actuator/ipcrsock/allocLogOff	Stops logging
 * Status	/actuator/ipcrsock/allocLogStatus	Shows current state of logging toggle
 * Publisher log toggle	/actuator/ipcrsock/publishLogOn / /actuator/ipcrsock/publishLogOff
 * Get current state of netty allocs (same as what would be logged)  e.g. http://127.0.0.1:59826/actuator/ipcrsock/allocSnapshot
 */
@Component
@Endpoint(id = "ipcrsock")
public class SocketMetrics {

    private static final long DEFAULT_ALLOCATOR_INTERVAL = 30; // seconds

    @ReadOperation
    public Object operation(@Selector(match = Selector.Match.ALL_REMAINING) String... args) {
        if (args == null || args.length == 0) {
            return "Missing operation name";
        }

        String operationName = args[0];
        String param = args.length > 1 ? args[1] : null;

        switch (operationName) {
            case "publishLogOn":
                return publishLogOn();
            case "publishLogOff":
                return publishLogOff();
            case "allocLogOn":
                return allocatorLogOn(param);
            case "allocLogOff":
                return allocatorLogOff();
            case "allocLogStatus":
                return allocatorLogStatus();
            case "allocSnapshot":
                return allocatorSnapshot();
            default:
                return "Unknown operation: " + operationName;
        }
    }

    private String publishLogOn() {
        PublisherSocketImpl.setLogLevel(1);
        return "Logging publish switched ON";
    }

    private String publishLogOff() {
        PublisherSocketImpl.setLogLevel(0);
        return "Logging publish switched OFF";
    }

    private String allocatorLogOn(String param) {
        long interval = DEFAULT_ALLOCATOR_INTERVAL;
        try {
            if (param != null) {
                interval = Long.parseLong(param);
            }
        } catch (NumberFormatException e) {
            return "Invalid interval: " + param + " (must be integer seconds)";
        }
        return AllocatorHealthCheck.start(interval);
    }

    private String allocatorLogOff() {
        return AllocatorHealthCheck.stop();
    }

    private String allocatorLogStatus() {
        return AllocatorHealthCheck.isRunning()
                ? "AllocatorHealthCheck logging is running"
                : "AllocatorHealthCheck logging is stopped";
    }
    private String allocatorSnapshot() {
        return AllocatorHealthCheck.snapshot();
    }
}