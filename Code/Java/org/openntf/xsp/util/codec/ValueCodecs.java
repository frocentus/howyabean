package org.openntf.xsp.util.codec;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The configured {@link ValueCodec}s, and the one place that decides which of them stores a
 * value and which of them reads an item back.
 * 
 * <p>
 * The registry exists so that the storage format is a decision made by the application once,
 * in one list, instead of an {@code instanceof} chain in the middle of
 * {@code replaceItemValue}. Adding a format means adding one entry here plus one class; it
 * does not mean touching the model.
 * </p>
 * 
 * <p>
 * Reading dispatches on the stored scheme, never on a type named by the payload. An item
 * with an unknown scheme is a hard failure: silently falling back to something else is how
 * a document quietly becomes unreadable.
 * </p>
 */
public final class ValueCodecs {
	private static final List<ValueCodec> CODECS = Collections.unmodifiableList(Arrays.<ValueCodec>asList(
			new MessagePackCodec()));

	private static final Map<String, ValueCodec> BY_SCHEME;

	static {
		Map<String, ValueCodec> byScheme = new LinkedHashMap<>();
		for (ValueCodec codec : CODECS) {
			byScheme.put(codec.scheme(), codec);
		}
		BY_SCHEME = Collections.unmodifiableMap(byScheme);
	}

	private ValueCodecs() {
	}

	/** The codec that stores values under this scheme, or {@code null} if there is none. */
	public static ValueCodec byScheme(final String scheme) {
		return scheme == null ? null : BY_SCHEME.get(scheme);
	}

	/** The first configured codec that is willing to encode the value, or {@code null}. */
	public static ValueCodec forValue(final Object value) {
		for (ValueCodec codec : CODECS) {
			if (codec.canEncode(value)) {
				return codec;
			}
		}
		return null;
	}

	/**
	 * The stored form of a value.
	 * 
	 * @throws IllegalArgumentException
	 *             if no codec can represent the value
	 */
	public static Encoded encode(final Object value) throws IOException {
		ValueCodec codec = forValue(value);
		if (codec == null) {
			throw new IllegalArgumentException("No configured codec can store "
					+ (value == null ? "null" : "a value of type " + value.getClass().getName()));
		}
		return new Encoded(codec, value, codec.encode(value));
	}

	/**
	 * The value of a stored payload.
	 * 
	 * @throws IllegalArgumentException
	 *             if the scheme is not configured
	 */
	public static Object decode(final String scheme, final byte[] payload) throws IOException {
		ValueCodec codec = byScheme(scheme);
		if (codec == null) {
			throw new IllegalArgumentException("No codec is registered for the storage scheme '" + scheme
					+ "', so the item cannot be read");
		}
		return codec.decode(payload);
	}

	/**
	 * A payload together with the scheme, content type and original type it is stored as.
	 * The type name is written for diagnosis only: nothing is ever instantiated from it.
	 */
	public static final class Encoded {
		private final String scheme;
		private final String contentType;
		private final String type;
		private final byte[] payload;

		Encoded(final ValueCodec codec, final Object value, final byte[] payload) {
			this.scheme = codec.scheme();
			this.contentType = codec.contentType();
			this.type = value.getClass().getName();
			this.payload = payload;
		}

		public String scheme() {
			return scheme;
		}

		public String contentType() {
			return contentType;
		}

		public String type() {
			return type;
		}

		public byte[] payload() {
			return payload;
		}
	}
}
