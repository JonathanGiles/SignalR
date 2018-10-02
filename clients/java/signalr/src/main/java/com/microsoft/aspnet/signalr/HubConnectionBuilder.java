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

    public HubConnectionBuilder withLogLevel(Level logLevel) {
        this.logLevel = logLevel;
        return this;
    }

    public HubConnectionBuilder withSkipNegotiate(boolean skipNegotiate) {
        this.skipNegotiate = skipNegotiate;
        return this;
    }

    // Transport is not public API, so this method is not public either
    HubConnectionBuilder withTransport(Transport transport) {
        this.transport = transport;
        return this;
    }

    // For testing purposes only
    HubConnectionBuilder withHttpClient(HttpClient client) {
        this.client = client;
        return this;
    }

    public HubConnection build() {
        return new HubConnection(url, transport, skipNegotiate, logLevel, client);
    }
}