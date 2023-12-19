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
package org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.modules;

import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.stream.NodeData;
import org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.stream.NodeProperty;
import org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.stream.NodeProperty.ValueType;

/**
 * Collects the total binary size (embedded binaries) per path.
 */
public class BinarySizeEmbedded implements StatsCollector {

    private final Storage storage = new Storage();
    private final int resolution;
    private final Random random = new Random(1);

    public BinarySizeEmbedded(int resolution) {
        this.resolution = resolution;
    }

    public void add(NodeData node) {
        long size = 0;
        for(NodeProperty p : node.getProperties()) {
            if (p.getType() == ValueType.BINARY) {
                for (String v : p.getValues()) {
                    if (!v.startsWith(":blobId:")) {
                        continue;
                    }
                    v = v.substring(":blobId:".length());
                    if (v.startsWith("0x")) {
                        // embedded
                        size = (v.length() - 2) / 2;
                    } else {
                        // reference
                    }
                }
            }
        }
        if (size == 0) {
            return;
        }
        storage.add("/", size);
        StringBuilder buff = new StringBuilder();
        for (int i = 0; i < node.getPathElements().size(); i++) {
            String pe = node.getPathElements().get(i);
            buff.append('/').append(pe);
            String key = buff.toString();
            if (pe.equals("jcr:content")) {
                break;
            }
            if (i < 2) {
                storage.add(key, size);
            } else {
                long s2 = size / resolution * resolution;
                if (s2 > 0) {
                    storage.add(key, size);
                } else {
                    if (random.nextInt(resolution) < size) {
                        storage.add(key, (long) resolution);
                    }
                }
            }
        }
    }

    public List<String> getRecords() {
        List<String> result = new ArrayList<>();
        for(Entry<String, Long> e : storage.entrySet()) {
            if (e.getValue() > 1_000_000) {
                result.add(e.getKey() + ": " + (e.getValue() / 1_000_000));
            }
        }
        return result;
    }

    public String toString() {
        StringBuilder buff = new StringBuilder();
        buff.append("BinarySizeEmbedded (MB)\n");
        buff.append(getRecords().stream().map(s -> s + "\n").collect(Collectors.joining()));
        buff.append(storage);
        return buff.toString();
    }

    @Override
    public void end() {
    }

}
