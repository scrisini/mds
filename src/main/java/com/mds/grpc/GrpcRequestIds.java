package com.mds.grpc;

import com.mds.logging.RequestIds;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * Gives every gRPC call a request id, as {@code RequestIdFilter} does for HTTP: the caller's
 * {@code x-request-id} metadata if it's safe, otherwise a new one. It's returned in the response
 * headers (or the trailers, when the call fails before sending any), and it's in the logging context
 * of each step of the call, so a caller can quote it to support.
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
class GrpcRequestIds implements ServerInterceptor {

    static final Metadata.Key<String> KEY = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String requestId = RequestIds.acceptOrGenerate(headers.get(KEY));
        ServerCall<Q, R> tagged = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            private boolean headersSent;

            @Override
            public void sendHeaders(Metadata responseHeaders) {
                responseHeaders.put(KEY, requestId);
                headersSent = true;
                super.sendHeaders(responseHeaders);
            }

            @Override
            public void close(Status status, Metadata trailers) {
                if (!headersSent) {
                    trailers.put(KEY, requestId);
                }
                super.close(status, trailers);
            }
        };
        ServerCall.Listener<Q> listener;
        try (RequestIds.Scope scope = RequestIds.bind(requestId)) {
            listener = next.startCall(tagged, headers);
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onMessage(Q message) {
                try (RequestIds.Scope scope = RequestIds.bind(requestId)) {
                    super.onMessage(message);
                }
            }

            @Override
            public void onHalfClose() {
                try (RequestIds.Scope scope = RequestIds.bind(requestId)) {
                    super.onHalfClose();
                }
            }

            @Override
            public void onCancel() {
                try (RequestIds.Scope scope = RequestIds.bind(requestId)) {
                    super.onCancel();
                }
            }

            @Override
            public void onComplete() {
                try (RequestIds.Scope scope = RequestIds.bind(requestId)) {
                    super.onComplete();
                }
            }

            @Override
            public void onReady() {
                try (RequestIds.Scope scope = RequestIds.bind(requestId)) {
                    super.onReady();
                }
            }
        };
    }
}
