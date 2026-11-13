package org.openntf.xsp.util.codec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.MessagePackException;
import org.msgpack.value.Value;

/**
 * Stores a value as MessagePack: maps, lists, strings, numbers, booleans, nil, binary and
 * the date wrapper {@link StructuredValues} adds.
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
 * Both directions are bounded by {@link #MAX_PAYLOAD_BYTES}. The encode check keeps a
 * mistake from writing a huge value into the document, and the decode check stops a stored
 * payload from asking for an unreasonable amount of memory before the format parser even
 * starts.
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
		try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
			write(packer, tree);
			byte[] payload = packer.toByteArray();
			if (payload.length > MAX_PAYLOAD_BYTES) {
				throw new IOException("The encoded value is " + payload.length + " bytes, more than the " + MAX_PAYLOAD_BYTES
						+ "-byte limit; store it as a file or split it into several items");
			}
			return payload;
		}
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
		try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload)) {
			Value value = unpacker.unpackValue();
			if (unpacker.hasNext()) {
				throw new IOException("The stored value has trailing data after its first value");
			}
			return StructuredValues.materialise(read(value));
		} catch (MessagePackException e) {
			// A truncated or corrupted item is a read failure, not a reason to guess
			throw new IOException("The stored value is not valid MessagePack: " + e.getMessage(), e);
		}
	}

	private static void write(final MessagePacker packer, final Object value) throws IOException {
		if (value == null) {
			packer.packNil();
		} else if (value instanceof String) {
			packer.packString((String) value);
		} else if (value instanceof Boolean) {
			packer.packBoolean((Boolean) value);
		} else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
			packer.packLong(((Number) value).longValue());
		} else if (value instanceof Float || value instanceof Double) {
			packer.packDouble(((Number) value).doubleValue());
		} else if (value instanceof byte[]) {
			byte[] bytes = (byte[]) value;
			packer.packBinaryHeader(bytes.length);
			packer.writePayload(bytes);
		} else if (value instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) value;
			packer.packMapHeader(map.size());
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				packer.packString(String.valueOf(entry.getKey()));
				write(packer, entry.getValue());
			}
		} else if (value instanceof List) {
			List<?> list = (List<?>) value;
			packer.packArrayHeader(list.size());
			for (Object each : list) {
				write(packer, each);
			}
		} else {
			throw new IOException("The value tree contains " + value.getClass().getName()
					+ ", which StructuredValues.normalise should have rejected");
		}
	}

	private static Object read(final Value value) throws IOException {
		if (value == null || value.isNilValue()) {
			return null;
		}
		if (value.isBooleanValue()) {
			return value.asBooleanValue().getBoolean();
		}
		if (value.isIntegerValue()) {
			return value.asIntegerValue().toLong();
		}
		if (value.isFloatValue()) {
			return value.asFloatValue().toDouble();
		}
		if (value.isStringValue()) {
			return value.asStringValue().asString();
		}
		if (value.isBinaryValue()) {
			return value.asBinaryValue().asByteArray();
		}
		if (value.isArrayValue()) {
			List<Object> result = new ArrayList<>(value.asArrayValue().size());
			for (Value each : value.asArrayValue().list()) {
				result.add(read(each));
			}
			return result;
		}
		if (value.isMapValue()) {
			Map<Value, Value> map = value.asMapValue().map();
			Map<String, Object> result = new LinkedHashMap<>(map.size());
			for (Map.Entry<Value, Value> entry : map.entrySet()) {
				Value key = entry.getKey();
				if (!key.isStringValue()) {
					throw new IOException("A stored map has a " + key.getValueType() + " key where a string was expected");
				}
				result.put(key.asStringValue().asString(), read(entry.getValue()));
			}
			return result;
		}
		throw new IOException("A stored value has the unsupported type " + value.getValueType());
	}
}
