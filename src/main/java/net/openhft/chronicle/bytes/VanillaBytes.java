/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.bytes;

import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.util.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import static net.openhft.chronicle.bytes.NoBytesStore.noBytesStore;

/**
 * Simple Bytes implementation which is not Elastic.
 */
public class VanillaBytes<Underlying> extends AbstractBytes<Underlying>
        implements Byteable<Bytes<Underlying>, Underlying> {

    public VanillaBytes(@NotNull BytesStore bytesStore) throws IllegalStateException {
        this(bytesStore, bytesStore.writePosition(), bytesStore.writeLimit());
    }

    public VanillaBytes(@NotNull BytesStore bytesStore, long writePosition, long writeLimit)
            throws IllegalStateException {
        super(bytesStore, writePosition, writeLimit);
    }

    /**
     * @return a non elastic bytes.
     */
    @NotNull
    public static VanillaBytes<Void> vanillaBytes() {
        try {
            return new VanillaBytes<>(noBytesStore());
        } catch (IllegalStateException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isEqual0(char[] chars, NativeBytesStore bs, long address) {
        long start = System.nanoTime();
        Memory memory = bs.memory;
        int i = 0;
        for (; i < chars.length - 3; i += 4) {
            int b = memory.readInt(address + i);
            int b0 = b & 0xFF;
            int b1 = (b >> 8) & 0xFF;
            int b2 = (b >> 16) & 0xFF;
            int b3 = (b >> 24) & 0xFF;
            if (b0 != chars[i] || b1 != chars[i + 1] || b2 != chars[i + 2] || b3 != chars[i + 3])
                return false;
        }
        for (; i < chars.length; i++) {
            int b = memory.readByte(address + i) & 0xFF;
            if (b != chars[i])
                return false;
        }
        long time = System.nanoTime() - start;
        if (time > 1.e6)
            System.out.println("isEquals took " + time / 1e6 + " ms. len=" + chars.length);
        return true;
    }

    private static boolean isEqual1(char[] chars, BytesStore bytesStore, long readPosition) {
        for (int i = 0; i < chars.length; i++) {
            int b = bytesStore.readByte(readPosition + i) & 0xFF;
            if (b != chars[i])
                return false;
        }
        return true;
    }

    @Override
    public void bytesStore(@NotNull BytesStore<Bytes<Underlying>, Underlying> byteStore, long offset, long length)
            throws IllegalStateException, BufferOverflowException, BufferUnderflowException {
        bytesStore(byteStore);
        // assume its read-only
        readLimit(offset + length);
        writeLimit(offset + length);
        readPosition(offset);
    }

    private void bytesStore(@NotNull BytesStore<Bytes<Underlying>, Underlying> bytesStore)
            throws IllegalStateException {
        BytesStore oldBS = this.bytesStore;
        this.bytesStore = bytesStore;
        bytesStore.reserve();
        oldBS.release();
        clear();
    }

    @Override
    public long offset() {
        return readPosition();
    }

    @Override
    public long maxSize() {
        return readRemaining();
    }

    @Override
    public boolean isElastic() {
        return false;
    }

    @NotNull
    @Override
    public Bytes<Underlying> bytesForRead() throws IllegalStateException {
        return isClear()
                ? new VanillaBytes<>(bytesStore, writePosition(), bytesStore.writeLimit())
                : new SubBytes<>(bytesStore, readPosition(), readLimit());
    }

    @Override
    public boolean isEqual(String s) {
        if (s == null || s.length() != readRemaining()) return false;
        char[] chars = StringUtils.extractChars(s);
        if (bytesStore instanceof NativeBytesStore) {
            NativeBytesStore bs = (NativeBytesStore) this.bytesStore;
            long address = bs.address + bs.translate(readPosition);
            return isEqual0(chars, bs, address);

        } else {
            try {
                return isEqual1(chars, bytesStore, readPosition);

            } catch (IORuntimeException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public long realCapacity() {
        return bytesStore.realCapacity();
    }

    @NotNull
    @Override
    public BytesStore<Bytes<Underlying>, Underlying> copy() {
        if (bytesStore.underlyingObject() instanceof ByteBuffer) {
            ByteBuffer bb = ByteBuffer.allocateDirect(Maths.toInt32(readRemaining()));
            ByteBuffer bbu = (ByteBuffer) bytesStore.underlyingObject();
            ByteBuffer slice = bbu.slice();
            slice.position((int) readPosition());
            slice.limit((int) readLimit());
            bb.put(slice);
            bb.clear();
            return (BytesStore) BytesStore.wrap(bb);

        } else {
            return (BytesStore) NativeBytes.copyOf(this);
        }
    }

    @NotNull
    @Override
    public Bytes<Underlying> write(@NotNull BytesStore bytes, long offset, long length)
            throws BufferOverflowException, BufferUnderflowException, IllegalArgumentException, IORuntimeException {
        if (bytesStore() instanceof NativeBytesStore && bytes.bytesStore() instanceof NativeBytesStore && length >= 64) {
            long len = Math.min(writeRemaining(), Math.min(bytes.readRemaining(), length));
            if (len > 0) {
                writeCheckOffset(writePosition(), len);
                OS.memory().copyMemory(bytes.address(offset), address(writePosition()), len);
                writeSkip(len);
            }

        } else {
            super.write(bytes, offset, length);
        }
        return this;
    }

    public void write(long position, @NotNull CharSequence str, int offset, int length)
            throws BufferOverflowException, IllegalArgumentException, IORuntimeException {
        // todo optimise
        if (str instanceof String) {
            char[] chars = ((String) str).toCharArray();
            ensureCapacity(position + length);
            NativeBytesStore nbs = (NativeBytesStore) bytesStore;
            nbs.write8bit(position, chars, offset, length);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @NotNull
    public VanillaBytes append(@NotNull CharSequence str, int start, int end) throws IndexOutOfBoundsException {
        try {
            if (bytesStore() instanceof NativeBytesStore) {
                if (str instanceof BytesStore) {
                    write((BytesStore) str, (long) start, end - start);
                    return this;
                }
                if (str instanceof String) {
                    appendUtf8(StringUtils.extractChars((String) str), start, end - start);
                    return this;
                }
            }
            super.append(str, start, end);
            return this;
        } catch (Exception e) {
            throw new IndexOutOfBoundsException(e.toString());
        }
    }

    @Override
    public VanillaBytes appendUtf8(CharSequence str) throws BufferOverflowException, IORuntimeException {
        try {
            if (bytesStore() instanceof NativeBytesStore) {
                if (str instanceof BytesStore) {
                    write((BytesStore) str, 0L, str.length());
                    return this;
                }
                if (str instanceof String) {
                    appendUtf8(StringUtils.extractChars((String) str), 0, str.length());
                    return this;
                }
            }
            super.append(str, 0, str.length());
            return this;
        } catch (Exception e) {
            throw new IndexOutOfBoundsException(e.toString());
        }
    }

    @NotNull
    public Bytes<Underlying> append8bit(@NotNull CharSequence cs)
            throws BufferOverflowException, BufferUnderflowException, IORuntimeException {
        if (cs instanceof BytesStore)
            return write((BytesStore) cs);

        if (bytesStore instanceof NativeBytesStore && cs instanceof String)
            return append8bitNBS_S((String) cs);
        return append8bit0(cs);
    }

    @NotNull
    public Bytes<Underlying> append8bit(@NotNull String cs)
            throws BufferOverflowException, BufferUnderflowException, IORuntimeException {
        if (bytesStore instanceof NativeBytesStore)
            return append8bitNBS_S(cs);
        return append8bit0(cs);
    }

    private Bytes<Underlying> append8bitNBS_S(String s) {
        int length = s.length();
        long offset = writeOffsetPositionMoved(length); // can re-assign the byteStore if not large enough.
        NativeBytesStore bytesStore = (NativeBytesStore) this.bytesStore;
        final long address = bytesStore.address + bytesStore.translate(offset);
        final Memory memory = bytesStore.memory;
        final char[] chars = StringUtils.extractChars(s);
        if (memory == null)
            throw new AssertionError(bytesStore.releasedHere);

        int i = 0;
        for (i = 0; i < length - 4; i += 4) {
            int c0 = chars[i] & 0xFF;
            int c1 = chars[i + 1] & 0xFF;
            int c2 = chars[i + 2] & 0xFF;
            int c3 = chars[i + 3] & 0xFF;
            memory.writeInt(address + i, c0 | (c1 << 8) | (c2 << 16) | (c3 << 24));
        }
        for (; i < length; i++) {
            int c0 = chars[i];
            memory.writeByte(address + i, (byte) c0);
        }
        return this;
    }

    public String toString() {
        return bytesStore instanceof NativeBytesStore
                ? toString2((NativeBytesStore) bytesStore)
                : toString0();
    }

    private String toString2(NativeBytesStore bytesStore) {
        int length = Maths.toUInt31(readRemaining());
        char[] chars = new char[length];
        final Memory memory = bytesStore.memory;
        final long address = bytesStore.address + bytesStore.translate(readPosition());
        for (int i = 0; i < length; i++)
            chars[i] = (char) (memory.readByte(address + i) & 0xFF);

        return StringUtils.newString(chars);
    }

    protected String toString0() {
        int length = Maths.toUInt31(readRemaining());
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) (bytesStore.readByte(readPosition() + i) & 0xFF);
        }
        return StringUtils.newString(chars);
    }

    @NotNull
    protected Bytes<Underlying> append8bit0(@NotNull CharSequence cs) {
        int length = cs.length();
        long offset = writeOffsetPositionMoved(length);
        for (int i = 0; i < length; i++) {
            char c = cs.charAt(i);
            if (c > 255) c = '?';
            bytesStore.writeByte(offset + i, (byte) c);
        }
        return this;
    }

    @Override
    public boolean equalBytes(BytesStore bytesStore, long length) throws BufferUnderflowException, IORuntimeException {
        if (this.bytesStore instanceof NativeBytesStore &&
                bytesStore instanceof VanillaBytes && bytesStore.bytesStore() instanceof NativeBytesStore) {
            VanillaBytes b2 = (VanillaBytes) bytesStore;
            NativeBytesStore nbs0 = (NativeBytesStore) this.bytesStore;
            NativeBytesStore nbs2 = (NativeBytesStore) b2.bytesStore();
            long i = 0;
            for (; i < length - 7; i++) {
                long addr0 = nbs0.address + readPosition() - nbs0.start() + i;
                long addr2 = nbs2.address + b2.readPosition() - nbs2.start() + i;
                long l0 = nbs0.memory.readLong(addr0);
                long l2 = nbs2.memory.readLong(addr2);
                if (l0 != l2)
                    return false;
            }
            for (; i < length; i++) {
                long offset2 = readPosition() + i - nbs0.start();
                long offset21 = b2.readPosition() + i - nbs2.start();
                byte b0 = nbs0.memory.readByte(nbs0.address + offset2);
                byte b1 = nbs2.memory.readByte(nbs2.address + offset21);
                if (b0 != b1)
                    return false;
            }
            return true;
        } else {
            return super.equalBytes(bytesStore, length);
        }
    }

    public void read8Bit(char[] chars, int length) {
        long position = readPosition();
        NativeBytesStore nbs = (NativeBytesStore) bytesStore();
        nbs.read8bit(position, chars, length);
    }

    public int byteCheckSum() throws IORuntimeException {
        if (readLimit() >= Integer.MAX_VALUE || start() != 0 || !(bytesStore() instanceof NativeBytesStore))
            return super.byteCheckSum();
        byte b = 0;
        NativeBytesStore bytesStore = (NativeBytesStore) bytesStore();
        Memory memory = bytesStore.memory;
        assert memory != null;
        for (int i = (int) readPosition(), lim = (int) readLimit(); i < lim; i++) {
            b += memory.readByte(bytesStore.address + i);
        }
        return b & 0xFF;
    }

    @Override
    public Bytes<Underlying> appendUtf8(char[] chars, int offset, int length) throws BufferOverflowException, IllegalArgumentException, IORuntimeException {
        ensureCapacity(writePosition() + length);
        if (bytesStore instanceof NativeBytesStore) {
            NativeBytesStore nbs = (NativeBytesStore) this.bytesStore;
            long position = nbs.appendUtf8(writePosition(), chars, offset, length);
            writePosition(position);
        } else {
            super.appendUtf8(chars, offset, length);
        }
        return this;
    }

    @Override
    public ByteBuffer toTemporaryDirectByteBuffer() {
        if (isClear())
            return bytesStore.toTemporaryDirectByteBuffer();
        return super.toTemporaryDirectByteBuffer();
    }

    public int read(@NotNull byte[] bytes) throws IORuntimeException {
        int len = (int) Math.min(bytes.length, readRemaining());
        if (bytesStore instanceof NativeBytesStore) {
            NativeBytesStore nbs = (NativeBytesStore) this.bytesStore;
            long len2 = nbs.read(readPosition(), bytes, 0, len);
            readSkip(len2);
            return Maths.toUInt31(len2);
        }
        return super.read(bytes);
    }
}
