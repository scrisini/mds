package com.mds.grpc;

import com.mds.logging.LogEvent;
import com.mds.service.PairNotSubscribedException;
import com.mds.service.PriceUnavailableException;
import com.mds.service.StreamCapacityException;
import com.mds.service.UnknownExchangeException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * Domain exceptions as gRPC statuses, the same mapping {@code ApiExceptionHandler} makes to HTTP:
 * NOT_FOUND, UNAVAILABLE, RESOURCE_EXHAUSTED (the subscriber limit) and INVALID_ARGUMENT. Anything
 * else is logged as {@link LogEvent#GRPC_UNEXPECTED_ERROR} and becomes INTERNAL, without details.
 */
@Slf4j
@Component
class GrpcErrors implements GrpcExceptionHandler {

    @Override
    public StatusException handleException(Throwable exception) {
        Status status = switch (exception) {
            case StatusRuntimeException e -> e.getStatus();
            case StatusException e -> e.getStatus();
            case PairNotSubscribedException e -> Status.NOT_FOUND.withDescription(e.getMessage());
            case UnknownExchangeException e -> Status.NOT_FOUND.withDescription(e.getMessage());
            case PriceUnavailableException e -> Status.UNAVAILABLE.withDescription(e.getMessage());
            case StreamCapacityException e -> Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage());
            case IllegalArgumentException e -> Status.INVALID_ARGUMENT.withDescription(e.getMessage());
            default -> {
                LogEvent.GRPC_UNEXPECTED_ERROR.at(log).cause(exception).log();
                yield Status.INTERNAL.withDescription("internal error; quote the x-request-id to support");
            }
        };
        return status.asException();
    }
}
