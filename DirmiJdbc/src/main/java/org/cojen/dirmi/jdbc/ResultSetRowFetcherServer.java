/*
 *  Copyright 2007-2010 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.jdbc;

import java.io.IOException;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ResultSetRowFetcherServer implements ResultSetRowFetcher {
    static final Object NULL = new Object();

    final ResultSet mResultSet;
    final ResultSetMetaData mMetaData;
    final BlockingQueue<Object> mRowQueue;

    public ResultSetRowFetcherServer(ResultSet rs, ResultSetMetaData md) {
        mResultSet = rs;
        mMetaData = md;
        mRowQueue = new ArrayBlockingQueue<Object>(1000);

        // FIXME: use thread pool
        new Thread() {
            public void run() {
                try {
                    while (mResultSet.next()) {
                        ResultSetRow row = new ResultSetRow(mResultSet, mMetaData);
                        mRowQueue.put(row);
                    }
                    mRowQueue.put(NULL);
                    mRowQueue.put(NULL);
                } catch (InterruptedException e) {
                    try {
                        mRowQueue.put(NULL);
                        mRowQueue.put(new SQLException(e));
                    } catch (InterruptedException e2) {
                    }
                } catch (SQLException e) {
                    try {
                        mRowQueue.put(NULL);
                        mRowQueue.put(e);
                    } catch (InterruptedException e2) {
                    }
                }
            }
        }.start();
    }

    public Pipe fetch(Pipe pipe) {
        try {
            try {
                int count = 0;
                while (true) {
                    Object obj = mRowQueue.take();
                    if (obj == NULL) {
                        pipe.writeObject(null);
                        obj = mRowQueue.take();
                        if (obj == NULL) {
                            pipe.writeThrowable(null);
                        } else {
                            pipe.writeThrowable((Throwable) obj);
                        }
                        break;
                    } else {
                        pipe.writeObject(obj);
                    }
                    if (++count >= 100) {
                        pipe.reset();
                        count = 0;
                    }
                }
            } catch (InterruptedException e) {
                pipe.writeObject(null);
                pipe.writeThrowable(e);
            }
        } catch (IOException e) {
            // FIXME: log it
        } finally {
            try {
                pipe.close();
            } catch (IOException e2) {
                // Ignore
            }
        }

        return null;
    }

    public SQLWarning getWarnings() throws SQLException {
        return mResultSet.getWarnings();
    }

    public void clearWarnings() throws SQLException {
        mResultSet.clearWarnings();
    }

    public String getCursorName() throws SQLException {
        return mResultSet.getCursorName();
    }

    public void close() {
        try {
            mResultSet.close();
        } catch (SQLException e) {
            // FIXME: log it
        }
    }
}
