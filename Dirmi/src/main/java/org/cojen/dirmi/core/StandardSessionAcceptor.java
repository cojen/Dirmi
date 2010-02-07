/*
 *  Copyright 2009-2010 Brian S O'Neill
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
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.SessionAcceptor;
import org.cojen.dirmi.SessionListener;

import org.cojen.dirmi.io.ChannelBroker;
import org.cojen.dirmi.io.ChannelBrokerAcceptor;

/**
 * Standard implementation of a remote method invocation {@link
 * SessionAcceptor}.
 *
 * @author Brian S O'Neill
 */
public class StandardSessionAcceptor implements SessionAcceptor {
    final Environment mEnv;
    final ChannelBrokerAcceptor mBrokerAcceptor;

    private Auto mAuto;

    /**
     * @param environment shared environment for creating sessions
     * @param brokerAcceptor accepted brokers must always connect to same remote server
     */
    public static SessionAcceptor create(Environment environment,
                                         ChannelBrokerAcceptor brokerAcceptor)
    {
        return new StandardSessionAcceptor(environment, brokerAcceptor);
    }

    /**
     * @param environment shared environment for creating sessions
     * @param brokerAcceptor accepted brokers must always connect to same remote server
     */
    private StandardSessionAcceptor(Environment environment,
                                    ChannelBrokerAcceptor brokerAcceptor)
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

    private class Manual implements ChannelBrokerAcceptor.Listener {
        private final SessionListener mListener;

        Manual(SessionListener listener) {
            mListener = listener;
        }

        public void accepted(ChannelBroker broker) {
            Session session;
            try {
                session = mEnv.newSession(broker);
            } catch (IOException cause) {
                broker.close();
                try {
                    mListener.establishFailed(cause);
                } catch (Throwable e) {
                    uncaught(e);
                }
                return;
            }

            try {
                mListener.established(session);
            } catch (Throwable e) {
                try {
                    session.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                uncaught(e);
            }
        }

        public void rejected(RejectedException cause) {
            try {
                if (cause.isShutdown()) {
                    mListener.acceptFailed(cause);
                } else {
                    mListener.establishFailed(cause);
                }
            } catch (Throwable e) {
                uncaught(e);
            }
        }

        public void failed(IOException cause) {
            try {
                mListener.establishFailed(cause);
            } catch (Throwable e) {
                uncaught(e);
            }
        }

        public void closed(IOException cause) {
            try {
                mListener.acceptFailed(cause);
            } catch (Throwable e) {
                uncaught(e);
            }
        }
    }

    private class Auto implements ChannelBrokerAcceptor.Listener {
        private final Object mShared;
        volatile boolean disabled;
        private volatile boolean mAnyAccepted;

        Auto(Object shared) {
            mShared = shared;
        }

        public void accepted(ChannelBroker broker) {
            mAnyAccepted = true;

            if (!disabled) {
                mBrokerAcceptor.accept(this);
            }

            Session session;
            try {
                session = mEnv.newSession(broker);
            } catch (IOException cause) {
                broker.close();
                return;
            }

            try {
                session.send(mShared);
            } catch (IOException e) {
                try {
                    session.close();
                } catch (IOException e2) {
                    // Ignore.
                }
            }
        }

        public void rejected(RejectedException cause) {
            if (!disabled && !cause.isShutdown()) {
                mBrokerAcceptor.accept(this);
            }
        }

        public void failed(IOException cause) {
            if (!disabled) {
                mBrokerAcceptor.accept(this);
            }
        }

        public void closed(IOException cause) {
            if (!mAnyAccepted) {
                // Report initialization problem with acceptor.
                uncaught(cause);
            }
        }
    }
}
