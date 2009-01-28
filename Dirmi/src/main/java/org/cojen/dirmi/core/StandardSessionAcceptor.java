/*
 *  Copyright 2009 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.io.IOException;

import org.cojen.dirmi.Environment;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.SessionAcceptor;

import org.cojen.dirmi.io.Acceptor;
import org.cojen.dirmi.io.AcceptListener;
import org.cojen.dirmi.io.Broker;
import org.cojen.dirmi.io.StreamChannel;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionAcceptor implements SessionAcceptor {
    final Environment mEnv;
    final Acceptor<Broker<StreamChannel>> mBrokerAcceptor;
    final Object mLocalServer;

    private Auto mAuto;

    /**
     * @param environment shared environment for creating sessions
     * @param brokerAcceptor accepted brokers must always connect to same remote server
     * @param server server object to export
     */
    public StandardSessionAcceptor(Environment environment,
                                   Acceptor<Broker<StreamChannel>> brokerAcceptor, Object server) {
        mEnv = environment;
        mBrokerAcceptor = brokerAcceptor;
        mLocalServer = server;
    }

    public Object getLocalServer() {
        return mLocalServer;
    }

    public Object getLocalAddress() {
        return mBrokerAcceptor.getLocalAddress();
    }

    public void accept(AcceptListener<Session> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Must provide a listener");
        }

        synchronized (this) {
            if (mAuto != null) {
                mAuto.disabled = true;
                mAuto = null;
            }
        }

        mBrokerAcceptor.accept(new Manual(listener));
    }

    public synchronized void acceptAll() {
        if (mAuto == null) {
            mBrokerAcceptor.accept(mAuto = new Auto());
        }
    }

    public void close() throws IOException {
        mBrokerAcceptor.close();
    }

    @Override
    public String toString() {
        return "SessionAcceptor {localAddress=" + getLocalAddress() + '}';
    }

    private class Manual implements AcceptListener<Broker<StreamChannel>> {
        private final AcceptListener<Session> mListener;

        Manual(AcceptListener<Session> listener) {
            mListener = listener;
        }

        public void established(Broker<StreamChannel> broker) {
            try {
                mListener.established(mEnv.createSession(broker, mLocalServer));
            } catch (IOException e) {
                try {
                    broker.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                failed(e);
            }
        }

        public void failed(IOException e) {
            mListener.failed(e);
        }
    }

    private class Auto implements AcceptListener<Broker<StreamChannel>> {
        volatile boolean disabled;

        public void established(Broker<StreamChannel> broker) {
            if (!disabled) {
                mBrokerAcceptor.accept(this);
            }
            try {
                mEnv.createSession(broker, mLocalServer);
            } catch (IOException e) {
                try {
                    broker.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                failed(e);
            }
        }

        public void failed(IOException e) {
            Thread t = Thread.currentThread();
            try {
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
            } catch (Throwable e2) {
                // I give up.
            }
            // Yield just in case exceptions are out of control.
            t.yield();
        }
    }
}
