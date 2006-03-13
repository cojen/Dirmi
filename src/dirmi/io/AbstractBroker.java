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
public abstract class AbstractBroker implements Broker {

    private final Connecter mConnecter;
    private final Accepter mAccepter;

    public AbstractBroker() {
        mConnecter = new Connecter() {
            public Connection connect() throws IOException {
                return AbstractBroker.this.connect();
            }
                
            public Connection connect(int timeoutMillis) throws IOException {
                return AbstractBroker.this.connect(timeoutMillis);
            }
        };

        mAccepter = new Accepter() {
            public Connection accept() throws IOException {
                return AbstractBroker.this.accept();
            }

            public Connection accept(int timeoutMillis) throws IOException {
                return AbstractBroker.this.accept(timeoutMillis);
            }
        };
    }

    public Connecter connecter() {
        return mConnecter;
    }

    public Accepter accepter() {
        return mAccepter;
    }

    protected abstract Connection connect() throws IOException;

    protected abstract Connection connect(int timeoutMillis) throws IOException;

    protected abstract Connection accept() throws IOException;

    protected abstract Connection accept(int timeoutMillis) throws IOException;
}
