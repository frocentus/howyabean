package org.openntf.xsp.util.codec;

import java.util.Map;

/**
 * A value that can describe itself as a plain map, so that it can be stored by a
 * {@link ValueCodec} instead of by Java serialization.
 * 
 * <p>
 * The point of the indirection is that decoding never has to name a class: a codec writes
 * maps, lists and scalars, and the map it writes names the type as data, which
 * {@link StructuredTypes} resolves against the factories that were registered at runtime.
 * A payload can therefore never ask for a class that the application did not compile in.
 * </p>
 * 
 * <p>
 * The returned map must only contain values that are already storable: {@code null},
 * strings, {@code Boolean}, {@code Byte} to {@code Double}, {@link java.util.Date},
 * {@code byte[]}, maps with string keys, collections, or further {@link Structured}
 * values. The key {@value #TYPE_KEY} is reserved for the type name.
 * </p>
 */
public interface Structured {
	/** The key a codec adds to name the type of a structured value. */
	String TYPE_KEY = "@type";

	/**
	 * The name this type is stored under. It only has to be stable and unique, not a class
	 * name; renaming it makes the existing values unreadable, which is the same cost as
	 * changing the field names.
	 */
	String type();

	/** This value as a map of field name to storable value. */
	Map<String, Object> toMap();
}
