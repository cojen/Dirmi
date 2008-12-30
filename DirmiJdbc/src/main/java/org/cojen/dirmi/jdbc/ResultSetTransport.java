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

import java.io.Serializable;

import java.rmi.RemoteException;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ResultSetTransport implements Serializable {
    private final ResultSetRowFetcher mFetcher;
    private final ResultSetMetaDataCopy mMetaData;

    public ResultSetTransport(ResultSet rs) throws SQLException {
        mMetaData = new ResultSetMetaDataCopy(rs.getMetaData());
        mFetcher = new ResultSetRowFetcherServer(rs, mMetaData);
    }

    public ResultSetRowFetcher getFetcher() {
        return mFetcher;
    }

    public ResultSetMetaDataCopy getMetaData() {
        return mMetaData;
    }

    public void close() throws RemoteException {
        mFetcher.close();
    }
}
