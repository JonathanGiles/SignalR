// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.aspnet.signalr;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HubConnection {
    private static final String RECORD_SEPARATOR = "\u001e";
    private static ArrayList<Class<?>> emptyArray = new ArrayList<>();
    private static int MAX_NEGOTIATE_ATTEMPTS = 100;

    private final Logger LOGGER = Logger.getLogger(HubConnection.class.getName());

    private String url;
    private Transport transport;
    private OnReceiveCallBack callback;
    private CallbackMap handlers = new CallbackMap();
    private HubProtocol protocol;
    private Boolean handshakeReceived = false;

    private HubConnectionState hubConnectionState = HubConnectionState.DISCONNECTED;
    private Lock hubConnectionStateLock = new ReentrantLock();
    private List<Consumer<Exception>> onClosedCallbackList;
    private boolean skipNegotiate;
    private Map<String, String> headers = new HashMap<>();
    private ConnectionState connectionState = null;
    private HttpClient httpClient;

    HubConnection(String url, Transport transport, boolean skipNegotiate, Level logLevel, HttpClient client) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("A valid url is required.");
        }

        this.url = url;
        this.protocol = new JsonHubProtocol();

        if (logLevel != null) {
            LOGGER.setLevel(logLevel);
        }

        if (client != null) {
            this.httpClient = client;
        } else {
            this.httpClient = new DefaultHttpClient();
        }

        if (transport != null) {
            this.transport = transport;
        }

        this.skipNegotiate = skipNegotiate;

        this.callback = (payload) -> {
            if (!handshakeReceived) {
                int handshakeLength = payload.indexOf(RECORD_SEPARATOR) + 1;
                String handshakeResponseString = payload.substring(0, handshakeLength - 1);
                HandshakeResponseMessage handshakeResponse = HandshakeProtocol.parseHandshakeResponse(handshakeResponseString);
                if (handshakeResponse.getError() != null) {
                    String errorMessage = "Error in handshake " + handshakeResponse.getError();
                    LOGGER.warning(errorMessage);
                    throw new RuntimeException(errorMessage);
                }
                handshakeReceived = true;

                payload = payload.substring(handshakeLength);
                // The payload only contained the handshake response so we can return.
                if (payload.length() == 0) {
                    return;
                }
            }

            HubMessage[] messages = protocol.parseMessages(payload, connectionState);

            for (HubMessage message : messages) {
                LOGGER.info("Received message of type " + message.getMessageType() + ".");
                switch (message.getMessageType()) {
                    case INVOCATION:
                        InvocationMessage invocationMessage = (InvocationMessage) message;
                        List<InvocationHandler> handlers = this.handlers.get(invocationMessage.getTarget());
                        if (handlers != null) {
                            for (InvocationHandler handler : handlers) {
                                handler.getAction().invoke(invocationMessage.getArguments());
                            }
                        } else {
                            LOGGER.warning("Failed to find handler for " + invocationMessage.getMessageType() + " method.");
                        }
                        break;
                    case CLOSE:
                        LOGGER.info("Close message received from server.");
                        CloseMessage closeMessage = (CloseMessage) message;
                        stop(closeMessage.getError());
                        break;
                    case PING:
                        // We don't need to do anything in the case of a ping message.
                        break;
                    case COMPLETION:
                        CompletionMessage completionMessage = (CompletionMessage)message;
                        InvocationRequest irq = connectionState.tryRemoveInvocation(completionMessage.getInvocationId());
                        if (irq == null) {
                            LOGGER.warning("Dropped unsolicited Completion message for invocation '" + completionMessage.getInvocationId() + "'.");
                            continue;
                        }
                        irq.complete(completionMessage);
                        break;
                    case STREAM_INVOCATION:
                    case STREAM_ITEM:
                    case CANCEL_INVOCATION:
                        LOGGER.warning("This client does not support " + message.getMessageType() + " messages.");
                        throw new UnsupportedOperationException(String.format("The message type %s is not supported yet.", message.getMessageType()));
                }
            }
        };
    }

    private CompletableFuture<NegotiateResponse> handleNegotiate() {
        HttpRequest request = new HttpRequest();
        request.addHeaders(this.headers);

        return httpClient.post(Negotiate.resolveNegotiateUrl(url), request).thenCompose((response) -> {
            NegotiateResponse negotiateResponse;
            try {
                negotiateResponse = new NegotiateResponse(response.getContent());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            if (negotiateResponse.getError() != null) {
                throw new RuntimeException(negotiateResponse.getError());
            }

            if (negotiateResponse.getConnectionId() != null) {
                if (url.contains("?")) {
                    url = url + "&id=" + negotiateResponse.getConnectionId();
                } else {
                    url = url + "?id=" + negotiateResponse.getConnectionId();
                }
            }

            if (negotiateResponse.getAccessToken() != null) {
                this.headers.put("Authorization", "Bearer " + negotiateResponse.getAccessToken());
            }

            if (negotiateResponse.getRedirectUrl() != null) {
                this.url = negotiateResponse.getRedirectUrl();
            }

            return CompletableFuture.completedFuture(negotiateResponse);
        });
    }

    /**
     * Indicates the state of the {@link HubConnection} to the server.
     *
     * @return HubConnection state enum.
     */
    public HubConnectionState getConnectionState() {
        return hubConnectionState;
    }

    /**
     * Starts a connection to the server.
     *
     * @return A CompletableFuture
     * @throws Exception An error occurred while connecting.
     */
    public CompletableFuture<Void> start() throws Exception {
        if (hubConnectionState != HubConnectionState.DISCONNECTED) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<NegotiateResponse> negotiate = null;
        if (!skipNegotiate) {
            negotiate = startNegotiate(0);
        } else {
            negotiate = CompletableFuture.completedFuture(null);
        }

        return negotiate.thenCompose((response) -> {
            // If we didn't skip negotiate and got a null response then exit start because we
            // are probably disconnected
            if (response == null && !skipNegotiate) {
                return CompletableFuture.completedFuture(null);
            }

            LOGGER.info("Starting HubConnection");
            if (transport == null) {
                transport = new WebSocketTransport(url, headers, httpClient);
            }

            transport.setOnReceive(this.callback);

            try {
                return transport.start().thenCompose((future) -> {
                    String handshake = HandshakeProtocol.createHandshakeRequestMessage(
                            new HandshakeRequestMessage(protocol.getName(), protocol.getVersion()));
                    return transport.send(handshake).thenRun(() -> {
                        hubConnectionStateLock.lock();
                        try {
                            hubConnectionState = HubConnectionState.CONNECTED;
                            connectionState = new ConnectionState(this);
                            LOGGER.info("HubConnection started.");
                        } finally {
                            hubConnectionStateLock.unlock();
                        }
                    });
                });
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private CompletableFuture<NegotiateResponse> startNegotiate(int negotiateAttempts) {
        if (hubConnectionState != HubConnectionState.DISCONNECTED) {
            return CompletableFuture.completedFuture(null);
        }

        return handleNegotiate().thenCompose((response) -> {
            if (response.getRedirectUrl() != null && negotiateAttempts >= MAX_NEGOTIATE_ATTEMPTS) {
                throw new RuntimeException("Negotiate redirection limit exceeded.");
            }

            if (response.getRedirectUrl() == null) {
                if (!response.getAvailableTransports().contains("WebSockets")) {
                    throw new RuntimeException("There were no compatible transports on the server.");
                }
                return CompletableFuture.completedFuture(response);
            }

            return startNegotiate(negotiateAttempts + 1);
        });
    }

    /**
     * Stops a connection to the server.
     */
    private CompletableFuture<Void> stop(String errorMessage) {
        hubConnectionStateLock.lock();
        try {
            if (hubConnectionState == HubConnectionState.DISCONNECTED) {
                return CompletableFuture.completedFuture(null);
            }

            if (errorMessage != null) {
                LOGGER.warning("HubConnection disconnected with an error " + errorMessage +".");
            } else {
                LOGGER.info("Stopping HubConnection.");
            }
        } finally {
            hubConnectionStateLock.unlock();
        }

        return transport.stop().whenComplete((i, t) -> {
            RuntimeException hubException = null;
            hubConnectionStateLock.lock();
            try {
                hubConnectionState = HubConnectionState.DISCONNECTED;

                if (errorMessage != null) {
                    hubException = new RuntimeException(errorMessage);
                } else if (t != null) {
                    hubException = new RuntimeException(t.getMessage());
                }
                connectionState.cancelOutstandingInvocations(hubException);
                connectionState = null;
                LOGGER.info("HubConnection stopped.");
            } finally {
                hubConnectionStateLock.unlock();
            }

            // Do not run these callbacks inside the hubConnectionStateLock
            if (onClosedCallbackList != null) {
                for (Consumer<Exception> callback : onClosedCallbackList) {
                    callback.accept(hubException);
                }
            }
        });
    }

    /**
     * Stops a connection to the server.
     *
     * @return A CompletableFuture
     */
    public CompletableFuture<Void> stop() {
        return stop(null);
    }

    /**
     * Invokes a hub method on the server using the specified method name.
     * Does not wait for a response from the receiver.
     *
     * @param method The name of the server method to invoke.
     * @param args   The arguments to be passed to the method.
     * @throws Exception If there was an error while sending.
     */
    public void send(String method, Object... args) throws Exception {
        if (hubConnectionState != HubConnectionState.CONNECTED) {
            throw new RuntimeException("The 'send' method cannot be called if the connection is not active");
        }

        InvocationMessage invocationMessage = new InvocationMessage(null, method, args);
        sendHubMessage(invocationMessage);
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> invoke(Class<T> returnType, String method, Object... args) throws Exception {
        String id = connectionState.getNextInvocationId();
        InvocationMessage invocationMessage = new InvocationMessage(id, method, args);

        CompletableFuture<T> future = new CompletableFuture<>();
        InvocationRequest irq = new InvocationRequest(returnType, id);
        connectionState.addInvocation(irq);

        // forward the invocation result or error to the user
        // run continuations on a separate thread
        CompletableFuture<Object> pendingCall = irq.getPendingCall();
        pendingCall.whenCompleteAsync((result, error) -> {
            if (error == null) {
                // Primitive types can't be cast with the Class cast function
                if (returnType.isPrimitive()) {
                    future.complete((T)result);
                } else {
                    future.complete(returnType.cast(result));
                }
            } else {
                future.completeExceptionally(error);
            }
        });

        // Make sure the actual send is after setting up the future otherwise there is a race
        // where the map doesn't have the future yet when the response is returned
        sendHubMessage(invocationMessage);

        return future;
    }

    private void sendHubMessage(HubMessage message) {
        String serializedMessage = protocol.writeMessage(message);
        if (message.getMessageType() == HubMessageType.INVOCATION) {
            LOGGER.info("Sending " + message.getMessageType().value + " message '" + ((InvocationMessage)message).getInvocationId() + "'.");
        } else {
            LOGGER.info("Sending " + message.getMessageType().value + " message.");
        }
        transport.send(serializedMessage);
    }

    /**
     * Removes all handlers associated with the method with the specified method name.
     *
     * @param name The name of the hub method from which handlers are being removed.
     */
    public void remove(String name) {
        handlers.remove(name);
        LOGGER.fine("Removing handlers for client method " + name);
    }

    public void onClosed(Consumer<Exception> callback) {
        if (onClosedCallbackList == null) {
            onClosedCallbackList = new ArrayList<>();
        }

        onClosedCallbackList.add(callback);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public Subscription on(String target, Action callback) {
        ActionBase action = args -> callback.invoke();
        return registerHandler(target, action);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param <T1>     The first argument type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1> Subscription on(String target, Action1<T1> callback, Class<T1> param1) {
        ActionBase action = params -> callback.invoke(param1.cast(params[0]));
        return registerHandler(target, action, param1);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2> Subscription on(String target, Action2<T1, T2> callback, Class<T1> param1, Class<T2> param2) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]));
        };
        return registerHandler(target, action, param1, param2);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param param3   The third parameter.
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @param <T3>     The third parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2, T3> Subscription on(String target, Action3<T1, T2, T3> callback,
                                        Class<T1> param1, Class<T2> param2, Class<T3> param3) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]), param3.cast(params[2]));
        };
        return registerHandler(target, action, param1, param2, param3);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param param3   The third parameter.
     * @param param4   The fourth parameter.
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @param <T3>     The third parameter type.
     * @param <T4>     The fourth parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2, T3, T4> Subscription on(String target, Action4<T1, T2, T3, T4> callback,
                                            Class<T1> param1, Class<T2> param2, Class<T3> param3, Class<T4> param4) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]), param3.cast(params[2]), param4.cast(params[3]));
        };
        return registerHandler(target, action, param1, param2, param3, param4);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param param3   The third parameter.
     * @param param4   The fourth parameter.
     * @param param5   The fifth parameter.
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @param <T3>     The third parameter type.
     * @param <T4>     The fourth parameter type.
     * @param <T5>     The fifth parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2, T3, T4, T5> Subscription on(String target, Action5<T1, T2, T3, T4, T5> callback,
                                                Class<T1> param1, Class<T2> param2, Class<T3> param3, Class<T4> param4, Class<T5> param5) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]), param3.cast(params[2]), param4.cast(params[3]),
                    param5.cast(params[4]));
        };
        return registerHandler(target, action, param1, param2, param3, param4, param5);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param param3   The third parameter.
     * @param param4   The fourth parameter.
     * @param param5   The fifth parameter.
     * @param param6   The sixth parameter.
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @param <T3>     The third parameter type.
     * @param <T4>     The fourth parameter type.
     * @param <T5>     The fifth parameter type.
     * @param <T6>     The sixth parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2, T3, T4, T5, T6> Subscription on(String target, Action6<T1, T2, T3, T4, T5, T6> callback,
                                                    Class<T1> param1, Class<T2> param2, Class<T3> param3, Class<T4> param4, Class<T5> param5, Class<T6> param6) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]), param3.cast(params[2]), param4.cast(params[3]),
                    param5.cast(params[4]), param6.cast(params[5]));
        };
        return registerHandler(target, action, param1, param2, param3, param4, param5, param6);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param param3   The third parameter.
     * @param param4   The fourth parameter.
     * @param param5   The fifth parameter.
     * @param param6   The sixth parameter.
     * @param param7   The seventh parameter.
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @param <T3>     The third parameter type.
     * @param <T4>     The fourth parameter type.
     * @param <T5>     The fifth parameter type.
     * @param <T6>     The sixth parameter type.
     * @param <T7>     The seventh parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2, T3, T4, T5, T6, T7> Subscription on(String target, Action7<T1, T2, T3, T4, T5, T6, T7> callback,
                                                        Class<T1> param1, Class<T2> param2, Class<T3> param3, Class<T4> param4, Class<T5> param5, Class<T6> param6, Class<T7> param7) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]), param3.cast(params[2]), param4.cast(params[3]),
                    param5.cast(params[4]), param6.cast(params[5]), param7.cast(params[6]));
        };
        return registerHandler(target, action, param1, param2, param3, param4, param5, param6, param7);
    }

    /**
     * Registers a handler that will be invoked when the hub method with the specified method name is invoked.
     *
     * @param target   The name of the hub method to define.
     * @param callback The handler that will be raised when the hub method is invoked.
     * @param param1   The first parameter.
     * @param param2   The second parameter.
     * @param param3   The third parameter.
     * @param param4   The fourth parameter.
     * @param param5   The fifth parameter.
     * @param param6   The sixth parameter.
     * @param param7   The seventh parameter.
     * @param param8   The eighth parameter
     * @param <T1>     The first parameter type.
     * @param <T2>     The second parameter type.
     * @param <T3>     The third parameter type.
     * @param <T4>     The fourth parameter type.
     * @param <T5>     The fifth parameter type.
     * @param <T6>     The sixth parameter type.
     * @param <T7>     The seventh parameter type.
     * @param <T8>     The eighth parameter type.
     * @return A {@link Subscription} that can be disposed to unsubscribe from the hub method.
     */
    public <T1, T2, T3, T4, T5, T6, T7, T8> Subscription on(String target, Action8<T1, T2, T3, T4, T5, T6, T7, T8> callback,
                                                            Class<T1> param1, Class<T2> param2, Class<T3> param3, Class<T4> param4, Class<T5> param5, Class<T6> param6, Class<T7> param7, Class<T8> param8) {
        ActionBase action = params -> {
            callback.invoke(param1.cast(params[0]), param2.cast(params[1]), param3.cast(params[2]), param4.cast(params[3]),
                    param5.cast(params[4]), param6.cast(params[5]), param7.cast(params[6]), param8.cast(params[7]));
        };
        return registerHandler(target, action, param1, param2, param3, param4, param5, param6, param7, param8);
    }

    private Subscription registerHandler(String target, ActionBase action, Class<?>... types) {
        InvocationHandler handler = handlers.put(target, action, types);
        LOGGER.fine("Registering handler for client method: " + target);
        return new Subscription(handlers, handler, target);
    }

    private final class ConnectionState implements InvocationBinder {
        private final HubConnection connection;
        private final AtomicInteger nextId = new AtomicInteger(0);
        private final HashMap<String, InvocationRequest> pendingInvocations = new HashMap<>();
        private final Lock lock = new ReentrantLock();

        public ConnectionState(HubConnection connection) {
            this.connection = connection;
        }

        public String getNextInvocationId() {
            int i = nextId.incrementAndGet();
            return Integer.toString(i);
        }

        public void cancelOutstandingInvocations(Exception ex) {
            lock.lock();
            try {
                pendingInvocations.forEach((key, irq) -> {
                    if (ex == null) {
                        irq.cancel();
                    } else {
                        irq.fail(ex);
                    }
                });

                pendingInvocations.clear();
            } finally {
                lock.unlock();
            }
        }

        public void addInvocation(InvocationRequest irq) {
            lock.lock();
            try {
                pendingInvocations.compute(irq.getInvocationId(), (key, value) -> {
                    if (value != null) {
                        // This should never happen
                        throw new IllegalStateException("Invocation Id is already used");
                    }

                    return irq;
                });
            } finally {
                lock.unlock();
            }
        }

        public InvocationRequest getInvocation(String id) {
            lock.lock();
            try {
                return pendingInvocations.get(id);
            } finally {
                lock.unlock();
            }
        }

        public InvocationRequest tryRemoveInvocation(String id) {
            lock.lock();
            try {
                return pendingInvocations.remove(id);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Class<?> getReturnType(String invocationId) {
            InvocationRequest irq = getInvocation(invocationId);
            if (irq == null) {
                return null;
            }

            return irq.getReturnType();
        }

        @Override
        public List<Class<?>> getParameterTypes(String methodName) throws Exception {
            List<InvocationHandler> handlers = connection.handlers.get(methodName);
            if (handlers == null) {
                LOGGER.warning("Failed to find handler for '" + methodName + "' method.");
                return emptyArray;
            }

            if (handlers.isEmpty()) {
                throw new Exception(String.format("There are no callbacks registered for the method '%s'.", methodName));
            }

            return handlers.get(0).getClasses();
        }
    }
}