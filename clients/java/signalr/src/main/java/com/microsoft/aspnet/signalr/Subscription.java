// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.aspnet.signalr;

import java.util.List;

public final class Subscription {
    private final CallbackMap handlers;
    private final InvocationHandler handler;
    private final String target;

    Subscription(CallbackMap handlers, InvocationHandler handler, String target) {
        this.handlers = handlers;
        this.handler = handler;
        this.target = target;
    }

    public void unsubscribe() {
        List<InvocationHandler> handler = this.handlers.get(target);
        if (handler != null) {
            handler.remove(this.handler);
        }
    }
}
