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

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class AbstractRemoteBroker implements RemoteBroker {

    private final RemoteConnecter mConnecter;
    private final RemoteAccepter mAccepter;

    public AbstractRemoteBroker() {
        mConnecter = new RemoteConnecter() {
            public RemoteConnection connect() throws IOException {
                return AbstractRemoteBroker.this.connect();
            }
                
            public RemoteConnection connect(int timeoutMillis) throws IOException {
                return AbstractRemoteBroker.this.connect(timeoutMillis);
            }
        };

        mAccepter = new RemoteAccepter() {
            public RemoteConnection accept() throws IOException {
                return AbstractRemoteBroker.this.accept();
            }

            public RemoteConnection accept(int timeoutMillis) throws IOException {
                return AbstractRemoteBroker.this.accept(timeoutMillis);
            }
        };
    }

    public RemoteConnecter connecter() {
        return mConnecter;
    }

    public RemoteAccepter accepter() {
        return mAccepter;
    }

    protected abstract RemoteConnection connect() throws IOException;

    protected abstract RemoteConnection connect(int timeoutMillis) throws IOException;

    protected abstract RemoteConnection accept() throws IOException;

    protected abstract RemoteConnection accept(int timeoutMillis) throws IOException;
}
