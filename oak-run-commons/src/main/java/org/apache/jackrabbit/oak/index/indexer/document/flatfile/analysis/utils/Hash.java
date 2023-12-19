/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.utils;

/**
 * A hash function utility class.
 */
public class Hash {

    /**
     * Calculate a 64-bit hash value from a value, using a seed.
     *
     * The current algorithm used the finalizer of the MurmurHash3 hash function,
     * but callers shouldn't rely on that.
     *
     * @param x    the value
     * @param seed the seed
     * @return the hash value
     */
    public static long hash64(long x, long seed) {
        x += seed;
        x = (x ^ (x >>> 33)) * 0xff51afd7ed558ccdL;
        x = (x ^ (x >>> 33)) * 0xc4ceb9fe1a85ec53L;
        x = x ^ (x >>> 33);
        return x;
    }

    /**
     * Calculate a 64-bit hash value from a value.
     *
     * @param x the value
     * @return the hash value
     */
    public static long hash64(long x) {
        return hash64(x, 100);
    }

}
