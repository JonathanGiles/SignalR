// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.aspnet.signalr;

import java.util.logging.Level;

public class HubConnectionBuilder {
    private final String url;
    private Transport transport;
    private boolean skipNegotiate;
    private HttpClient client;
    private Level logLevel;

    private HubConnectionBuilder(String url) {
        this.url = url;
    }

    public static HubConnectionBuilder create(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("A valid url is required.");
        }
        return new HubConnectionBuilder(url);
    }

//    public HubConnectionBuilder withUrl(String url) {
//        if (url == null || url.isEmpty()) {
//            throw new IllegalArgumentException("A valid url is required.");
//        }
//
//        this.url = url;
//        return this;
//    }

    public HubConnectionBuilder withTransport(Transport transport) {
        this.transport = transport;
        return this;
    }

//    public HubConnectionBuilder withOptions(HttpConnectionOptions options) {
//        this.url = url;
//        this.transport = options.getTransport();
//        this.skipNegotiate = options.getSkipNegotiate();
//        return this;
//    }

    public HubConnectionBuilder withLogLevel(Level logLevel) {
        this.logLevel = logLevel;
        return this;
    }

    public HubConnectionBuilder withSkipNegotiate(boolean skipNegotiate) {
        this.skipNegotiate = skipNegotiate;
        return this;
    }

    // For testing purposes only
    HubConnectionBuilder withHttpClient(HttpClient client) {
        this.client = client;
        return this;
    }

    public HubConnection build() {
        if (this.url == null) {
            throw new RuntimeException("The 'HubConnectionBuilder.withUrl' method must be called before building the connection.");
        }
        return new HubConnection(url, transport, skipNegotiate, logLevel, client);
    }
}