/*
 *  Copyright 2007 Brian S O'Neill
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

import java.rmi.RemoteException;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.dirmi.Environment;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ClientDriver implements Driver {
    private static final String URL_PREFIX = "jdbc:dirmi://";

    static {
        try {
            DriverManager.registerDriver(new ClientDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static SQLException unsupported() throws SQLException {
        try {
            Class clazz = Class.forName("java.sql.SQLFeatureNotSupportedException");
            if (SQLException.class.isAssignableFrom(clazz)) {
                throw (SQLException) clazz.newInstance();
            }
        } catch (ClassNotFoundException e) {
        } catch (InstantiationException e) {
        } catch (IllegalAccessException e) {
        }

        throw new SQLException("unsupported");
    }

    private final Environment mEnvironment;
    private final Map<String, Session> mSessions;
    private final ReadWriteLock mSessionsLock;

    private ClientDriver() {
        mEnvironment = new Environment();
        mSessions = new HashMap<String, Session>();
        mSessionsLock = new ReentrantReadWriteLock();
    }

    public Connection connect(String url, Properties info) throws SQLException {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            throw new SQLException("Unsupported URL: " + url);
        }
        url = url.substring(URL_PREFIX.length());

        String host;
        {
            int index = url.indexOf(':');
            if (index < 0) {
                throw new SQLException("Malformed URL at: " + url + " (colon character expected)");
            }
            host = url.substring(0, index);
            url = url.substring(index + 1);
        }

        int port;
        {
            int index = url.indexOf('/');
            if (index < 0) {
                throw new SQLException("Malformed URL at: " + url + " (slash character expected)");
            }
            try {
                port = Integer.parseInt(url.substring(0, index));
            } catch (NumberFormatException e) {
                throw new SQLException("Malformed URL at: " + url + " (port number expected)");
            }
            url = url.substring(index + 1);
        }

        String db = url;

        final int maxTries = 3;
        for (int tryCount = 1; tryCount <= maxTries; tryCount++) {
            RemoteConnector connector;
            try {
                Session session = obtainSession(host, port, tryCount > 1);
                connector = (RemoteConnector) session.receive();
            } catch (IOException e) {
                throw new SQLException
                    ("Unable to connect to remote host: " + host + ':' + port, e);
            }

            RemoteConnection con;
            try {
                con = connector.getConnection(db);
                return new ClientConnection(con);
            } catch (RemoteException e) {
                if (tryCount >= maxTries) {
                    throw new SQLException("Unable to connect", e);
                }
            }
        }

        // Should not be reached.
        throw new SQLException("Unable to connect");
    }

    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(URL_PREFIX);
    }

    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
        throws SQLException
    {
        return new DriverPropertyInfo[0];
    }

    public int getMajorVersion() {
        return 1;
    }

    public int getMinorVersion() {
        return 0;
    }

    public boolean jdbcCompliant() {
        return false;
    }

    private Session obtainSession(String host, int port, boolean forceNew)
        throws IOException, SQLException
    {
        // FIXME: Use lock per key, not lock for entire cache.

        String key = host + ':' + port;
        Session session;

        if (!forceNew) {
            mSessionsLock.readLock().lock();
            try {
                session = mSessions.get(key);
            } finally {
                mSessionsLock.readLock().unlock();
            }
            
            if (session != null) {
                return session;
            }
        }

        mSessionsLock.writeLock().lock();
        try {
            session = mSessions.get(key);

            if (session != null) {
                if (forceNew) {
                    mSessions.remove(key);
                    try {
                        session.close();
                    } catch (IOException e) {
                        // Don't care.
                    }
                } else {
                    return session;
                }
            }

            session = mEnvironment.createSession(host, port);
            mSessions.put(key, session);
        } finally {
            mSessionsLock.writeLock().unlock();
        }

        return session;
    }
}
