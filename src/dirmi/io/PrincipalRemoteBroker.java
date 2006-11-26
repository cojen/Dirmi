/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;
import java.rmi.RemoteException;

import org.cojen.util.IntHashMap;

import dirmi.core.Identifier;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class PrincipalRemoteBroker extends AbstractRemoteBroker {
    private static final int DEFAULT_LEASE_DURATION_MILLIS = 60000;

    private static final byte REASON_INITIATE       = 1;
    private static final byte REASON_REMOTE_OBJECTS = 2;
    private static final byte REASON_LEASE_RENEWAL  = 3;

    final Broker mBroker;
    final long mLeaseDurationMillis;

    final IntHashMap mConnections;
    private int mNextId;

    public PrincipalRemoteBroker(Broker broker) {
        if (broker == null) {
            throw new IllegalArgumentException("Broker is null");
        }
        mBroker = broker;
        mLeaseDurationMillis = DEFAULT_LEASE_DURATION_MILLIS;
        mConnections = new IntHashMap();
    }

    /* FIXME

    RemoteConnection weakly registers itself with a timer, to fire off lease
    renewals. Lease renewal rate is half the lease duration.

    */

    protected RemoteConnection connect() throws IOException {
        return connected(mBroker.connecter().connect());
    }

    protected RemoteConnection connect(int timeoutMillis) throws IOException {
        return connected(mBroker.connecter().connect(timeoutMillis));
    }

    protected RemoteConnection accept() throws IOException {
        while (true) {
            Connection con = mBroker.accepter().accept();
            RemoteConnection remoteCon = accepted(con);
            if (remoteCon != null) {
                return remoteCon;
            }
        }
    }

    protected RemoteConnection accept(int timeoutMillis) throws IOException {
        while (true) {
            Connection con = mBroker.accepter().accept(timeoutMillis);
            if (con == null) {
                return null;
            }
            RemoteConnection remoteCon = accepted(con);
            if (remoteCon != null) {
                return remoteCon;
            }
        }
    }

    /**
     * @param con initiating connection, which is closed as a side-effect
     */
    private RemoteConnection connected(Connection con) throws IOException {
        // The initial connection is used for user input/output streams on the
        // RemoteConnection. Acquire two more connections, one for read/write
        // of remote objects, and the other for lease renewal.

        con.getOutputStream().write(REASON_INITIATE);
        con.getOutputStream().flush();

        int conId;
        {
            RemoteInputStream rin = new RemoteInputStream(con.getInputStream());
            conId = rin.readInt();
        }

        // Got the connection ID, so initiating connection no longer needed.
        con.close();

        Connection remoteObjCon = mBroker.connecter().connect();
        {
            RemoteOutputStream rout = new RemoteOutputStream(remoteObjCon.getOutputStream());
            rout.writeByte(REASON_REMOTE_OBJECTS);
            rout.writeInt(conId);
            rout.flush();
        }

        Connection leaseRenewalCon = mBroker.connecter().connect();
        {
            RemoteOutputStream rout = new RemoteOutputStream(leaseRenewalCon.getOutputStream());
            rout.writeByte(REASON_LEASE_RENEWAL);
            rout.writeInt(conId);
            rout.flush();
        }

        return new ConImpl(conId, remoteObjCon, leaseRenewalCon);
    }

    /**
     * @param con initiating connection, which is closed as a side-effect
     * @return ready RemoteConnection or null if none
     */
    private RemoteConnection accepted(Connection con) throws IOException {
        RemoteInputStream rin = new RemoteInputStream(con.getInputStream());

        byte reason = rin.readByte();
        ConImpl conImpl = null;

        if (reason == REASON_INITIATE) {
            int conId;
            synchronized (mConnections) {
                do {
                    conId = mNextId++;
                } while (mConnections.containsKey(conId));
                conImpl = new ConImpl(conId);
                mConnections.put(conId, conImpl);
            }
            RemoteOutputStream rout = new RemoteOutputStream(con.getOutputStream());
            rout.writeInt(conId);
            rout.flush();
        } else if (reason == REASON_REMOTE_OBJECTS) {
            int conId = rin.readInt();
            synchronized (mConnections) {
                conImpl = (ConImpl) mConnections.get(conId);
            }
            if (conImpl != null) {
                conImpl.setRemoteObjectConnection(con);
            }
        } else if (reason == REASON_LEASE_RENEWAL) {
            int conId = rin.readInt();
            synchronized (mConnections) {
                conImpl = (ConImpl) mConnections.get(conId);
            }
            if (conImpl != null) {
                conImpl.setLeaseRenewalConnection(con);
            }
        }

        if (conImpl == null) {
            con.close();
        } else if (conImpl.isReady()) {
            return conImpl;
        }

        return null;
    }

    private class ConImpl implements RemoteConnection {
        private final int mConId;
        private Connection mRemoteObjCon;
        private Connection mLeaseRenewalCon;

        private RemoteInputStream mRemoteIn;
        private RemoteOutputStream mRemoteOut;

        ConImpl(int conId) {
            mConId = conId;
        }

        ConImpl(int conId, Connection remoteObjCon, Connection leaseRenewalCon)
            throws IOException
        {
            mConId = conId;
            setRemoteObjectConnection(remoteObjCon);
            setLeaseRenewalConnection(leaseRenewalCon);
        }

        public void close() throws IOException {
            mConnections.remove(mConId);
            try {
                mLeaseRenewalCon.close();
            } finally {
                mRemoteObjCon.close();
            }
        }

        public RemoteInputStream getInputStream() throws IOException {
            return mRemoteIn;
        }

        public RemoteOutputStream getOutputStream() throws IOException {
            return mRemoteOut;
        }

        public void dispose(Identifier id) throws RemoteException {
            // FIXME: cancel lease
            // FIXME: implement
        }

        void setRemoteObjectConnection(Connection con) throws IOException {
            mRemoteObjCon = con;
            mRemoteIn = new RemoteInputStream(con.getInputStream());
            mRemoteOut = new RemoteOutputStream(con.getOutputStream());
        }

        void setLeaseRenewalConnection(Connection con) {
            mLeaseRenewalCon = con;
        }

        boolean isReady() {
            return mRemoteObjCon != null && mLeaseRenewalCon != null;
        }
    }
}
