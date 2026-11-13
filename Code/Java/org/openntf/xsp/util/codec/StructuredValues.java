package org.openntf.xsp.util.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Converts between the application's values and the plain map/list/scalar tree that a
 * {@link ValueCodec} can encode.
 * 
 * <p>
 * Two directions, both strict:
 * </p>
 * <ul>
 * <li>{@link #normalise(Object)} runs on save and rejects anything it cannot represent
 * losslessly, so an unsupported value fails while the caller can still do something about
 * it rather than turning into an opaque blob;</li>
 * <li>{@link #materialise(Object)} runs on read and rebuilds objects only through
 * {@link StructuredTypes}, so the payload chooses fields, never classes.</li>
 * </ul>
 * 
 * <p>
 * Built-in types are {@code null}, strings, {@code Boolean}, {@code Byte} to {@code Long},
 * {@code Float} and {@code Double}, {@code byte[]}, {@link Date} (as milliseconds under a
 * reserved type name), maps with string keys and collections. Deliberately absent are
 * {@code BigDecimal}, {@code BigInteger} and the temporal types: they would need a
 * representation that round-trips exactly, and a wrong conversion into a Domino number is
 * worse than an exception that names the field.
 * </p>
 */
public final class StructuredValues {
	/** The key a codec adds to name the type of a structured value. */
	public static final String TYPE_KEY = Structured.TYPE_KEY;
	/** The key a codec uses for the single value of a built-in wrapper such as a date. */
	public static final String VALUE_KEY = "@value";
	/** The reserved type name of {@link Date}. */
	public static final String DATE_TYPE = "date";

	private StructuredValues() {
	}

	/**
	 * The plain tree for a value, ready to be encoded.
	 * 
	 * @throws IllegalArgumentException
	 *             if the value cannot be represented, naming the type and the alternative
	 */
	public static Object normalise(final Object value) {
		if (value == null || value instanceof String || value instanceof Boolean || value instanceof byte[]
				|| value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
				|| value instanceof Float || value instanceof Double) {
			return value;
		}
		if (value instanceof CharSequence sequence) {
			return sequence.toString();
		}
		if (value instanceof Date date) {
			Map<String, Object> result = new LinkedHashMap<>(2);
			result.put(TYPE_KEY, DATE_TYPE);
			result.put(VALUE_KEY, date.getTime());
			return result;
		}
		if (value instanceof Structured structured) {
			if (structured.type() == null || structured.type().isEmpty()) {
				throw new IllegalArgumentException("A " + structured.getClass().getName() + " value has no type name to store it under");
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put(TYPE_KEY, structured.type());
			for (Map.Entry<String, Object> entry : structured.toMap().entrySet()) {
				result.put(field(entry.getKey(), structured.getClass()), normalise(entry.getValue()));
			}
			return result;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() instanceof String name) {
					result.put(field(name, value.getClass()), normalise(entry.getValue()));
				} else {
					Object key = entry.getKey();
					throw new IllegalArgumentException("A stored map needs string keys, but found "
							+ (key == null ? "null" : "a " + key.getClass().getName()));
				}
			}
			return result;
		}
		if (value instanceof Iterable<?> iterable) {
			List<Object> result = new ArrayList<>();
			for (Object each : iterable) {
				result.add(normalise(each));
			}
			return result;
		}
		if (value instanceof Object[] array) {
			return normalise(Arrays.asList(array));
		}
		throw new IllegalArgumentException("Cannot store a value of type " + value.getClass().getName()
				+ ": use a string, number, boolean, date, byte[], map, collection or a Structured implementation");
	}

	private static String field(final String name, final Class<?> owner) {
		if (name == null) {
			throw new IllegalArgumentException("A " + owner.getName() + " value has a null field name");
		}
		if (TYPE_KEY.equals(name)) {
			throw new IllegalArgumentException("The field name " + TYPE_KEY + " is reserved for the stored type of "
					+ owner.getName());
		}
		return name;
	}

	/**
	 * The application value for a decoded tree: maps that name a registered type become
	 * objects again, everything else stays a plain map, list or scalar.
	 * 
	 * @throws IllegalArgumentException
	 *             if a map names a type that is not registered
	 */
	@SuppressWarnings("unchecked")
	public static Object materialise(final Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> source = (Map<String, Object>) map;
			Object type = source.get(TYPE_KEY);
			Map<String, Object> fields = new LinkedHashMap<>(source.size());
			for (Map.Entry<String, Object> entry : source.entrySet()) {
				if (!TYPE_KEY.equals(entry.getKey())) {
					fields.put(entry.getKey(), materialise(entry.getValue()));
				}
			}
			if (DATE_TYPE.equals(type)) {
				Object millis = fields.get(VALUE_KEY);
				if (!(millis instanceof Number)) {
					throw new IllegalArgumentException("A stored date needs a numeric " + VALUE_KEY + " value");
				}
				return new Date(((Number) millis).longValue());
			}
			if (type != null) {
				Function<Map<String, Object>, ?> factory = StructuredTypes.factoryFor(String.valueOf(type));
				return factory.apply(fields);
			}
			return fields;
		}
		if (value instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object each : list) {
				result.add(materialise(each));
			}
			return result;
		}
		return value;
	}
}
