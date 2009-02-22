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
import org.cojen.dirmi.SessionListener;

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

    private Auto mAuto;

    /**
     * @param environment shared environment for creating sessions
     * @param brokerAcceptor accepted brokers must always connect to same remote server
     */
    public static SessionAcceptor create(Environment environment,
                                         Acceptor<Broker<StreamChannel>> brokerAcceptor)
    {
        return new StandardSessionAcceptor(environment, brokerAcceptor);
    }

    /**
     * @param environment shared environment for creating sessions
     * @param brokerAcceptor accepted brokers must always connect to same remote server
     */
    private StandardSessionAcceptor(Environment environment,
                                    Acceptor<Broker<StreamChannel>> brokerAcceptor)
    {
        mEnv = environment;
        mBrokerAcceptor = brokerAcceptor;
    }

    public Object getLocalAddress() {
        return mBrokerAcceptor.getLocalAddress();
    }

    public void accept(SessionListener listener) {
        synchronized (this) {
            if (mAuto != null) {
                mAuto.disabled = true;
                mAuto = null;
            }
            if (listener != null) {
                mBrokerAcceptor.accept(new Manual(listener));
            }
        }
    }

    public void acceptAll(Object shared) {
        synchronized (this) {
            if (mAuto != null) {
                mAuto.disabled = true;
            }
            mBrokerAcceptor.accept(mAuto = new Auto(shared));
        }
    }

    public void close() throws IOException {
        mBrokerAcceptor.close();
    }

    @Override
    public String toString() {
        return "SessionAcceptor {localAddress=" + getLocalAddress() + '}';
    }

    void uncaught(Throwable e) {
        Thread t = Thread.currentThread();
        try {
            t.getUncaughtExceptionHandler().uncaughtException(t, e);
        } catch (Throwable e2) {
            // I give up.
        }
        // Yield just in case exceptions are out of control.
        t.yield();
    }

    private class Manual implements AcceptListener<Broker<StreamChannel>> {
        private final SessionListener mListener;

        Manual(SessionListener listener) {
            mListener = listener;
        }

        public void established(Broker<StreamChannel> broker) {
            try {
                mListener.established(mEnv.newSession(broker));
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
            try {
                mListener.failed(e);
            } catch (IOException e2) {
                uncaught(e2);
            }
        }
    }

    private class Auto implements AcceptListener<Broker<StreamChannel>> {
        private final Object mShared;
        volatile boolean disabled;

        Auto(Object shared) {
            mShared = shared;
        }

        public void established(Broker<StreamChannel> broker) {
            if (!disabled) {
                mBrokerAcceptor.accept(this);
            }
            try {
                mEnv.newSession(broker).send(mShared);
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
            /*
            if (!disabled) {
                mBrokerAcceptor.accept(this);
            }
            */
            uncaught(e);
        }
    }
}
