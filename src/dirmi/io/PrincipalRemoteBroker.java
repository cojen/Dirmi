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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;
import java.rmi.RemoteException;

import cojen.util.IntHashMap;

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

    /* TODO

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

    private RemoteConnection connected(Connection con) throws IOException {
        // The initial connection is used for user input/output streams on the
        // RemoteConnection. Acquire two more connections, one for read/write
        // of remote objects, and the other for lease renewal.

        con.getOutputStream().write(REASON_INITIATE);
        con.getOutputStream().flush();

        DataInputStream din = new DataInputStream(con.getInputStream());
        int conId = din.readInt();

        Connection remoteObjCon = mBroker.connecter().connect();
        {
            DataOutputStream dout = new DataOutputStream(remoteObjCon.getOutputStream());
            dout.writeByte(REASON_REMOTE_OBJECTS);
            dout.writeInt(conId);
            dout.flush();
        }

        Connection leaseRenewalCon = mBroker.connecter().connect();
        {
            DataOutputStream dout = new DataOutputStream(leaseRenewalCon.getOutputStream());
            dout.writeByte(REASON_LEASE_RENEWAL);
            dout.writeInt(conId);
            dout.flush();
        }

        return new ConImpl(conId, remoteObjCon, leaseRenewalCon);
    }

    private RemoteConnection accepted(Connection con) throws IOException {
        DataInputStream din = new DataInputStream(con.getInputStream());
        DataOutputStream dout = new DataOutputStream(con.getOutputStream());

        byte reason = din.readByte();
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
            dout.writeInt(conId);
            dout.flush();
        } else if (reason == REASON_REMOTE_OBJECTS) {
            int conId = din.readInt();
            synchronized (mConnections) {
                conImpl = (ConImpl) mConnections.get(conId);
            }
            if (conImpl != null) {
                conImpl.mRemoteObjCon = con;
            }
        } else if (reason == REASON_LEASE_RENEWAL) {
            int conId = din.readInt();
            synchronized (mConnections) {
                conImpl = (ConImpl) mConnections.get(conId);
            }
            if (conImpl != null) {
                conImpl.mLeaseRenewalCon = con;
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
        final int mConId;
        Connection mRemoteObjCon;
        Connection mLeaseRenewalCon;

        ConImpl(int conId) {
            mConId = conId;
        }

        ConImpl(int conId, Connection remoteObjCon, Connection leaseRenewalCon) {
            mConId = conId;
            mRemoteObjCon = remoteObjCon;
            mLeaseRenewalCon = leaseRenewalCon;
        }

        public void close() throws IOException {
            try {
                mLeaseRenewalCon.close();
            } finally {
                mRemoteObjCon.close();
            }
        }

        public Remote readRemote() throws RemoteException {
            // TODO
            return null;
        }

        public void writeRemote(Remote remote) throws RemoteException {
            // TODO
        }

        public boolean dispose(Remote remote) throws RemoteException {
            // TODO: cancel lease

            // TODO
            return false;
        }

        boolean isReady() {
            return mRemoteObjCon != null && mLeaseRenewalCon != null;
        }
    }
}
