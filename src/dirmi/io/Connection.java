/*
 * Copyright (c) 2006 Brian S O'Neill. All Rights Reserved.
 */

package bidirmi;

import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface Connection extends Closeable {
    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;
}
