/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class TypeCodes {
    static final int
        T_REF_MODE_ON = 2,
        T_REF_MODE_OFF = 3,
        T_REFERENCE = 4, // small
        T_REFERENCE_L = 5, // large
        T_NULL = 6,
        T_TRUE = 7,
        T_FALSE = 8,
        T_CHAR = 9,
        T_FLOAT = 10,
        T_DOUBLE = 11,
        T_BYTE = 12,
        T_SHORT = 13,
        T_INT = 14,
        T_LONG = 15,
        T_STRING = 16,
        T_STRING_L = 17,
        T_BOOLEAN_ARRAY = 18,
        T_BOOLEAN_ARRAY_L = 19,
        T_CHAR_ARRAY = 20,
        T_CHAR_ARRAY_L = 21,
        T_FLOAT_ARRAY = 22,
        T_FLOAT_ARRAY_L = 23,
        T_DOUBLE_ARRAY = 24,
        T_DOUBLE_ARRAY_L = 25,
        T_BYTE_ARRAY = 26,
        T_BYTE_ARRAY_L = 27,
        T_SHORT_ARRAY = 28,
        T_SHORT_ARRAY_L = 29,
        T_INT_ARRAY = 30,
        T_INT_ARRAY_L = 31,
        T_LONG_ARRAY = 32,
        T_LONG_ARRAY_L = 33,
        T_OBJECT_ARRAY = 34,
        T_OBJECT_ARRAY_L = 35,
        T_LIST = 36,
        T_LIST_L = 37,
        T_SET = 38,
        T_SET_L = 39,
        T_MAP = 40,
        T_MAP_L = 41,
        T_BIG_INTEGER = 42,
        T_BIG_INTEGER_L = 43,
        T_BIG_DECIMAL = 44,
        T_THROWABLE = 45,
        T_STACK_TRACE = 46,
        T_REMOTE = 47,    // remote object id
        T_REMOTE_T = 48,  // remote object id + type id
        T_REMOTE_TI = 49; // remote object id + type id + info
}
