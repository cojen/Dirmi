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
        T_REFERENCE = 4,   // small
        T_REFERENCE_L = 5, // large
        T_REMOTE = 6,    // + remote object id
        T_REMOTE_T = 7,  // + remote object id + type id
        T_REMOTE_TI = 8, // + remote object id + type id + info
        T_NULL = 10,
        T_VOID = 11,
        T_OBJECT = 12, // plain object, not a subclass
        T_TRUE = 13,
        T_FALSE = 14,
        T_CHAR = 15,
        T_FLOAT = 16,
        T_DOUBLE = 17,
        T_BYTE = 18,
        T_SHORT = 19,
        T_INT = 20,
        T_LONG = 21,
        T_STRING = 22,
        T_STRING_L = 23,
        T_BOOLEAN_ARRAY = 24,
        T_BOOLEAN_ARRAY_L = 25,
        T_CHAR_ARRAY = 26,
        T_CHAR_ARRAY_L = 27,
        T_FLOAT_ARRAY = 28,
        T_FLOAT_ARRAY_L = 29,
        T_DOUBLE_ARRAY = 30,
        T_DOUBLE_ARRAY_L = 31,
        T_BYTE_ARRAY = 32,
        T_BYTE_ARRAY_L = 33,
        T_SHORT_ARRAY = 34,
        T_SHORT_ARRAY_L = 35,
        T_INT_ARRAY = 36,
        T_INT_ARRAY_L = 37,
        T_LONG_ARRAY = 38,
        T_LONG_ARRAY_L = 39,
        T_OBJECT_ARRAY = 40,
        T_OBJECT_ARRAY_L = 41,
        T_LIST = 42,
        T_LIST_L = 43,
        T_SET = 44,
        T_SET_L = 45,
        T_MAP = 46,
        T_MAP_L = 47,
        T_BIG_INTEGER = 48,
        T_BIG_INTEGER_L = 49,
        T_BIG_DECIMAL = 50,
        T_THROWABLE = 51,
        T_STACK_TRACE = 52,
        T_CUSTOM_2 = 53,
        T_CUSTOM_4 = 54,

        T_FIRST_CUSTOM = T_CUSTOM_4 + 1;
}
