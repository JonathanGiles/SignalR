// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.aspnet.signalr;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

class WebSocketTransport implements Transport {
    private final static Logger LOGGER = Logger.getLogger(WebSocketTransport.class.getName());

    private WebSocketWrapper webSocketClient;
    private OnReceiveCallBack onReceiveCallBack;
    private String url;
    private HttpClient client;
    private Map<String, String> headers;

    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String WS = "ws";
    private static final String WSS = "wss";

    public WebSocketTransport(String url, Map<String, String> headers, HttpClient client) {
        this.url = formatUrl(url);
        this.client = client;
        this.headers = headers;
    }

    String getUrl() {
        return url;
    }

    private String formatUrl(String url) {
        if (url.startsWith(HTTPS)) {
            url = WSS + url.substring(HTTPS.length());
        } else if (url.startsWith(HTTP)) {
            url = WS + url.substring(HTTP.length());
        }

        return url;
    }

    @Override
    public CompletableFuture<Void> start() {
        LOGGER.info("Starting Websocket connection.");
        this.webSocketClient = client.createWebSocket(this.url, this.headers);
        this.webSocketClient.setOnReceive(this::onReceive);
        this.webSocketClient.setOnClose(this::onClose);
        return webSocketClient.start().thenRun(() -> LOGGER.info("WebSocket transport connected to: " + this.url + "."));
    }

    @Override
    public CompletableFuture<Void> send(String message) {
        return webSocketClient.send(message);
    }

    @Override
    public void setOnReceive(OnReceiveCallBack callback) {
        this.onReceiveCallBack = callback;
        LOGGER.info("OnReceived callback has been set");
    }

    @Override
    public void onReceive(String message) throws Exception {
        this.onReceiveCallBack.invoke(message);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return webSocketClient.stop().whenComplete((i, j) -> LOGGER.info("WebSocket connection stopped."));
    }

    void onClose(int code, String reason) {
        LOGGER.info("WebSocket connection stopping with code " + code + " and reason '" + reason + "'.");
    }
}
