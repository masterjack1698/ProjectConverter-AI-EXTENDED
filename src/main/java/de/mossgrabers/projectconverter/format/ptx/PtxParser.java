package de.mossgrabers.projectconverter.format.ptx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Pro Tools .ptx files.
 * Handles XOR decryption, block tree parsing, and data extraction.
 */
public class PtxParser {

    public static class Block {
        public final int pos;          // Position of the 0x5A byte
        public final int btype;        // Block type
        public final int bsize;        // Size of content_type + payload
        public final int ctype;        // Content type
        public final int offset;       // Position of the content_type
        public final List<Block> children = new ArrayList<>();

        public Block(int pos, int btype, int bsize, int ctype, int offset) {
            this.pos = pos;
            this.btype = btype;
            this.bsize = bsize;
            this.ctype = ctype;
            this.offset = offset;
        }

        public int getContentStart() {
            return offset + 2;
        }

        public int getEnd() {
            return offset + bsize;
        }
    }

    /**
     * Decrypts the .ptx file using the XOR logic specified in ptformat.
     */
    public static byte[] unxor(byte[] raw) {
        if (raw[0] != 0x03) {
            throw new RuntimeException("Invalid .ptx signature");
        }

        int xorType = raw[0x12] & 0xFF;
        int xorVal = raw[0x13] & 0xFF;
        int delta;
        int shift;

        if (xorType == 1) {
            delta = genXorDelta(xorVal, 53, false);
            shift = 0;
        } else {
            delta = genXorDelta(xorVal, 11, true);
            shift = 12;
        }

        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = (i * delta) & 0xFF;
        }

        byte[] out = new byte[raw.length];
        System.arraycopy(raw, 0, out, 0, 0x14);

        for (int i = 0x14; i < raw.length; i++) {
            int k = (xorType != 1) ? ((i >> shift) & 0xFF) : (i & 0xFF);
            out[i] = (byte) (raw[i] ^ table[k]);
        }

        return out;
    }

    private static int genXorDelta(int xv, int mul, boolean negative) {
        for (int i = 0; i < 256; i++) {
            if (((i * mul) & 0xFF) == xv) {
                return negative ? ((-i) & 0xFF) : i;
            }
        }
        return 0;
    }

    /**
     * Parses the block tree from the decrypted byte array.
     */
    public static List<Block> walkBlocks(byte[] data) {
        List<Block> blocks = new ArrayList<>();
        List<Integer> zpos = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0x5A) {
                zpos.add(i);
            }
        }

        int currentPos = 20;
        int zi = 0;
        while (zi < zpos.size()) {
            int i = zpos.get(zi);
            if (i >= 20) break;

            Block blk = parseBlockAt(data, i, data.length, 0, zpos);
            if (blk != null) {
                blocks.add(blk);
                int nextSearchPos = i + (blk.bsize > 0 ? blk.bsize : 1) + 7;
                zi = findNextIndex(zpos, nextSearchPos);
            } else {
                zi++;
            }
        }
        return blocks;
    }

    private static Block parseBlockAt(byte[] data, int pos, int parentEnd, int depth, List<Integer> zpos) {
        if (depth > 12 || pos + 9 > data.length || data[pos] != 0x5A) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(data, pos, 9).order(ByteOrder.LITTLE_ENDIAN);
        int btype = bb.get(1) | (bb.get(2) << 8);
        int bsize = bb.getInt(3);
        int ctype = bb.get(7) | (bb.get(8) << 8);
        int offset = pos + 7;

        if ((btype & 0xFF00) != 0 || offset + bsize > parentEnd || bsize > 0x4000000) {
            return null;
        }

        Block blk = new Block(pos, btype, bsize, ctype, offset);

        int limit = pos + bsize;
        int i = pos + 1;

        if (zpos == null) {
            while (i < limit) {
                Block child = parseBlockAt(data, i, offset + bsize, depth + 1, null);
                if (child != null) {
                    blk.children.add(child);
                    i += child.bsize + 7;
                } else {
                    i++;
                }
            }
        } else {
            int currentZi = findNextIndex(zpos, i);
            while (currentZi < zpos.size() && zpos.get(currentZi) < limit) {
                int childPos = zpos.get(currentZi);
                Block child = parseBlockAt(data, childPos, offset + bsize, depth + 1, zpos);
                if (child != null) {
                    blk.children.add(child);
                    i = childPos + child.bsize + 7;
                    currentZi = findNextIndex(zpos, i);
                } else {
                    currentZi++;
                }
            }
        }

        return blk;
    }

    private static int findNextIndex(List<Integer> zpos, int targetPos) {
        int low = 0;
        int high = zpos.size() - 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            if (zpos.get(mid) >= targetPos) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }
}
