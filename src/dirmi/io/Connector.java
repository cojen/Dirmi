/*
 * Copyright (c) 2006 Brian S O'Neill. All Rights Reserved.
 */

package bidirmi;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface Connector {
    /**
     * Called by client to establish a new connection.
     */
    public Connection connect() throws IOException;

    /**
     * Called by client to establish a new connection.
     */
    public Connection connect(int timeoutMillis) throws IOException;

    /**
     * Called by server to block waiting for a new connection request from client.
     */
    public Connection accept() throws IOException;

    /**
     * Called by server to block waiting for a new connection request from client.
     */
    public Connection accept(int timeoutMillis) throws IOException;
}
