package org.openntf.xsp.util.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores a value as MessagePack: maps, lists, strings, numbers, booleans, nil, binary and
 * the date wrapper {@link StructuredValues} adds.
 * 
 * <p>
 * The wire format is MessagePack, the implementation is this class: no third-party jar, no
 * reflection, no {@code sun.misc.Unsafe}. That is deliberate - the earlier version used
 * <code>msgpack-core</code>, whose buffer layer reaches for {@code Unsafe} through
 * {@code setAccessible}, which the Java security manager of Notes/Designer and of a Domino
 * server refuses. Encoding and decoding are a few hundred lines of
 * {@link DataOutputStream}/{@link DataInputStream}, and the stored bytes stay compatible
 * with documents written by the jar.
 * </p>
 * 
 * <p>
 * MessagePack is self-describing binary data, like a binary JSON. That is the whole
 * security argument: the decoder can produce a map, a list, a string or a number, and
 * nothing else. There is no type tag that makes it allocate an object, invoke a
 * constructor or call {@code readObject}, so a document containing a hostile payload can
 * at worst produce a wrong {@code Map} and an {@link IOException} - never code execution.
 * Objects are rebuilt afterwards, and only through the {@link StructuredTypes} allow-list.
 * </p>
 * 
 * <p>
 * Both directions are bounded: a payload larger than {@link #MAX_PAYLOAD_BYTES} is refused
 * before it is parsed (or written), nesting is capped by {@link #MAX_DEPTH}, and every
 * declared length is checked against the remaining payload before memory is allocated. A
 * stored value is data from a document, and a document can replicate in from anywhere.
 * </p>
 */
public final class MessagePackCodec implements ValueCodec {
	public static final String SCHEME = "msgpack/1";
	public static final String CONTENT_TYPE = "application/x-msgpack";

	/**
	 * Upper bound for a stored value, in encoded bytes. A document item cannot usefully
	 * hold much more than this, and a limit that is checked before parsing is what turns
	 * "arbitrary data" into "arbitrary data up to a size we chose".
	 */
	public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

	/** Nesting limit, so a hostile payload cannot exhaust the stack through the reader. */
	private static final int MAX_DEPTH = 32;

	private static final int MAP_FIX = 0x80;
	private static final int ARRAY_FIX = 0x90;
	private static final int STR_FIX = 0xa0;

	private static final int NIL = 0xc0;
	private static final int FALSE = 0xc2;
	private static final int TRUE = 0xc3;
	private static final int BIN8 = 0xc4;
	private static final int BIN16 = 0xc5;
	private static final int BIN32 = 0xc6;
	private static final int FLOAT32 = 0xca;
	private static final int FLOAT64 = 0xcb;
	private static final int UINT8 = 0xcc;
	private static final int UINT16 = 0xcd;
	private static final int UINT32 = 0xce;
	private static final int UINT64 = 0xcf;
	private static final int INT8 = 0xd0;
	private static final int INT16 = 0xd1;
	private static final int INT32 = 0xd2;
	private static final int INT64 = 0xd3;
	private static final int STR8 = 0xd9;
	private static final int STR16 = 0xda;
	private static final int STR32 = 0xdb;
	private static final int ARRAY16 = 0xdc;
	private static final int ARRAY32 = 0xdd;
	private static final int MAP16 = 0xde;
	private static final int MAP32 = 0xdf;

	@Override
	public String scheme() {
		return SCHEME;
	}

	@Override
	public String contentType() {
		return CONTENT_TYPE;
	}

	@Override
	public boolean canEncode(final Object value) {
		return value instanceof Structured || value instanceof Map || value instanceof Iterable || value instanceof Object[];
	}

	@Override
	public byte[] encode(final Object value) throws IOException {
		Object tree = StructuredValues.normalise(value);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			write(out, bytes, tree, 0);
		}
		if (bytes.size() > MAX_PAYLOAD_BYTES) {
			throw new IOException("The encoded value is " + bytes.size() + " bytes, more than the " + MAX_PAYLOAD_BYTES
					+ "-byte limit; store it as a file or split it into several items");
		}
		return bytes.toByteArray();
	}

	private static void write(final DataOutputStream out, final ByteArrayOutputStream sink, final Object value, final int depth)
			throws IOException {
		/*
		 * The budget is checked before every value, so a large tree is refused while it is
		 * being written instead of after the whole byte array exists.
		 */
		if (sink.size() > MAX_PAYLOAD_BYTES) {
			throw new IOException("The encoded value is already " + sink.size() + " bytes, more than the " + MAX_PAYLOAD_BYTES
					+ "-byte limit; store it as a file or split it into several items");
		}
		if (depth > MAX_DEPTH) {
			throw new IOException("The value tree is nested more than " + MAX_DEPTH + " levels deep");
		}
		if (value == null) {
			out.writeByte(NIL);
		} else if (value instanceof Boolean flag) {
			out.writeByte(flag ? TRUE : FALSE);
		} else if (value instanceof Number number) {
			if (number instanceof Float || number instanceof Double) {
				out.writeByte(FLOAT64);
				out.writeDouble(number.doubleValue());
			} else {
				out.writeByte(INT64);
				out.writeLong(number.longValue());
			}
		} else if (value instanceof CharSequence sequence) {
			writeString(out, sequence.toString().getBytes(StandardCharsets.UTF_8));
		} else if (value instanceof byte[] binary) {
			writeBinary(out, binary);
		} else if (value instanceof Map<?, ?> map) {
			writeHeader(out, map.size(), MAP_FIX, 0x0f, MAP16, MAP32);
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				write(out, sink, String.valueOf(entry.getKey()), depth + 1);
				write(out, sink, entry.getValue(), depth + 1);
			}
		} else if (value instanceof List<?> list) {
			writeHeader(out, list.size(), ARRAY_FIX, 0x0f, ARRAY16, ARRAY32);
			for (Object each : list) {
				write(out, sink, each, depth + 1);
			}
		} else {
			throw new IOException("The value tree contains " + value.getClass().getName()
					+ ", which StructuredValues.normalise should have rejected");
		}
	}

	/** Writes the header of a map, array, string or binary value in its smallest form. */
	private static void writeHeader(final DataOutputStream out, final int length, final int fix, final int fixLimit,
			final int medium, final int large) throws IOException {
		if (length <= fixLimit) {
			out.writeByte(fix | length);
		} else if (length <= 0xffff) {
			out.writeByte(medium);
			out.writeShort(length);
		} else {
			out.writeByte(large);
			out.writeInt(length);
		}
	}

	private static void writeString(final DataOutputStream out, final byte[] text) throws IOException {
		writeHeader(out, text.length, STR_FIX, 0x1f, STR16, STR32);
		out.write(text);
	}

	private static void writeBinary(final DataOutputStream out, final byte[] binary) throws IOException {
		int length = binary.length;
		if (length <= 0xff) {
			out.writeByte(BIN8);
			out.writeByte(length);
		} else if (length <= 0xffff) {
			out.writeByte(BIN16);
			out.writeShort(length);
		} else {
			out.writeByte(BIN32);
			out.writeInt(length);
		}
		out.write(binary);
	}

	@Override
	public Object decode(final byte[] payload) throws IOException {
		if (payload == null) {
			throw new IOException("There is no payload to decode");
		}
		if (payload.length > MAX_PAYLOAD_BYTES) {
			throw new IOException("The stored value is " + payload.length + " bytes, more than the " + MAX_PAYLOAD_BYTES
					+ "-byte limit; refusing to read it");
		}
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
			Object value = read(in, 0);
			if (in.available() > 0) {
				throw new IOException("The stored value has trailing data after its first value");
			}
			return StructuredValues.materialise(value);
		}
	}

	private static Object read(final DataInputStream in, final int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("The stored value is nested more than " + MAX_DEPTH + " levels deep");
		}
		int tag = in.readUnsignedByte();
		if (tag <= 0x7f) {
			return (long) tag;
		}
		if (tag >= 0xe0) {
			return (long) (byte) tag;
		}
		if (tag >= 0xa0 && tag <= 0xbf) {
			return readString(in, tag & 0x1f);
		}
		if (tag >= 0x90 && tag <= 0x9f) {
			return readArray(in, tag & 0x0f, depth);
		}
		if (tag >= 0x80 && tag <= 0x8f) {
			return readMap(in, tag & 0x0f, depth);
		}
		switch (tag) {
		case NIL:
			return null;
		case FALSE:
			return Boolean.FALSE;
		case TRUE:
			return Boolean.TRUE;
		case FLOAT32:
			return (double) in.readFloat();
		case FLOAT64:
			return in.readDouble();
		case UINT8:
			return (long) in.readUnsignedByte();
		case UINT16:
			return (long) in.readUnsignedShort();
		case UINT32:
			return Integer.toUnsignedLong(in.readInt());
		case UINT64:
			return in.readLong();
		case INT8:
			return (long) in.readByte();
		case INT16:
			return (long) in.readShort();
		case INT32:
			return (long) in.readInt();
		case INT64:
			return in.readLong();
		case STR8:
			return readString(in, in.readUnsignedByte());
		case STR16:
			return readString(in, in.readUnsignedShort());
		case STR32:
			return readString(in, in.readInt());
		case BIN8:
			return readBinary(in, in.readUnsignedByte());
		case BIN16:
			return readBinary(in, in.readUnsignedShort());
		case BIN32:
			return readBinary(in, in.readInt());
		case ARRAY16:
			return readArray(in, in.readUnsignedShort(), depth);
		case ARRAY32:
			return readArray(in, in.readInt(), depth);
		case MAP16:
			return readMap(in, in.readUnsignedShort(), depth);
		case MAP32:
			return readMap(in, in.readInt(), depth);
		default:
			throw new IOException("The stored value uses the unsupported MessagePack tag 0x" + Integer.toHexString(tag));
		}
	}

	private static String readString(final DataInputStream in, final int length) throws IOException {
		return new String(readBytes(in, length), StandardCharsets.UTF_8);
	}

	private static byte[] readBinary(final DataInputStream in, final int length) throws IOException {
		return readBytes(in, length);
	}

	/**
	 * The bytes of a declared length, after checking that the length is plausible for a
	 * payload of this size. Without the check, a three-byte header could ask for a
	 * gigabyte of heap before the parser notices there is nothing to read.
	 */
	private static byte[] readBytes(final DataInputStream in, final int length) throws IOException {
		if (length < 0 || length > MAX_PAYLOAD_BYTES) {
			throw new IOException("The stored value declares " + length + " bytes, which cannot be part of a "
					+ MAX_PAYLOAD_BYTES + "-byte payload");
		}
		byte[] result = new byte[length];
		in.readFully(result);
		return result;
	}

	private static List<Object> readArray(final DataInputStream in, final int size, final int depth) throws IOException {
		if (size < 0 || size > MAX_PAYLOAD_BYTES) {
			throw new IOException("The stored value declares " + size + " array entries, which cannot be part of a "
					+ MAX_PAYLOAD_BYTES + "-byte payload");
		}
		List<Object> result = new ArrayList<>(Math.min(size, 256));
		for (int i = 0; i < size; i++) {
			result.add(read(in, depth + 1));
		}
		return result;
	}

	private static Map<String, Object> readMap(final DataInputStream in, final int size, final int depth) throws IOException {
		if (size < 0 || size > MAX_PAYLOAD_BYTES) {
			throw new IOException("The stored value declares " + size + " map entries, which cannot be part of a "
					+ MAX_PAYLOAD_BYTES + "-byte payload");
		}
		Map<String, Object> result = new LinkedHashMap<>(Math.min(size, 256));
		for (int i = 0; i < size; i++) {
			Object key = read(in, depth + 1);
			if (!(key instanceof String name)) {
				throw new IOException("A stored map has a " + (key == null ? "null" : key.getClass().getName())
						+ " key where a string was expected");
			}
			result.put(name, read(in, depth + 1));
		}
		return result;
	}
}
