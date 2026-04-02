package com.fun90.airopscat.service.ssh;

import java.io.IOException;

/**
 * SSH 本地端口转发句柄。
 */
public interface SshLocalPortForward extends AutoCloseable {

    int localPort();

    String remoteHost();

    int remotePort();

    @Override
    void close() throws IOException;
}
