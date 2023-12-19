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
package org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.stream;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.jackrabbit.oak.index.indexer.document.flatfile.analysis.stream.NodeProperty.ValueType;

import net.jpountz.lz4.LZ4FrameInputStream;

/**
 * A node stream reader with compression for repeated strings.
 */
public class NodeStreamReaderCompressed implements NodeDataReader {

    private static final int MAX_LENGTH = 1024;
    private static final int WINDOW_SIZE = 1024;

    private final InputStream in;
    private final long fileSize;
    private final String[] lastStrings = new String[WINDOW_SIZE];

    private long currentId;
    private long count;
    private byte[] buffer = new byte[1024 * 1024];

    private NodeStreamReaderCompressed(long fileSize, InputStream in) {
        this.fileSize = fileSize;
        this.in = in;
    }

    /**
     * Read a variable size int.
     *
     * @return the value
     * @throws IOException
     */
    public static int readVarInt(InputStream in) throws IOException {
        int b = in.read();
        if ((b & 0x80) == 0) {
            return b;
        }
        // a separate function so that this one can be inlined
        return readVarIntRest(in, b);
    }

    private static int readVarIntRest(InputStream in, int b) throws IOException {
        int x = b & 0x7f;
        b = in.read();
        if ((b & 0x80) == 0) {
            return x | (b << 7);
        }
        x |= (b & 0x7f) << 7;
        b = in.read();
        if ((b & 0x80) == 0) {
            return x | (b << 14);
        }
        x |= (b & 0x7f) << 14;
        b = in.read();
        if ((b & 0x80) == 0) {
            return x | b << 21;
        }
        x |= ((b & 0x7f) << 21) | (in.read() << 28);
        return x;
    }

    public static NodeStreamReaderCompressed open(String fileName) throws IOException {
        long fileSize = new File(fileName).length();
        InputStream in = new FileInputStream(fileName);
        if (fileName.endsWith(".lz4")) {
            in = new LZ4FrameInputStream(in);
        }
        return new NodeStreamReaderCompressed(fileSize, in);
    }

    public NodeData readNode() throws IOException {
        int size = readVarInt(in);
        if (size < 0) {
            in.close();
            return null;
        }
        if (++count % 1000000 == 0) {
            System.out.println(count + " lines");
        }
        ArrayList<String> pathElements = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            pathElements.add(readString(in));
        }
        int propertyCount = readVarInt(in);
        ArrayList<NodeProperty> properties = new ArrayList<>(propertyCount);
        for (int i = 0; i < propertyCount; i++) {
            NodeProperty p;
            String name = readString(in);
            ValueType type = ValueType.byOrdinal(in.read());
            if (in.read() == 1) {
                int count = readVarInt(in);
                String[] values = new String[count];
                for (int j = 0; j < count; j++) {
                    values[j] = readString(in);
                }
                p = new NodeProperty(name, type, values, true);
            } else {
                String value = readString(in);
                p = new NodeProperty(name, type, value);
            }
            properties.add(p);
        }
        return new NodeData(pathElements, properties);
    }

    private String readString(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 2) {
            if (len == 0) {
                return null;
            } else if (len == 1) {
                return "";
            }
        }
        if ((len & 1) == 1) {
            int offset = len >>> 1;
            String s = lastStrings[(int) (currentId - offset) & (WINDOW_SIZE - 1)];
            lastStrings[(int) currentId & (WINDOW_SIZE - 1)] = s;
            currentId++;
            return s;
        }
        len = len >>> 1;
        byte[] buff = buffer;
        if (len > buff.length) {
            buff = buffer = new byte[len];
        }
        int read = in.readNBytes(buff, 0, len);
        if (read != len) {
            throw new EOFException();
        }
        String s = new String(buff, 0, len, StandardCharsets.UTF_8);
        if (s.length() < MAX_LENGTH) {
            lastStrings[(int) currentId & (WINDOW_SIZE - 1)] = s;
            currentId++;
        }
        return s;
    }

    @Override
    public long getFileSize() {
        return fileSize;
    }

}
